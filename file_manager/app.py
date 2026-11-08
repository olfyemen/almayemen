# -*- coding: utf-8 -*-
"""
الما — مدير الملفات على الشبكة المحلية
=====================================
برنامج ويب محلي لإدارة الملفات مع أدوات كثيرة لتسريع العمل:
إعادة تسمية دفعة واحدة، تنظيم وفرز، تحليل المساحة، الملفات المكررة،
تصغير الصور والفيديوهات، تعديل البيانات الوصفية، أدوات النصوص و PDF ... إلخ.

التشغيل:
    pip install -r requirements.txt
    python app.py                       # يشارك مجلد المستخدم
    python app.py --root "D:/Files"     # مجلد محدد (يمكن تكرار --root)
    python app.py --password 1234       # حماية بكلمة مرور
ثم افتح الرابط الظاهر من أي جهاز على نفس الشبكة.
"""
import argparse
import csv
import datetime as dt
import functools
import hashlib
import io
import json
import mimetypes
import os
import random
import re
import secrets
import shutil
import socket
import string
import subprocess
import sys
import threading
import time
import uuid
import zipfile
from pathlib import Path

from flask import (Flask, Response, abort, jsonify, redirect, request,
                   send_file, session, stream_with_context)

try:
    from PIL import Image, ImageDraw, ImageFont, ImageOps, ExifTags
    HAS_PIL = True
except ImportError:  # pragma: no cover
    HAS_PIL = False

try:
    import mutagen
    HAS_MUTAGEN = True
except ImportError:  # pragma: no cover
    HAS_MUTAGEN = False

try:
    import pypdf
    HAS_PYPDF = True
except ImportError:  # pragma: no cover
    HAS_PYPDF = False

APP_DIR = Path(__file__).resolve().parent
CACHE_DIR = APP_DIR / ".cache"
THUMB_DIR = CACHE_DIR / "thumbs"
TRASH_NAME = ".alma_trash"
FFMPEG = shutil.which("ffmpeg")
FFPROBE = shutil.which("ffprobe")

app = Flask(__name__, static_folder=str(APP_DIR / "static"), static_url_path="/static")
app.config["MAX_CONTENT_LENGTH"] = None  # لا حد لحجم الرفع

ROOTS = {}          # اسم -> Path
PASSWORD = ""
READ_ONLY = False
UNDO_STACK = []     # كل عنصر: {"title":..., "moves": [(src, dst), ...]}
UNDO_LOCK = threading.Lock()
JOBS = {}

# ----------------------------------------------------------------------------
# تصنيف الملفات
# ----------------------------------------------------------------------------
CATEGORIES = {
    "صور": {"jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "heic", "heif", "svg", "ico", "raw", "cr2", "nef", "arw", "dng", "psd"},
    "فيديو": {"mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpg", "mpeg", "ts", "mts", "m2ts", "vob"},
    "صوتيات": {"mp3", "wav", "aac", "flac", "ogg", "m4a", "wma", "opus", "amr", "aiff", "mid", "midi"},
    "مستندات": {"pdf", "doc", "docx", "odt", "rtf", "txt", "md", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "pages", "numbers", "key"},
    "مضغوطات": {"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "cab"},
    "برامج": {"exe", "msi", "apk", "dmg", "deb", "rpm", "appimage", "bat", "cmd", "sh", "jar"},
    "برمجة": {"py", "js", "ts", "html", "htm", "css", "json", "xml", "yml", "yaml", "java", "c", "cpp", "h", "cs", "php", "rb", "go", "rs", "sql", "ini", "cfg", "log", "kt", "swift", "dart", "vue"},
    "خطوط": {"ttf", "otf", "woff", "woff2", "eot"},
}
EXT_TO_CAT = {e: c for c, exts in CATEGORIES.items() for e in exts}
TEXT_EXTS = {"txt", "md", "csv", "json", "xml", "yml", "yaml", "ini", "cfg", "log", "py", "js", "ts", "html",
             "htm", "css", "java", "c", "cpp", "h", "cs", "php", "rb", "go", "rs", "sql", "sh", "bat", "cmd",
             "srt", "vtt", "ass", "kt", "swift", "dart", "vue", "env", "toml", "conf", "tsv"}
IMAGE_EXTS = {"jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "ico"}
VIDEO_EXTS = CATEGORIES["فيديو"]
AUDIO_EXTS = CATEGORIES["صوتيات"]


def ext_of(name):
    _, e = os.path.splitext(name)
    return e[1:].lower()


def category_of(name, is_dir=False):
    if is_dir:
        return "مجلد"
    return EXT_TO_CAT.get(ext_of(name), "أخرى")


def human_size(n):
    n = float(n or 0)
    for unit in ["بايت", "ك.ب", "م.ب", "ج.ب", "ت.ب"]:
        if n < 1024 or unit == "ت.ب":
            return f"{n:.0f} {unit}" if unit == "بايت" else f"{n:.1f} {unit}"
        n /= 1024


def natural_key(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


# ----------------------------------------------------------------------------
# المسارات الآمنة
# ----------------------------------------------------------------------------
class PathError(Exception):
    pass


def safe_path(rel, must_exist=True):
    """يحوّل مساراً نسبياً من الواجهة (اسم_الجذر/مسار/فرعي) إلى مسار حقيقي آمن."""
    rel = (rel or "").replace("\\", "/").strip("/")
    if not rel:
        raise PathError("مسار فارغ")
    root_name, _, sub = rel.partition("/")
    if root_name not in ROOTS:
        raise PathError("الموقع غير موجود")
    root = ROOTS[root_name]
    p = (root / sub).resolve() if sub else root
    if p != root and root not in p.parents:
        raise PathError("مسار غير مسموح")
    if must_exist and not p.exists():
        raise PathError(f"غير موجود: {rel}")
    return p


def to_rel(p):
    p = Path(os.path.abspath(p))
    for name, root in ROOTS.items():
        if p == root:
            return name
        if root in p.parents:
            return name + "/" + p.relative_to(root).as_posix()
    raise PathError("خارج المواقع المسموحة")


def root_of(p):
    p = Path(os.path.abspath(p))
    for root in ROOTS.values():
        if p == root or root in p.parents:
            return root
    raise PathError("خارج المواقع المسموحة")


def is_internal(name):
    return name == TRASH_NAME


def unique_path(p):
    """يُرجع مساراً غير مستخدم بإضافة (2) (3) ..."""
    p = Path(p)
    if not p.exists():
        return p
    stem, suf = (p.name, "") if p.is_dir() else (p.stem, p.suffix)
    i = 2
    while True:
        cand = p.with_name(f"{stem} ({i}){suf}")
        if not cand.exists():
            return cand
        i += 1


def valid_name(name):
    name = (name or "").strip()
    if not name or name in (".", "..") or "/" in name or "\\" in name or "\0" in name:
        return False
    if os.name == "nt" and re.search(r'[<>:"|?*]', name):
        return False
    return True


def walk_files(base, include_hidden=False):
    """يمر على كل الملفات تحت مجلد (بدون السلة)."""
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if not is_internal(d) and (include_hidden or not d.startswith("."))]
        for f in filenames:
            if not include_hidden and f.startswith("."):
                continue
            yield Path(dirpath) / f


def entry_info(p):
    try:
        st = p.stat()
        rel = to_rel(p)
    except (OSError, PathError):
        return None
    is_dir = p.is_dir()
    return {
        "name": p.name,
        "path": rel,
        "is_dir": is_dir,
        "size": 0 if is_dir else st.st_size,
        "mtime": st.st_mtime,
        "ext": "" if is_dir else ext_of(p.name),
        "cat": category_of(p.name, is_dir),
    }


# ----------------------------------------------------------------------------
# الأمان: كلمة المرور
# ----------------------------------------------------------------------------
def login_required(fn):
    @functools.wraps(fn)
    def wrapper(*a, **kw):
        if PASSWORD and not session.get("ok"):
            if request.path.startswith("/api/"):
                return jsonify({"error": "يلزم تسجيل الدخول"}), 401
            return redirect("/login")
        return fn(*a, **kw)
    return wrapper


def write_guard():
    if READ_ONLY:
        raise PathError("البرنامج يعمل بوضع القراءة فقط")


@app.errorhandler(PathError)
def path_error(e):
    return jsonify({"error": str(e)}), 400


@app.errorhandler(Exception)
def any_error(e):
    from werkzeug.exceptions import HTTPException
    if isinstance(e, HTTPException):
        return e
    app.logger.exception(e)
    return jsonify({"error": f"خطأ: {e}"}), 500


LOGIN_HTML = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>الدخول — الما</title>
<link rel="stylesheet" href="/static/style.css"></head><body class="login-body">
<form method="post" class="login-card"><div class="logo">📁</div><h2>الما — مدير الملفات</h2>
<p>{msg}</p><input type="password" name="password" placeholder="كلمة المرور" autofocus>
<button class="btn primary" type="submit">دخول</button></form></body></html>"""


@app.route("/login", methods=["GET", "POST"])
def login():
    msg = "أدخل كلمة المرور للمتابعة"
    if request.method == "POST":
        if secrets.compare_digest(request.form.get("password", ""), PASSWORD):
            session["ok"] = True
            session.permanent = True
            return redirect("/")
        msg = "كلمة المرور غير صحيحة"
        time.sleep(1)
    return LOGIN_HTML.replace("{msg}", msg)


@app.route("/logout")
def logout():
    session.clear()
    return redirect("/login")


@app.route("/")
@login_required
def index():
    return send_file(APP_DIR / "templates" / "index.html")


def J():
    return request.get_json(silent=True) or {}


# ----------------------------------------------------------------------------
# المهام الطويلة (تعمل بالخلفية مع نسبة تقدم)
# ----------------------------------------------------------------------------
def start_job(title, fn, *args):
    jid = uuid.uuid4().hex[:10]
    job = {"id": jid, "title": title, "status": "running", "progress": 0, "message": "",
           "result": None, "log": [], "started": time.time(), "cancel": False}
    JOBS[jid] = job

    def run():
        try:
            job["result"] = fn(job, *args)
            job["status"] = "cancelled" if job["cancel"] else "done"
            job["progress"] = 100
        except Exception as e:  # noqa
            job["status"] = "error"
            job["message"] = str(e)
        job["ended"] = time.time()

    threading.Thread(target=run, daemon=True).start()
    # احذف المهام القديمة
    if len(JOBS) > 60:
        for k in sorted(JOBS, key=lambda k: JOBS[k]["started"])[:20]:
            if JOBS[k]["status"] != "running":
                JOBS.pop(k, None)
    return jid


def job_log(job, msg):
    job["log"].append(msg)
    job["message"] = msg
    if len(job["log"]) > 500:
        job["log"] = job["log"][-500:]


@app.get("/api/jobs")
@login_required
def api_jobs():
    out = []
    for j in sorted(JOBS.values(), key=lambda j: -j["started"]):
        out.append({k: v for k, v in j.items() if k != "result"} | {"has_result": j["result"] is not None})
    return jsonify(out)


@app.get("/api/jobs/<jid>")
@login_required
def api_job(jid):
    j = JOBS.get(jid)
    if not j:
        abort(404)
    return jsonify(j)


@app.post("/api/jobs/<jid>/cancel")
@login_required
def api_job_cancel(jid):
    j = JOBS.get(jid)
    if j:
        j["cancel"] = True
    return jsonify({"ok": True})


@app.post("/api/jobs/clear")
@login_required
def api_jobs_clear():
    for k in list(JOBS):
        if JOBS[k]["status"] != "running":
            JOBS.pop(k, None)
    return jsonify({"ok": True})


# ----------------------------------------------------------------------------
# التراجع
# ----------------------------------------------------------------------------
def push_undo(title, moves):
    moves = [(str(a), str(b)) for a, b in moves if str(a) != str(b)]
    if not moves:
        return
    with UNDO_LOCK:
        UNDO_STACK.append({"title": title, "moves": moves, "time": time.time()})
        del UNDO_STACK[:-30]


@app.get("/api/undo")
@login_required
def api_undo_list():
    return jsonify([{"title": u["title"], "count": len(u["moves"]), "time": u["time"]} for u in reversed(UNDO_STACK)])


@app.post("/api/undo")
@login_required
def api_undo():
    write_guard()
    with UNDO_LOCK:
        if not UNDO_STACK:
            return jsonify({"error": "لا توجد عمليات للتراجع"}), 400
        op = UNDO_STACK.pop()
    done, failed = 0, []
    for src, dst in reversed(op["moves"]):
        try:
            if Path(dst).exists() and not Path(src).exists():
                Path(src).parent.mkdir(parents=True, exist_ok=True)
                shutil.move(dst, src)
                done += 1
                # احذف المجلدات التي أصبحت فارغة بعد التراجع
                parent = Path(dst).parent
                try:
                    root = root_of(parent)
                    while parent != root and not any(parent.iterdir()):
                        parent.rmdir()
                        parent = parent.parent
                except (OSError, PathError):
                    pass
            else:
                failed.append(Path(dst).name)
        except OSError as e:
            failed.append(f"{Path(dst).name}: {e}")
    return jsonify({"ok": True, "title": op["title"], "done": done, "failed": failed})


# ----------------------------------------------------------------------------
# معلومات عامة
# ----------------------------------------------------------------------------
def lan_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    ips.discard("127.0.0.1")
    return sorted(ips) or ["127.0.0.1"]


@app.get("/api/info")
@login_required
def api_info():
    roots = []
    for name, p in ROOTS.items():
        try:
            du = shutil.disk_usage(p)
            roots.append({"name": name, "path": str(p), "total": du.total, "used": du.used, "free": du.free})
        except OSError:
            roots.append({"name": name, "path": str(p)})
    port = request.host.split(":")[-1] if ":" in request.host else "80"
    return jsonify({
        "roots": roots,
        "urls": [f"http://{ip}:{port}" for ip in lan_ips()],
        "features": {"pil": HAS_PIL, "ffmpeg": bool(FFMPEG), "mutagen": HAS_MUTAGEN, "pypdf": HAS_PYPDF},
        "read_only": READ_ONLY,
        "password": bool(PASSWORD),
        "undo": len(UNDO_STACK),
    })


# ----------------------------------------------------------------------------
# التصفح والعمليات الأساسية
# ----------------------------------------------------------------------------
@app.get("/api/list")
@login_required
def api_list():
    rel = request.args.get("path", "")
    hidden = request.args.get("hidden") == "1"
    if not rel.strip("/"):
        return jsonify({"path": "", "entries": [
            {"name": n, "path": n, "is_dir": True, "size": 0, "mtime": p.stat().st_mtime, "ext": "", "cat": "مجلد", "root": True}
            for n, p in ROOTS.items()]})
    p = safe_path(rel)
    if not p.is_dir():
        raise PathError("ليس مجلداً")
    entries = []
    try:
        it = list(os.scandir(p))
    except PermissionError:
        raise PathError("لا توجد صلاحية لفتح هذا المجلد")
    for e in it:
        if is_internal(e.name) or (not hidden and e.name.startswith(".")):
            continue
        info = entry_info(Path(e.path))
        if info:
            entries.append(info)
    return jsonify({"path": to_rel(p), "entries": entries})


@app.get("/api/tree")
@login_required
def api_tree():
    """قائمة المجلدات الفرعية (لاختيار وجهة النقل/النسخ)."""
    rel = request.args.get("path", "")
    if not rel.strip("/"):
        return jsonify([{"name": n, "path": n} for n in ROOTS])
    p = safe_path(rel)
    out = []
    try:
        for e in sorted(os.scandir(p), key=lambda e: natural_key(e.name)):
            if e.is_dir() and not e.name.startswith(".") and not is_internal(e.name):
                out.append({"name": e.name, "path": to_rel(Path(e.path))})
    except PermissionError:
        pass
    return jsonify(out)


@app.get("/api/file")
@login_required
def api_file():
    p = safe_path(request.args.get("path"))
    if p.is_dir():
        return zip_response([p], p.name + ".zip")
    dl = request.args.get("dl") == "1"
    mt = mimetypes.guess_type(p.name)[0] or "application/octet-stream"
    return send_file(p, mimetype=mt, as_attachment=dl, download_name=p.name, conditional=True)


def zip_response(paths, name):
    """ينشئ ملف ZIP مؤقت ويرسله."""
    CACHE_DIR.mkdir(exist_ok=True)
    tmp = CACHE_DIR / f"dl_{uuid.uuid4().hex}.zip"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as z:
        for p in paths:
            if p.is_dir():
                for f in walk_files(p, True):
                    z.write(f, (Path(p.name) / f.relative_to(p)).as_posix())
            else:
                z.write(p, p.name)

    def gen():
        try:
            with open(tmp, "rb") as fh:
                while chunk := fh.read(1 << 20):
                    yield chunk
        finally:
            try:
                tmp.unlink()
            except OSError:
                pass
    headers = {"Content-Disposition": f"attachment; filename*=UTF-8''{_quote(name)}",
               "Content-Length": str(tmp.stat().st_size)}
    return Response(stream_with_context(gen()), mimetype="application/zip", headers=headers)


def _quote(s):
    from urllib.parse import quote
    return quote(s)


@app.post("/api/download_zip")
@login_required
def api_download_zip():
    paths = [safe_path(x) for x in (request.form.getlist("paths") or J().get("paths", []))]
    name = request.form.get("name") or "files.zip"
    return zip_response(paths, name)


@app.post("/api/upload")
@login_required
def api_upload():
    write_guard()
    dest = safe_path(request.form.get("path"))
    if not dest.is_dir():
        raise PathError("الوجهة ليست مجلداً")
    saved = []
    rels = request.form.getlist("relpath")
    for i, f in enumerate(request.files.getlist("files")):
        relname = rels[i] if i < len(rels) and rels[i] else f.filename
        relname = relname.replace("\\", "/")
        parts = [x for x in relname.split("/") if x not in ("", ".", "..")]
        if not parts:
            continue
        target = dest.joinpath(*parts).resolve()
        if dest not in target.parents:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target = unique_path(target)
        f.save(target)
        saved.append(target.name)
    return jsonify({"ok": True, "saved": saved})


@app.post("/api/mkdir")
@login_required
def api_mkdir():
    write_guard()
    d = J()
    parent = safe_path(d.get("path"))
    if not valid_name(d.get("name")):
        raise PathError("اسم غير صالح")
    target = parent / d["name"].strip()
    if target.exists():
        raise PathError("يوجد عنصر بنفس الاسم")
    target.mkdir(parents=True)
    return jsonify({"ok": True})


@app.post("/api/newfile")
@login_required
def api_newfile():
    write_guard()
    d = J()
    parent = safe_path(d.get("path"))
    if not valid_name(d.get("name")):
        raise PathError("اسم غير صالح")
    target = parent / d["name"].strip()
    if target.exists():
        raise PathError("يوجد عنصر بنفس الاسم")
    target.write_text(d.get("content", ""), encoding="utf-8")
    return jsonify({"ok": True})


@app.post("/api/rename")
@login_required
def api_rename():
    write_guard()
    d = J()
    p = safe_path(d.get("path"))
    if p in ROOTS.values():
        raise PathError("لا يمكن إعادة تسمية الموقع الرئيسي")
    new = (d.get("name") or "").strip()
    if not valid_name(new):
        raise PathError("اسم غير صالح")
    target = p.with_name(new)
    if target.exists() and target.name.lower() != p.name.lower():
        raise PathError("يوجد عنصر بنفس الاسم")
    os.rename(p, target)
    push_undo(f"إعادة تسمية {p.name}", [(p, target)])
    return jsonify({"ok": True})


def to_trash(p):
    root = root_of(p)
    tdir = root / TRASH_NAME
    tdir.mkdir(exist_ok=True)
    idx_file = tdir / "index.json"
    try:
        idx = json.loads(idx_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        idx = {}
    tid = uuid.uuid4().hex[:12]
    dest = tdir / f"{tid}__{p.name}"
    orig = to_rel(p)
    shutil.move(str(p), str(dest))
    idx[dest.name] = {"orig": orig, "name": p.name, "time": time.time(), "is_dir": dest.is_dir()}
    idx_file.write_text(json.dumps(idx, ensure_ascii=False), encoding="utf-8")


@app.post("/api/delete")
@login_required
def api_delete():
    write_guard()
    d = J()
    permanent = bool(d.get("permanent"))
    count = 0
    for rel in d.get("paths", []):
        p = safe_path(rel)
        if p in ROOTS.values():
            continue
        if permanent:
            shutil.rmtree(p) if p.is_dir() else p.unlink()
        else:
            to_trash(p)
        count += 1
    return jsonify({"ok": True, "count": count})


@app.get("/api/trash")
@login_required
def api_trash():
    items = []
    for name, root in ROOTS.items():
        idx_file = root / TRASH_NAME / "index.json"
        try:
            idx = json.loads(idx_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        for key, meta in idx.items():
            f = root / TRASH_NAME / key
            if not f.exists():
                continue
            size = f.stat().st_size if f.is_file() else sum(x.stat().st_size for x in walk_files(f, True))
            items.append({"id": f"{name}|{key}", "name": meta["name"], "orig": meta["orig"],
                          "time": meta["time"], "is_dir": f.is_dir(), "size": size})
    items.sort(key=lambda x: -x["time"])
    return jsonify(items)


def _trash_item(tid):
    rname, _, key = tid.partition("|")
    if rname not in ROOTS or "/" in key or "\\" in key or ".." in key:
        raise PathError("عنصر غير صالح")
    root = ROOTS[rname]
    tdir = root / TRASH_NAME
    idx_file = tdir / "index.json"
    idx = json.loads(idx_file.read_text(encoding="utf-8"))
    return root, tdir, idx_file, idx, key


@app.post("/api/trash/restore")
@login_required
def api_trash_restore():
    write_guard()
    restored = 0
    for tid in J().get("ids", []):
        root, tdir, idx_file, idx, key = _trash_item(tid)
        meta = idx.get(key)
        if not meta:
            continue
        try:
            dest = safe_path(meta["orig"], must_exist=False)
        except PathError:
            dest = root / meta["name"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest = unique_path(dest)
        shutil.move(str(tdir / key), str(dest))
        idx.pop(key, None)
        idx_file.write_text(json.dumps(idx, ensure_ascii=False), encoding="utf-8")
        restored += 1
    return jsonify({"ok": True, "count": restored})


@app.post("/api/trash/empty")
@login_required
def api_trash_empty():
    write_guard()
    ids = J().get("ids")
    if ids:
        for tid in ids:
            root, tdir, idx_file, idx, key = _trash_item(tid)
            f = tdir / key
            if f.exists():
                shutil.rmtree(f) if f.is_dir() else f.unlink()
            idx.pop(key, None)
            idx_file.write_text(json.dumps(idx, ensure_ascii=False), encoding="utf-8")
    else:
        for root in ROOTS.values():
            t = root / TRASH_NAME
            if t.exists():
                shutil.rmtree(t, ignore_errors=True)
    return jsonify({"ok": True})


def _transfer(paths, dest, mode):
    moves = []
    for rel in paths:
        src = safe_path(rel)
        if src in ROOTS.values():
            continue
        if src.is_dir() and (dest == src or src in dest.parents):
            raise PathError("لا يمكن نقل مجلد إلى داخل نفسه")
        target = dest / src.name
        if target.resolve() == src:
            if mode == "move":
                continue
        target = unique_path(target)
        if mode == "move":
            shutil.move(str(src), str(target))
            moves.append((src, target))
        else:
            if src.is_dir():
                shutil.copytree(src, target)
            else:
                shutil.copy2(src, target)
    return moves


@app.post("/api/move")
@login_required
def api_move():
    write_guard()
    d = J()
    dest = safe_path(d.get("dest"))
    moves = _transfer(d.get("paths", []), dest, "move")
    push_undo(f"نقل {len(moves)} عنصر", moves)
    return jsonify({"ok": True, "count": len(moves)})


@app.post("/api/copy")
@login_required
def api_copy():
    write_guard()
    d = J()
    dest = safe_path(d.get("dest"))
    _transfer(d.get("paths", []), dest, "copy")
    return jsonify({"ok": True})


@app.post("/api/duplicate")
@login_required
def api_duplicate():
    """عمل نسخة من الملف في نفس المكان."""
    write_guard()
    for rel in J().get("paths", []):
        src = safe_path(rel)
        target = unique_path(src.with_name(f"{src.stem} - نسخة{src.suffix}" if src.is_file() else f"{src.name} - نسخة"))
        shutil.copytree(src, target) if src.is_dir() else shutil.copy2(src, target)
    return jsonify({"ok": True})


@app.post("/api/zip")
@login_required
def api_zip():
    write_guard()
    d = J()
    paths = [safe_path(x) for x in d.get("paths", [])]
    if not paths:
        raise PathError("لم يتم تحديد ملفات")
    name = (d.get("name") or (paths[0].stem if len(paths) == 1 else "مضغوط")).strip()
    if not name.lower().endswith(".zip"):
        name += ".zip"
    if not valid_name(name):
        raise PathError("اسم غير صالح")
    target = unique_path(paths[0].parent / name)

    def work(job):
        files = []
        for p in paths:
            if p.is_dir():
                files += [(f, (Path(p.name) / f.relative_to(p)).as_posix()) for f in walk_files(p, True)]
            else:
                files.append((p, p.name))
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as z:
            for i, (f, arc) in enumerate(files):
                if job["cancel"]:
                    break
                z.write(f, arc)
                job["progress"] = int(100 * (i + 1) / max(1, len(files)))
                job["message"] = arc
        return {"file": to_rel(target), "size": target.stat().st_size}
    return jsonify({"job": start_job(f"ضغط إلى {target.name}", work)})


@app.post("/api/unzip")
@login_required
def api_unzip():
    write_guard()
    d = J()
    p = safe_path(d.get("path"))
    here = bool(d.get("here"))
    if not zipfile.is_zipfile(p):
        raise PathError("الملف ليس ZIP (يدعم فك ZIP فقط)")
    dest = p.parent if here else unique_path(p.with_suffix(""))

    def work(job):
        with zipfile.ZipFile(p) as z:
            members = z.infolist()
            for i, m in enumerate(members):
                name = m.filename
                # إصلاح الأسماء العربية المرمّزة بـ cp437
                if not (m.flag_bits & 0x800):
                    try:
                        name = name.encode("cp437").decode("utf-8")
                    except (UnicodeEncodeError, UnicodeDecodeError):
                        try:
                            name = name.encode("cp437").decode("cp1256")
                        except (UnicodeEncodeError, UnicodeDecodeError):
                            pass
                target = (dest / name).resolve()
                if dest.resolve() not in target.parents and target != dest.resolve():
                    continue
                if m.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with z.open(m) as src, open(target, "wb") as out:
                        shutil.copyfileobj(src, out)
                job["progress"] = int(100 * (i + 1) / max(1, len(members)))
        return {"dest": to_rel(dest)}
    return jsonify({"job": start_job(f"فك ضغط {p.name}", work)})


@app.get("/api/text")
@login_required
def api_text_get():
    p = safe_path(request.args.get("path"))
    if p.stat().st_size > 5 * 1024 * 1024:
        raise PathError("الملف كبير جداً للتحرير (أكثر من 5 م.ب)")
    raw = p.read_bytes()
    for enc in ("utf-8-sig", "cp1256", "latin-1"):
        try:
            return jsonify({"content": raw.decode(enc), "encoding": enc})
        except UnicodeDecodeError:
            continue
    return jsonify({"content": raw.decode("utf-8", "replace"), "encoding": "utf-8"})


@app.post("/api/text")
@login_required
def api_text_save():
    write_guard()
    d = J()
    p = safe_path(d.get("path"), must_exist=False)
    enc = d.get("encoding") or "utf-8"
    if enc == "utf-8-sig":
        enc = "utf-8"
    p.write_text(d.get("content", ""), encoding=enc, newline="")
    return jsonify({"ok": True})


@app.get("/api/props")
@login_required
def api_props():
    p = safe_path(request.args.get("path"))
    st = p.stat()
    info = {"name": p.name, "path": to_rel(p), "full": str(p), "is_dir": p.is_dir(),
            "mtime": st.st_mtime, "ctime": st.st_ctime, "atime": st.st_atime,
            "mime": mimetypes.guess_type(p.name)[0], "cat": category_of(p.name, p.is_dir())}
    if p.is_dir():
        total = files = dirs = 0
        for dirpath, dirnames, filenames in os.walk(p):
            dirnames[:] = [x for x in dirnames if not is_internal(x)]
            dirs += len(dirnames)
            for f in filenames:
                try:
                    total += os.path.getsize(os.path.join(dirpath, f))
                    files += 1
                except OSError:
                    pass
        info.update(size=total, files=files, dirs=dirs)
    else:
        info["size"] = st.st_size
        e = ext_of(p.name)
        if HAS_PIL and e in IMAGE_EXTS:
            try:
                with Image.open(p) as im:
                    info["dimensions"] = f"{im.width}×{im.height}"
                    info["mode"] = im.mode
            except Exception:
                pass
        if e in VIDEO_EXTS or e in AUDIO_EXTS:
            pi = probe(p)
            if pi:
                info.update({k: v for k, v in pi.items() if k in ("duration", "width", "height", "vcodec", "acodec", "bitrate", "fps")})
    return jsonify(info)


@app.post("/api/hash")
@login_required
def api_hash():
    p = safe_path(J().get("path"))
    if p.is_dir():
        raise PathError("اختر ملفاً")

    def work(job):
        hs = {"md5": hashlib.md5(), "sha1": hashlib.sha1(), "sha256": hashlib.sha256()}
        size = p.stat().st_size
        done = 0
        with open(p, "rb") as fh:
            while chunk := fh.read(1 << 20):
                for h in hs.values():
                    h.update(chunk)
                done += len(chunk)
                job["progress"] = int(100 * done / max(1, size))
                if job["cancel"]:
                    return None
        return {k: h.hexdigest() for k, h in hs.items()} | {"name": p.name}
    return jsonify({"job": start_job(f"بصمة {p.name}", work)})


# ----------------------------------------------------------------------------
# الصور المصغرة للمعاينة
# ----------------------------------------------------------------------------
@app.get("/api/thumb")
@login_required
def api_thumb():
    p = safe_path(request.args.get("path"))
    size = min(int(request.args.get("s", 256)), 1024)
    st = p.stat()
    key = hashlib.sha1(f"{p}|{st.st_mtime}|{st.st_size}|{size}".encode()).hexdigest()
    THUMB_DIR.mkdir(parents=True, exist_ok=True)
    out = THUMB_DIR / f"{key}.jpg"
    if not out.exists():
        e = ext_of(p.name)
        try:
            if e in IMAGE_EXTS and HAS_PIL:
                with Image.open(p) as im:
                    im = ImageOps.exif_transpose(im)
                    im.thumbnail((size, size))
                    if im.mode not in ("RGB", "L"):
                        bg = Image.new("RGB", im.size, (255, 255, 255))
                        im = im.convert("RGBA")
                        bg.paste(im, mask=im.split()[-1])
                        im = bg
                    im.save(out, "JPEG", quality=80)
            elif e in VIDEO_EXTS and FFMPEG:
                dur = (probe(p) or {}).get("duration") or 0
                t = min(max(dur * 0.1, 0), 10)
                subprocess.run([FFMPEG, "-y", "-ss", str(t), "-i", str(p), "-frames:v", "1",
                                "-vf", f"scale='min({size},iw)':-2", str(out)],
                               capture_output=True, timeout=30)
            else:
                abort(404)
        except subprocess.TimeoutExpired:
            abort(404)
        except Exception:
            abort(404)
    if not out.exists():
        abort(404)
    return send_file(out, mimetype="image/jpeg", max_age=86400)


# ----------------------------------------------------------------------------
# إعادة التسمية دفعة واحدة
# ----------------------------------------------------------------------------
TASHKEEL = re.compile(r"[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")
AR2LAT = {
    "ا": "a", "أ": "a", "إ": "e", "آ": "aa", "ب": "b", "ت": "t", "ث": "th", "ج": "j", "ح": "h", "خ": "kh",
    "د": "d", "ذ": "dh", "ر": "r", "ز": "z", "س": "s", "ش": "sh", "ص": "s", "ض": "d", "ط": "t", "ظ": "z",
    "ع": "a", "غ": "gh", "ف": "f", "ق": "q", "ك": "k", "ل": "l", "م": "m", "ن": "n", "ه": "h", "و": "w",
    "ي": "y", "ى": "a", "ة": "h", "ء": "", "ؤ": "o", "ئ": "e", "٠": "0", "١": "1", "٢": "2", "٣": "3",
    "٤": "4", "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9", "،": ",", "؛": ";", "؟": "?",
}
AR_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
EN_TO_AR_DIGITS = str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩")


def exif_datetime(p):
    if not HAS_PIL or ext_of(p.name) not in IMAGE_EXTS | {"heic"}:
        return None
    try:
        with Image.open(p) as im:
            ex = im.getexif()
            val = ex.get_ifd(0x8769).get(36867) or ex.get(306)
            if val:
                return dt.datetime.strptime(str(val).strip()[:19], "%Y:%m:%d %H:%M:%S")
    except Exception:
        return None
    return None


def file_date(p, source):
    if source == "exif":
        d = exif_datetime(p)
        if d:
            return d
    st = p.stat()
    if source == "ctime":
        return dt.datetime.fromtimestamp(getattr(st, "st_birthtime", st.st_ctime))
    if source == "now":
        return dt.datetime.now()
    return dt.datetime.fromtimestamp(st.st_mtime)


def image_size(p):
    if HAS_PIL and ext_of(p.name) in IMAGE_EXTS:
        try:
            with Image.open(p) as im:
                return im.size
        except Exception:
            pass
    return ("", "")


def sort_paths(paths, how):
    if how == "mtime":
        return sorted(paths, key=lambda p: p.stat().st_mtime)
    if how == "size":
        return sorted(paths, key=lambda p: p.stat().st_size)
    if how == "exif":
        return sorted(paths, key=lambda p: file_date(p, "exif"))
    if how == "none":
        return paths
    return sorted(paths, key=lambda p: natural_key(p.name))


def apply_rules(p, idx, total, rules):
    name = p.name
    is_dir = p.is_dir()
    if is_dir:
        stem, ext = name, ""
    else:
        stem, ext = os.path.splitext(name)
    for r in rules:
        t = r.get("type")
        target_ext = r.get("scope") == "all"
        s = stem + ext if target_ext else stem
        if t == "replace":
            find = r.get("find", "")
            repl = r.get("repl", "")
            if find:
                if r.get("regex"):
                    flags = 0 if r.get("case") else re.IGNORECASE
                    s = re.sub(find, repl, s, flags=flags)
                elif r.get("case"):
                    s = s.replace(find, repl)
                else:
                    s = re.sub(re.escape(find), lambda m: repl, s, flags=re.IGNORECASE)
        elif t == "prefix":
            s = r.get("text", "") + s
        elif t == "suffix":
            s = s + r.get("text", "")
        elif t == "insert":
            pos = int(r.get("pos") or 0)
            if r.get("from_end"):
                pos = max(0, len(s) - pos)
            pos = min(max(0, pos), len(s))
            s = s[:pos] + r.get("text", "") + s[pos:]
        elif t == "remove":
            start = int(r.get("start") or 0)
            count = int(r.get("count") or 0)
            if r.get("from_end"):
                end = len(s) - start
                s = s[:max(0, end - count)] + s[end:]
            else:
                s = s[:start] + s[start + count:]
        elif t == "keep":  # احتفظ بأول/آخر N حرف
            n = int(r.get("count") or 0)
            s = s[-n:] if r.get("from_end") else s[:n]
        elif t == "number":
            start = int(r.get("start") or 1)
            step = int(r.get("step") or 1)
            pad = int(r.get("pad") or 0)
            num = str(start + idx * step).zfill(pad)
            if r.get("arabic_digits"):
                num = num.translate(EN_TO_AR_DIGITS)
            sep = r.get("sep", "_")
            mode = r.get("mode", "prefix")
            if mode == "prefix":
                s = f"{num}{sep}{s}"
            elif mode == "suffix":
                s = f"{s}{sep}{num}"
            else:
                base = r.get("text", "")
                s = f"{base}{sep}{num}" if base else num
        elif t == "case":
            m = r.get("mode")
            if m == "upper":
                s = s.upper()
            elif m == "lower":
                s = s.lower()
            elif m == "title":
                s = s.title()
            elif m == "sentence":
                s = s[:1].upper() + s[1:].lower()
        elif t == "clean":
            if r.get("trim", True):
                s = s.strip()
            if r.get("collapse"):
                s = re.sub(r"\s+", " ", s)
            if r.get("spaces_to") is not None and r.get("spaces_to") != "":
                s = s.replace(" ", r["spaces_to"])
            if r.get("underscore_to_space"):
                s = s.replace("_", " ").replace(".", " ") if not target_ext else s.replace("_", " ")
            if r.get("remove_digits"):
                s = re.sub(r"[0-9٠-٩]", "", s)
            if r.get("remove_symbols"):
                s = re.sub(r"[^\w\s\-\.]", "", s)
            if r.get("remove_brackets"):
                s = re.sub(r"\s*[\(\[\{].*?[\)\]\}]", "", s)
            if r.get("tashkeel"):
                s = TASHKEEL.sub("", s)
            if r.get("ar_digits_to_en"):
                s = s.translate(AR_DIGITS)
            if r.get("translit"):
                s = "".join(AR2LAT.get(c, c) for c in TASHKEEL.sub("", s))
            if r.get("normalize_alef"):
                s = re.sub("[أإآ]", "ا", s).replace("ة", "ه").replace("ى", "ي")
        elif t == "date":
            d = file_date(p, r.get("source", "mtime"))
            txt = d.strftime(r.get("format") or "%Y-%m-%d")
            sep = r.get("sep", "_")
            mode = r.get("mode", "prefix")
            if mode == "prefix":
                s = f"{txt}{sep}{s}"
            elif mode == "suffix":
                s = f"{s}{sep}{txt}"
            else:
                s = txt
        elif t == "template":
            tpl = r.get("pattern", "{name}")
            d = file_date(p, r.get("source", "mtime"))
            w, h = image_size(p) if "{w}" in tpl or "{h}" in tpl else ("", "")
            parent = p.parent.name

            def num_sub(m):
                pad = int(m.group(1) or 0)
                return str(int(r.get("start") or 1) + idx).zfill(pad)

            out = re.sub(r"\{n(?::(\d+))?\}", num_sub, tpl)
            out = re.sub(r"\{date(?::([^}]+))?\}", lambda m: d.strftime(m.group(1) or "%Y-%m-%d"), out)
            out = (out.replace("{name}", s).replace("{parent}", parent).replace("{w}", str(w)).replace("{h}", str(h))
                   .replace("{ext}", ext[1:]).replace("{size}", human_size(0 if is_dir else p.stat().st_size).replace(" ", ""))
                   .replace("{total}", str(total))
                   .replace("{rand}", "".join(random.choices(string.ascii_lowercase + string.digits, k=6))))
            s = out
        elif t == "ext" and not is_dir:
            m = r.get("mode", "set")
            if m == "lower":
                ext = ext.lower()
            elif m == "upper":
                ext = ext.upper()
            elif m == "remove":
                ext = ""
            else:
                new = r.get("text", "").strip().lstrip(".")
                ext = ("." + new) if new else ""
            continue
        # إعادة التقسيم
        if target_ext and not is_dir:
            stem, ext = os.path.splitext(s)
        else:
            stem = s
    return stem + ext


def rename_plan(paths, rules, sort):
    paths = sort_paths(paths, sort)
    plan = []
    for i, p in enumerate(paths):
        try:
            new = apply_rules(p, i, len(paths), rules)
            err = None if valid_name(new) else "اسم غير صالح"
        except re.error as e:
            new, err = p.name, f"تعبير نمطي خاطئ: {e}"
        except Exception as e:  # noqa
            new, err = p.name, str(e)
        plan.append({"src": p, "new": new, "error": err})
    # التعارضات
    src_set = {str(x["src"]).lower() for x in plan}
    seen = {}
    for x in plan:
        tgt = x["src"].with_name(x["new"])
        key = str(tgt).lower()
        if x["error"]:
            continue
        if key in seen:
            x["error"] = "اسم مكرر"
            seen[key]["error"] = "اسم مكرر"
        elif tgt.exists() and key not in src_set:
            x["error"] = "يوجد ملف بنفس الاسم"
        seen[key] = x
    return plan


def collect_targets(d):
    """يجمع الملفات المطلوبة: إما قائمة مسارات أو كل ملفات مجلد."""
    paths = [safe_path(x) for x in d.get("paths", [])]
    if not paths and d.get("folder"):
        folder = safe_path(d["folder"])
        it = walk_files(folder) if d.get("recursive") else (x for x in folder.iterdir() if not is_internal(x.name) and not x.name.startswith("."))
        paths = list(it)
    flt = d.get("filter")
    if flt:
        f = flt.lower()
        cats = {c for c in CATEGORIES if c == flt}
        exts = {e.strip().lstrip(".").lower() for e in f.split(",") if e.strip()}
        paths = [p for p in paths if (cats and category_of(p.name, p.is_dir()) in cats) or (not cats and ext_of(p.name) in exts)]
    if d.get("files_only"):
        paths = [p for p in paths if p.is_file()]
    return paths


@app.post("/api/batch_rename")
@login_required
def api_batch_rename():
    d = J()
    paths = collect_targets(d)
    plan = rename_plan(paths, d.get("rules", []), d.get("sort", "name"))
    if not d.get("apply"):
        return jsonify({"items": [{"old": x["src"].name, "new": x["new"], "error": x["error"],
                                   "changed": x["new"] != x["src"].name} for x in plan[:2000]],
                        "total": len(plan), "errors": sum(1 for x in plan if x["error"]),
                        "changed": sum(1 for x in plan if x["new"] != x["src"].name)})
    write_guard()
    todo = [x for x in plan if not x["error"] and x["new"] != x["src"].name]
    # مرحلتان لتجنب التصادم عند تبديل الأسماء
    temps = []
    for x in todo:
        tmp = x["src"].with_name(f".alma_tmp_{uuid.uuid4().hex[:8]}")
        os.rename(x["src"], tmp)
        temps.append((x, tmp))
    moves = []
    for x, tmp in temps:
        final = x["src"].with_name(x["new"])
        if final.exists():
            final = unique_path(final)
        os.rename(tmp, final)
        moves.append((x["src"], final))
    push_undo(f"إعادة تسمية {len(moves)} ملف", moves)
    return jsonify({"ok": True, "count": len(moves), "skipped": len(plan) - len(todo)})


# ----------------------------------------------------------------------------
# التنظيم والفرز
# ----------------------------------------------------------------------------
SIZE_BUCKETS = [(1 << 20, "أقل من 1 م.ب"), (10 << 20, "1-10 م.ب"), (100 << 20, "10-100 م.ب"),
                (1 << 30, "100 م.ب - 1 ج.ب"), (float("inf"), "أكبر من 1 ج.ب")]
AR_MONTHS = ["يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"]


def organize_target(p, mode, opts):
    if mode == "type":
        return [category_of(p.name)]
    if mode == "ext":
        return [ext_of(p.name).upper() or "بدون امتداد"]
    if mode in ("year", "month", "day"):
        d = file_date(p, opts.get("source", "mtime"))
        if mode == "year":
            return [str(d.year)]
        mname = f"{d.month:02d}-{AR_MONTHS[d.month - 1]}" if opts.get("month_names") else f"{d.month:02d}"
        if mode == "month":
            return [str(d.year), mname] if opts.get("nested", True) else [f"{d.year}-{d.month:02d}"]
        return [str(d.year), mname, f"{d.day:02d}"] if opts.get("nested", True) else [d.strftime("%Y-%m-%d")]
    if mode == "size":
        s = p.stat().st_size
        for lim, label in SIZE_BUCKETS:
            if s < lim:
                return [label]
    if mode == "letter":
        c = p.name[:1].upper()
        return [c if c.isalnum() else "#"]
    if mode == "keyword":
        for line in (opts.get("rules") or "").splitlines():
            if "=" in line:
                kw, folder = line.split("=", 1)
                kws = [k.strip().lower() for k in kw.split(",") if k.strip()]
                if any(k in p.name.lower() for k in kws):
                    return [folder.strip()]
        return [opts.get("other") or "أخرى"] if opts.get("move_others") else None
    if mode == "dims":
        w, h = image_size(p)
        if not w:
            return None
        return ["أفقية" if w > h else "عمودية" if h > w else "مربعة"]
    return None


@app.post("/api/organize")
@login_required
def api_organize():
    d = J()
    folder = safe_path(d.get("folder"))
    mode = d.get("mode", "type")
    dest_base = safe_path(d["dest"]) if d.get("dest") else folder
    if d.get("paths"):
        files = [safe_path(x) for x in d["paths"]]
        files = [f for f in files if f.is_file()]
    else:
        files = list(walk_files(folder)) if d.get("recursive") else [x for x in folder.iterdir() if x.is_file() and not x.name.startswith(".")]
    copy = bool(d.get("copy"))
    plan = []
    if mode == "chunk":
        n = max(1, int(d.get("chunk") or 100))
        files = sort_paths(files, d.get("sort", "name"))
        for i, f in enumerate(files):
            plan.append((f, dest_base / f"{d.get('chunk_name') or 'مجموعة'} {i // n + 1}" / f.name))
    elif mode == "flatten":
        for f in files:
            if f.parent != folder:
                plan.append((f, folder / f.name))
    else:
        for f in files:
            parts = organize_target(f, mode, d)
            if not parts:
                continue
            parts = [re.sub(r'[\\/:*?"<>|]', "_", x) for x in parts]
            tgt = dest_base.joinpath(*parts) / f.name
            if tgt != f:
                plan.append((f, tgt))
    if not d.get("apply"):
        groups = {}
        for s, t in plan:
            key = to_rel(t.parent)
            groups.setdefault(key, []).append(s.name)
        return jsonify({"total": len(plan), "groups": [{"folder": k, "count": len(v), "sample": v[:5]} for k, v in sorted(groups.items())]})
    write_guard()

    def work(job):
        moves = []
        for i, (s, t) in enumerate(plan):
            if job["cancel"]:
                break
            t.parent.mkdir(parents=True, exist_ok=True)
            t = unique_path(t)
            if copy:
                shutil.copy2(s, t)
            else:
                shutil.move(str(s), str(t))
                moves.append((s, t))
            job["progress"] = int(100 * (i + 1) / max(1, len(plan)))
            job["message"] = s.name
        if d.get("remove_empty") and not copy:
            remove_empty_dirs(folder)
        push_undo(f"تنظيم {len(moves)} ملف", moves)
        return {"count": len(plan)}
    return jsonify({"job": start_job("تنظيم الملفات", work)})


def remove_empty_dirs(base, delete=True):
    found = []
    for dirpath, dirnames, filenames in os.walk(base, topdown=False):
        p = Path(dirpath)
        if p == base or is_internal(p.name) or TRASH_NAME in p.parts:
            continue
        try:
            if not any(p.iterdir()):
                found.append(p)
                if delete:
                    p.rmdir()
        except OSError:
            pass
    return found


@app.post("/api/empty_dirs")
@login_required
def api_empty_dirs():
    d = J()
    base = safe_path(d.get("folder"))
    if d.get("delete"):
        write_guard()
    found = remove_empty_dirs(base, bool(d.get("delete")))
    return jsonify({"items": [to_rel(x) for x in found], "deleted": bool(d.get("delete"))})


# ----------------------------------------------------------------------------
# تحليل المساحة / الأكبر حجماً
# ----------------------------------------------------------------------------
@app.post("/api/analyze")
@login_required
def api_analyze():
    d = J()
    base = safe_path(d.get("folder"))
    top_n = int(d.get("top") or 100)

    def work(job):
        import heapq
        total = count = 0
        cats, exts, children = {}, {}, {}
        heap = []
        oldest = newest = None
        years = {}
        for i, f in enumerate(walk_files(base, True)):
            if job["cancel"]:
                break
            try:
                st = f.stat()
            except OSError:
                continue
            s = st.st_size
            total += s
            count += 1
            c = category_of(f.name)
            cats.setdefault(c, [0, 0])
            cats[c][0] += s
            cats[c][1] += 1
            e = ext_of(f.name) or "—"
            exts.setdefault(e, [0, 0])
            exts[e][0] += s
            exts[e][1] += 1
            rel = f.relative_to(base)
            child = rel.parts[0] if len(rel.parts) > 1 else "(ملفات مباشرة)"
            children[child] = children.get(child, 0) + s
            y = dt.datetime.fromtimestamp(st.st_mtime).year
            years.setdefault(y, [0, 0])
            years[y][0] += s
            years[y][1] += 1
            item = (s, str(f))
            if len(heap) < top_n:
                heapq.heappush(heap, item)
            elif s > heap[0][0]:
                heapq.heapreplace(heap, item)
            if oldest is None or st.st_mtime < oldest[0]:
                oldest = (st.st_mtime, str(f))
            if newest is None or st.st_mtime > newest[0]:
                newest = (st.st_mtime, str(f))
            if i % 500 == 0:
                job["message"] = f"تم فحص {count} ملف"
                job["progress"] = min(95, job["progress"] + 1)
        job["message"] = f"تم فحص {count} ملف"
        largest = [{"path": to_rel(p), "name": Path(p).name, "size": s} for s, p in sorted(heap, reverse=True)]
        return {
            "total": total, "count": count,
            "cats": sorted([{"name": k, "size": v[0], "count": v[1]} for k, v in cats.items()], key=lambda x: -x["size"]),
            "exts": sorted([{"name": k, "size": v[0], "count": v[1]} for k, v in exts.items()], key=lambda x: -x["size"])[:30],
            "children": sorted([{"name": k, "size": v, "path": to_rel(base / k) if (base / k).is_dir() else None} for k, v in children.items()], key=lambda x: -x["size"])[:50],
            "years": sorted([{"name": str(k), "size": v[0], "count": v[1]} for k, v in years.items()], key=lambda x: x["name"]),
            "largest": largest,
            "oldest": {"path": to_rel(oldest[1]), "time": oldest[0]} if oldest else None,
            "newest": {"path": to_rel(newest[1]), "time": newest[0]} if newest else None,
        }
    return jsonify({"job": start_job(f"تحليل {base.name}", work)})


# ----------------------------------------------------------------------------
# البحث المتقدم
# ----------------------------------------------------------------------------
def parse_size(s):
    if s in (None, ""):
        return None
    m = re.match(r"^\s*([\d.]+)\s*([kmgt]?)b?\s*$", str(s).lower())
    if not m:
        return None
    mult = {"": 1, "k": 1 << 10, "m": 1 << 20, "g": 1 << 30, "t": 1 << 40}[m.group(2)]
    return float(m.group(1)) * mult


@app.post("/api/search")
@login_required
def api_search():
    d = J()
    base = safe_path(d.get("folder"))
    q = (d.get("q") or "").strip()
    regex = None
    if d.get("regex") and q:
        regex = re.compile(q, re.IGNORECASE)
    words = [w.lower() for w in q.split()] if q and not regex else []
    exts = {e.strip().lstrip(".").lower() for e in (d.get("ext") or "").split(",") if e.strip()}
    cat = d.get("cat") or ""
    mn, mx = parse_size(d.get("min_size")), parse_size(d.get("max_size"))
    after = dt.datetime.fromisoformat(d["after"]).timestamp() if d.get("after") else None
    before = (dt.datetime.fromisoformat(d["before"]) + dt.timedelta(days=1)).timestamp() if d.get("before") else None
    content = (d.get("content") or "").strip().lower()
    include_dirs = bool(d.get("dirs"))
    limit = int(d.get("limit") or 2000)
    results = []
    t0 = time.time()
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [x for x in dirnames if not is_internal(x) and (d.get("hidden") or not x.startswith("."))]
        names = [(f, False) for f in filenames] + ([(x, True) for x in dirnames] if include_dirs else [])
        for name, is_dir in names:
            low = name.lower()
            if regex and not regex.search(name):
                continue
            if words and not all(w in low for w in words):
                continue
            if exts and ext_of(name) not in exts:
                continue
            if cat and category_of(name, is_dir) != cat:
                continue
            p = Path(dirpath) / name
            try:
                st = p.stat()
            except OSError:
                continue
            if not is_dir:
                if mn is not None and st.st_size < mn:
                    continue
                if mx is not None and st.st_size > mx:
                    continue
            if after and st.st_mtime < after:
                continue
            if before and st.st_mtime > before:
                continue
            if content and not is_dir:
                if ext_of(name) not in TEXT_EXTS or st.st_size > 20 << 20:
                    continue
                try:
                    with open(p, "r", encoding="utf-8", errors="ignore") as fh:
                        if content not in fh.read().lower():
                            continue
                except OSError:
                    continue
            info = entry_info(p)
            if info:
                info["dir"] = to_rel(p.parent)
                results.append(info)
            if len(results) >= limit:
                break
        if len(results) >= limit or time.time() - t0 > 60:
            break
    return jsonify({"results": results, "truncated": len(results) >= limit})


@app.post("/api/export_list")
@login_required
def api_export_list():
    """تصدير قائمة الملفات كملف CSV (يفتح في إكسل)."""
    d = request.form if request.form else J()
    base = safe_path(d.get("folder"))
    recursive = str(d.get("recursive")) in ("1", "true", "True")
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["الاسم", "المجلد", "النوع", "الامتداد", "الحجم (بايت)", "الحجم", "تاريخ التعديل"])
    it = walk_files(base) if recursive else (x for x in base.iterdir() if x.is_file() and not x.name.startswith("."))
    for f in it:
        try:
            st = f.stat()
        except OSError:
            continue
        w.writerow([f.name, to_rel(f.parent), category_of(f.name), ext_of(f.name), st.st_size, human_size(st.st_size),
                    dt.datetime.fromtimestamp(st.st_mtime).strftime("%Y-%m-%d %H:%M")])
    data = "\ufeff" + buf.getvalue()
    return Response(data.encode("utf-8"), mimetype="text/csv",
                    headers={"Content-Disposition": f"attachment; filename*=UTF-8''{_quote(base.name + '-قائمة.csv')}"})


# ----------------------------------------------------------------------------
# الملفات المكررة
# ----------------------------------------------------------------------------
def file_hash(p, limit=None):
    h = hashlib.blake2b(digest_size=20)
    with open(p, "rb") as fh:
        if limit:
            h.update(fh.read(limit))
        else:
            while chunk := fh.read(1 << 20):
                h.update(chunk)
    return h.hexdigest()


@app.post("/api/duplicates")
@login_required
def api_duplicates():
    d = J()
    base = safe_path(d.get("folder"))
    min_size = parse_size(d.get("min_size")) or 1
    by_name = d.get("mode") == "name"

    def work(job):
        by_size = {}
        job_log(job, "جمع الملفات...")
        for f in walk_files(base):
            if job["cancel"]:
                return None
            try:
                s = f.stat().st_size
            except OSError:
                continue
            if s < min_size:
                continue
            key = (f.name.lower(), ) if by_name else s
            by_size.setdefault(key, []).append(f)
        cands = [g for g in by_size.values() if len(g) > 1]
        groups = []
        if by_name:
            groups = cands
        else:
            total = sum(len(g) for g in cands)
            done = 0
            for g in cands:
                quick = {}
                for f in g:
                    try:
                        quick.setdefault(file_hash(f, 65536), []).append(f)
                    except OSError:
                        pass
                for qg in quick.values():
                    if len(qg) < 2:
                        continue
                    full = {}
                    for f in qg:
                        try:
                            full.setdefault(file_hash(f), []).append(f)
                        except OSError:
                            pass
                        if job["cancel"]:
                            return None
                    groups += [x for x in full.values() if len(x) > 1]
                done += len(g)
                job["progress"] = int(100 * done / max(1, total))
                job["message"] = f"فحص {done} من {total}"
        out = []
        wasted = 0
        for g in groups:
            g = sorted(g, key=lambda p: (p.stat().st_mtime, len(str(p))))
            size = g[0].stat().st_size
            wasted += size * (len(g) - 1)
            out.append({"size": size, "files": [{"path": to_rel(f), "name": f.name, "dir": to_rel(f.parent),
                                                 "mtime": f.stat().st_mtime} for f in g]})
        out.sort(key=lambda x: -x["size"] * len(x["files"]))
        return {"groups": out, "wasted": wasted}
    return jsonify({"job": start_job(f"البحث عن المكررات في {base.name}", work)})


# ----------------------------------------------------------------------------
# مقارنة مجلدين
# ----------------------------------------------------------------------------
@app.post("/api/compare")
@login_required
def api_compare():
    d = J()
    a, b = safe_path(d.get("a")), safe_path(d.get("b"))
    deep = bool(d.get("deep"))

    def index(base):
        return {f.relative_to(base).as_posix(): f for f in walk_files(base)}

    def work(job):
        ia, ib = index(a), index(b)
        only_a = sorted(set(ia) - set(ib), key=natural_key)
        only_b = sorted(set(ib) - set(ia), key=natural_key)
        diff = []
        same = 0
        common = sorted(set(ia) & set(ib), key=natural_key)
        for i, k in enumerate(common):
            fa, fb = ia[k], ib[k]
            sa, sb = fa.stat(), fb.stat()
            different = sa.st_size != sb.st_size or (deep and file_hash(fa) != file_hash(fb))
            if different:
                diff.append({"name": k, "a_size": sa.st_size, "b_size": sb.st_size,
                             "newer": "a" if sa.st_mtime > sb.st_mtime else "b"})
            else:
                same += 1
            job["progress"] = int(100 * (i + 1) / max(1, len(common)))
        return {"only_a": only_a[:3000], "only_b": only_b[:3000], "diff": diff[:3000], "same": same,
                "a": to_rel(a), "b": to_rel(b)}
    return jsonify({"job": start_job("مقارنة مجلدين", work)})


@app.post("/api/sync_copy")
@login_required
def api_sync_copy():
    """ينسخ الملفات الناقصة من مجلد إلى آخر (مزامنة باتجاه واحد)."""
    write_guard()
    d = J()
    src, dst = safe_path(d.get("src")), safe_path(d.get("dst"))
    update = bool(d.get("update"))

    def work(job):
        files = list(walk_files(src))
        copied = 0
        for i, f in enumerate(files):
            if job["cancel"]:
                break
            t = dst / f.relative_to(src)
            if not t.exists() or (update and f.stat().st_mtime > t.stat().st_mtime + 1):
                t.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(f, t)
                copied += 1
                job["message"] = f.name
            job["progress"] = int(100 * (i + 1) / max(1, len(files)))
        return {"copied": copied}
    return jsonify({"job": start_job("مزامنة مجلد", work)})


# ----------------------------------------------------------------------------
# أدوات الصور
# ----------------------------------------------------------------------------
def output_path(src, opts, new_ext=None, default_suffix="_مصغر"):
    mode = opts.get("output", "folder")
    ext = new_ext or src.suffix
    if mode == "replace":
        return src.with_suffix(ext), True
    if mode == "suffix":
        return unique_path(src.with_name(src.stem + (opts.get("suffix") or default_suffix) + ext)), False
    folder = src.parent / (opts.get("folder_name") or "مصغرة")
    folder.mkdir(exist_ok=True)
    return unique_path(folder / (src.stem + ext)), False


def _font(size):
    for f in ["arial.ttf", "DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
              "C:/Windows/Fonts/arial.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf"]:
        try:
            return ImageFont.truetype(f, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def process_image(src, o):
    with Image.open(src) as im0:
        exif = im0.info.get("exif")
        icc = im0.info.get("icc_profile")
        im = ImageOps.exif_transpose(im0) if o.get("auto_rotate", True) else im0.copy()
    fmt_map = {"jpg": "JPEG", "jpeg": "JPEG", "png": "PNG", "webp": "WEBP", "bmp": "BMP", "gif": "GIF", "tiff": "TIFF", "tif": "TIFF", "ico": "ICO"}
    fmt_key = o.get("format") or "keep"
    if fmt_key == "keep":
        fmt_key = ext_of(src.name)
    fmt = fmt_map.get(fmt_key, "JPEG")
    new_ext = "." + ("jpg" if fmt == "JPEG" else fmt_key if fmt_key in fmt_map else "jpg")

    # تغيير الأبعاد
    rm = o.get("resize", "fit")
    w, h = im.size
    if rm == "fit":
        mw, mh = int(o.get("width") or 0), int(o.get("height") or 0)
        if mw or mh:
            im.thumbnail((mw or 100000, mh or 100000), Image.LANCZOS)
    elif rm == "percent":
        pct = float(o.get("percent") or 50) / 100
        im = im.resize((max(1, int(w * pct)), max(1, int(h * pct))), Image.LANCZOS)
    elif rm == "exact":
        mw, mh = int(o.get("width") or w), int(o.get("height") or h)
        im = im.resize((mw, mh), Image.LANCZOS)
    elif rm == "crop":
        mw, mh = int(o.get("width") or w), int(o.get("height") or h)
        im = ImageOps.fit(im, (mw, mh), Image.LANCZOS)
    elif rm == "long":
        side = int(o.get("width") or 1920)
        if max(w, h) > side:
            im.thumbnail((side, side), Image.LANCZOS)

    rot = int(o.get("rotate") or 0)
    if rot:
        im = im.rotate(-rot, expand=True)
    if o.get("flip_h"):
        im = ImageOps.mirror(im)
    if o.get("flip_v"):
        im = ImageOps.flip(im)
    if o.get("grayscale"):
        im = ImageOps.grayscale(im)
    if o.get("autocontrast"):
        im = ImageOps.autocontrast(im.convert("RGB"))
    wm = (o.get("watermark") or "").strip()
    if wm:
        im = im.convert("RGBA")
        layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        fsize = max(12, int(min(im.size) * float(o.get("wm_size") or 5) / 100))
        font = _font(fsize)
        try:
            bbox = draw.textbbox((0, 0), wm, font=font)
            tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
        except AttributeError:
            tw, th = fsize * len(wm) // 2, fsize
        pad = fsize // 2
        pos = o.get("wm_pos", "br")
        x = {"bl": pad, "tl": pad, "br": im.width - tw - pad, "tr": im.width - tw - pad, "c": (im.width - tw) // 2}[pos]
        y = {"bl": im.height - th - pad * 2, "br": im.height - th - pad * 2, "tl": pad, "tr": pad, "c": (im.height - th) // 2}[pos]
        alpha = int(255 * float(o.get("wm_opacity") or 60) / 100)
        draw.text((x + 2, y + 2), wm, font=font, fill=(0, 0, 0, alpha // 2))
        draw.text((x, y), wm, font=font, fill=(255, 255, 255, alpha))
        im = Image.alpha_composite(im, layer)

    if fmt in ("JPEG", "BMP") and im.mode not in ("RGB", "L"):
        bg = Image.new("RGB", im.size, (255, 255, 255))
        rgba = im.convert("RGBA")
        bg.paste(rgba, mask=rgba.split()[-1])
        im = bg
    quality = int(o.get("quality") or 85)
    save_kw = {}
    if fmt in ("JPEG", "WEBP"):
        save_kw["quality"] = quality
        if fmt == "JPEG":
            save_kw["optimize"] = True
            save_kw["progressive"] = True
    if fmt == "PNG":
        save_kw["optimize"] = True
    if o.get("keep_exif") and exif and fmt in ("JPEG", "WEBP", "TIFF") and not o.get("auto_rotate", True):
        save_kw["exif"] = exif
    elif o.get("keep_exif") and exif and fmt in ("JPEG", "WEBP", "TIFF"):
        try:
            ex = Image.Exif()
            ex.load(exif)
            ex[0x0112] = 1  # الاتجاه تم تطبيقه
            save_kw["exif"] = ex.tobytes()
        except Exception:
            pass
    if icc and fmt in ("JPEG", "PNG", "WEBP", "TIFF"):
        save_kw["icc_profile"] = icc

    target_kb = float(o.get("target_kb") or 0)
    buf = io.BytesIO()
    if target_kb and fmt in ("JPEG", "WEBP"):
        lo, hi, best = 10, 95, None
        while lo <= hi:
            mid = (lo + hi) // 2
            b = io.BytesIO()
            im.save(b, fmt, **(save_kw | {"quality": mid}))
            if b.tell() <= target_kb * 1024:
                best, lo = b, mid + 1
            else:
                hi = mid - 1
        if best is None:
            best = io.BytesIO()
            im.save(best, fmt, **(save_kw | {"quality": 10}))
        buf = best
    else:
        im.save(buf, fmt, **save_kw)
    return buf.getvalue(), new_ext


@app.post("/api/images")
@login_required
def api_images():
    write_guard()
    if not HAS_PIL:
        raise PathError("مكتبة Pillow غير مثبتة: pip install pillow")
    d = J()
    paths = [p for p in collect_targets(d) if p.is_file() and ext_of(p.name) in IMAGE_EXTS]
    if not paths:
        raise PathError("لا توجد صور مدعومة في التحديد")
    opts = d.get("options", {})

    def work(job):
        before = after = 0
        results = []
        for i, p in enumerate(paths):
            if job["cancel"]:
                break
            try:
                size0 = p.stat().st_size
                data, new_ext = process_image(p, opts)
                if opts.get("skip_bigger") and len(data) >= size0 and new_ext.lower() == p.suffix.lower():
                    job_log(job, f"تخطي (لم يصغر): {p.name}")
                    results.append({"name": p.name, "before": size0, "after": size0, "skipped": True})
                    before += size0
                    after += size0
                    continue
                out, replace = output_path(p, opts, new_ext)
                out.write_bytes(data)
                if replace and out != p:
                    to_trash(p)
                before += size0
                after += len(data)
                results.append({"name": p.name, "out": to_rel(out), "before": size0, "after": len(data)})
                job_log(job, f"{p.name}: {human_size(size0)} ← {human_size(len(data))}")
            except Exception as e:  # noqa
                job_log(job, f"خطأ في {p.name}: {e}")
                results.append({"name": p.name, "error": str(e)})
            job["progress"] = int(100 * (i + 1) / len(paths))
        return {"before": before, "after": after, "items": results}
    return jsonify({"job": start_job(f"معالجة {len(paths)} صورة", work)})


@app.post("/api/images_to_pdf")
@login_required
def api_images_to_pdf():
    write_guard()
    d = J()
    paths = sort_paths([p for p in collect_targets(d) if ext_of(p.name) in IMAGE_EXTS], d.get("sort", "name"))
    if not paths:
        raise PathError("لا توجد صور")
    name = (d.get("name") or "صور").strip()
    if not name.lower().endswith(".pdf"):
        name += ".pdf"
    target = unique_path(paths[0].parent / name)

    def work(job):
        imgs = []
        for i, p in enumerate(paths):
            with Image.open(p) as im:
                im = ImageOps.exif_transpose(im).convert("RGB")
                mx = int(d.get("max_side") or 0)
                if mx:
                    im.thumbnail((mx, mx))
                imgs.append(im)
            job["progress"] = int(80 * (i + 1) / len(paths))
        imgs[0].save(target, "PDF", save_all=True, append_images=imgs[1:], resolution=150)
        return {"file": to_rel(target)}
    return jsonify({"job": start_job("تحويل الصور إلى PDF", work)})


@app.post("/api/contact_sheet")
@login_required
def api_contact_sheet():
    """لوحة صور مصغرة مجمعة (كولاج) لمجموعة صور."""
    write_guard()
    d = J()
    paths = sort_paths([p for p in collect_targets(d) if ext_of(p.name) in IMAGE_EXTS], "name")[:400]
    if not paths:
        raise PathError("لا توجد صور")
    cols = int(d.get("cols") or 5)
    cell = int(d.get("cell") or 300)

    def work(job):
        rows = (len(paths) + cols - 1) // cols
        label_h = 28 if d.get("labels", True) else 0
        sheet = Image.new("RGB", (cols * cell, rows * (cell + label_h)), (255, 255, 255))
        draw = ImageDraw.Draw(sheet)
        font = _font(14)
        for i, p in enumerate(paths):
            try:
                with Image.open(p) as im:
                    im = ImageOps.exif_transpose(im).convert("RGB")
                    im.thumbnail((cell - 10, cell - 10))
                    x = (i % cols) * cell + (cell - im.width) // 2
                    y = (i // cols) * (cell + label_h) + (cell - im.height) // 2
                    sheet.paste(im, (x, y))
                    if label_h:
                        draw.text(((i % cols) * cell + 6, (i // cols) * (cell + label_h) + cell), p.name[:40], fill=(40, 40, 40), font=font)
            except Exception:
                pass
            job["progress"] = int(100 * (i + 1) / len(paths))
        out = unique_path(paths[0].parent / "لوحة-الصور.jpg")
        sheet.save(out, "JPEG", quality=85)
        return {"file": to_rel(out)}
    return jsonify({"job": start_job("إنشاء لوحة صور", work)})


# ----------------------------------------------------------------------------
# أدوات الفيديو (ffmpeg)
# ----------------------------------------------------------------------------
def probe(p):
    if not FFPROBE:
        return None
    try:
        r = subprocess.run([FFPROBE, "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", str(p)],
                           capture_output=True, timeout=30)
        data = json.loads(r.stdout or b"{}")
    except Exception:
        return None
    fmt = data.get("format", {})
    out = {"duration": float(fmt.get("duration") or 0), "bitrate": int(fmt.get("bit_rate") or 0),
           "tags": fmt.get("tags", {})}
    for s in data.get("streams", []):
        if s.get("codec_type") == "video" and "vcodec" not in out:
            out.update(vcodec=s.get("codec_name"), width=s.get("width"), height=s.get("height"))
            try:
                n, dd = s.get("avg_frame_rate", "0/1").split("/")
                out["fps"] = round(float(n) / float(dd), 2) if float(dd) else None
            except (ValueError, ZeroDivisionError):
                pass
        if s.get("codec_type") == "audio" and "acodec" not in out:
            out["acodec"] = s.get("codec_name")
    return out


def run_ffmpeg(job, cmd, duration, base=0, span=100):
    proc = subprocess.Popen(cmd + ["-progress", "pipe:1", "-nostats", "-loglevel", "error"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            encoding="utf-8", errors="replace")
    err_lines = []

    def read_err():
        for line in proc.stderr:
            err_lines.append(line)
    t = threading.Thread(target=read_err, daemon=True)
    t.start()
    for line in proc.stdout:
        if job["cancel"]:
            proc.kill()
            break
        if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
            try:
                us = int(line.split("=")[1])
                if duration:
                    job["progress"] = int(base + span * min(1, us / 1e6 / duration))
            except ValueError:
                pass
    proc.wait()
    t.join(timeout=2)
    if proc.returncode != 0 and not job["cancel"]:
        raise RuntimeError("".join(err_lines[-5:]) or "فشل ffmpeg")


def build_video_cmd(src, out, o, info):
    mode = o.get("mode", "compress")
    cmd = [FFMPEG, "-y"]
    if o.get("start"):
        cmd += ["-ss", str(o["start"])]
    if o.get("end"):
        cmd += ["-to", str(o["end"])]
    cmd += ["-i", str(src)]
    if mode == "audio":
        fmt = o.get("audio_format", "mp3")
        if fmt == "mp3":
            cmd += ["-vn", "-c:a", "libmp3lame", "-b:a", o.get("audio_bitrate") or "192k"]
        elif fmt == "m4a":
            cmd += ["-vn", "-c:a", "aac", "-b:a", o.get("audio_bitrate") or "192k"]
        else:
            cmd += ["-vn", "-c:a", "pcm_s16le"]
        return cmd + [str(out)]
    if mode == "gif":
        w = int(o.get("gif_width") or 480)
        fps = int(o.get("fps") or 10)
        return cmd + ["-vf", f"fps={fps},scale={w}:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse", "-loop", "0", str(out)]
    if mode == "frames":
        every = float(o.get("every") or 10)
        return cmd + ["-vf", f"fps=1/{every}", "-q:v", "2", str(out)]
    if mode == "mute":
        return cmd + ["-c:v", "copy", "-an", str(out)]
    if mode == "cut":
        return cmd + ["-c", "copy", "-avoid_negative_ts", "make_zero", str(out)]
    # compress / convert
    vf = []
    height = int(o.get("height") or 0)
    if height:
        vf.append(f"scale=-2:'min(ih,{height})'")
    if o.get("fps"):
        vf.append(f"fps={int(o['fps'])}")
    rot = o.get("rotate")
    if rot == "90":
        vf.append("transpose=1")
    elif rot == "-90":
        vf.append("transpose=2")
    elif rot == "180":
        vf.append("transpose=1,transpose=1")
    if o.get("speed") and float(o["speed"]) != 1:
        vf.append(f"setpts=PTS/{float(o['speed'])}")
    codec = o.get("codec", "h264")
    vcodec = "libx265" if codec == "h265" else "libx264"
    cmd += ["-c:v", vcodec, "-crf", str(o.get("crf") or (28 if codec == "h265" else 26)),
            "-preset", o.get("preset") or "medium", "-pix_fmt", "yuv420p"]
    if codec == "h265":
        cmd += ["-tag:v", "hvc1"]
    if vf:
        cmd += ["-vf", ",".join(vf)]
    if o.get("remove_audio"):
        cmd += ["-an"]
    else:
        cmd += ["-c:a", "aac", "-b:a", o.get("audio_bitrate") or "128k"]
        if o.get("speed") and float(o["speed"]) != 1:
            sp = float(o["speed"])
            if 0.5 <= sp <= 2:
                cmd += ["-af", f"atempo={sp}"]
            else:
                cmd += ["-an"]
    cmd += ["-movflags", "+faststart", "-map_metadata", "0"]
    return cmd + [str(out)]


@app.post("/api/videos")
@login_required
def api_videos():
    write_guard()
    if not FFMPEG:
        raise PathError("برنامج ffmpeg غير مثبت. ثبّته وأضفه إلى PATH (راجع ملف اقرأني)")
    d = J()
    paths = [p for p in collect_targets(d) if p.is_file() and ext_of(p.name) in VIDEO_EXTS | (AUDIO_EXTS if d.get("options", {}).get("mode") == "audio" else set())]
    if not paths:
        raise PathError("لا توجد فيديوهات في التحديد")
    o = d.get("options", {})
    mode = o.get("mode", "compress")
    ext_map = {"audio": "." + o.get("audio_format", "mp3"), "gif": ".gif", "frames": ".jpg"}

    def work(job):
        before = after = 0
        results = []
        for i, p in enumerate(paths):
            if job["cancel"]:
                break
            info = probe(p) or {}
            dur = info.get("duration") or 0
            if o.get("start") or o.get("end"):
                try:
                    st = float(o.get("start") or 0)
                    en = float(o.get("end") or dur)
                    dur = max(0.1, en - st)
                except ValueError:
                    pass
            new_ext = ext_map.get(mode) or (".mp4" if mode in ("compress", "convert") else p.suffix)
            if mode == "frames":
                folder = p.parent / f"{p.stem}_لقطات"
                folder.mkdir(exist_ok=True)
                out, replace = folder / "لقطة_%04d.jpg", False
            else:
                default_suffix = {"compress": "_مضغوط", "audio": "", "gif": "", "mute": "_بدون_صوت", "cut": "_مقطع", "convert": ""}.get(mode, "_جديد")
                oo = dict(o)
                if mode in ("audio", "gif", "convert") and oo.get("output") == "folder" and not oo.get("folder_name"):
                    oo["folder_name"] = "محولة"
                if mode == "compress" and not oo.get("folder_name"):
                    oo["folder_name"] = "مضغوطة"
                out, replace = output_path(p, oo, new_ext, default_suffix)
                if replace:
                    out = p.with_name(f".alma_tmp_{uuid.uuid4().hex[:6]}{new_ext}")
            base = 100 * i / len(paths)
            job["message"] = p.name
            try:
                run_ffmpeg(job, build_video_cmd(p, out, o, info), dur, base, 100 / len(paths))
                if job["cancel"]:
                    if mode != "frames" and out.exists():
                        out.unlink()
                    break
                s0 = p.stat().st_size
                if mode == "frames":
                    results.append({"name": p.name, "out": to_rel(out.parent)})
                    job_log(job, f"{p.name}: تم استخراج اللقطات")
                    continue
                s1 = out.stat().st_size
                if replace:
                    final = p.with_suffix(new_ext)
                    to_trash(p)
                    os.rename(out, unique_path(final))
                    out = final
                before += s0
                after += s1
                results.append({"name": p.name, "out": to_rel(out), "before": s0, "after": s1})
                job_log(job, f"{p.name}: {human_size(s0)} ← {human_size(s1)}" + (" ⚠ الناتج أكبر من الأصل (جرّب CRF أعلى أو دقة أقل)" if mode == "compress" and s1 > s0 else ""))
            except Exception as e:  # noqa
                job_log(job, f"خطأ في {p.name}: {e}")
                results.append({"name": p.name, "error": str(e)})
                if mode != "frames" and Path(out).exists() and Path(out).name.startswith(".alma_tmp"):
                    Path(out).unlink()
        return {"before": before, "after": after, "items": results}
    titles = {"compress": "ضغط", "audio": "استخراج الصوت من", "gif": "تحويل إلى GIF", "frames": "استخراج لقطات من",
              "mute": "إزالة الصوت من", "cut": "قص", "convert": "تحويل"}
    return jsonify({"job": start_job(f"{titles.get(mode, 'معالجة')} {len(paths)} فيديو", work)})


@app.post("/api/video_merge")
@login_required
def api_video_merge():
    """دمج عدة فيديوهات في فيديو واحد (بنفس الترتيب)."""
    write_guard()
    if not FFMPEG:
        raise PathError("ffmpeg غير مثبت")
    d = J()
    paths = sort_paths([p for p in collect_targets(d) if ext_of(p.name) in VIDEO_EXTS], d.get("sort", "name"))
    if len(paths) < 2:
        raise PathError("اختر فيديوهين أو أكثر")
    out = unique_path(paths[0].parent / ((d.get("name") or "مدمج").removesuffix(".mp4") + ".mp4"))

    def work(job):
        CACHE_DIR.mkdir(exist_ok=True)
        lst = CACHE_DIR / f"concat_{uuid.uuid4().hex}.txt"
        lst.write_text("\n".join("file '" + str(p).replace("'", "'\\''") + "'" for p in paths), encoding="utf-8")
        total = sum((probe(p) or {}).get("duration") or 0 for p in paths)
        try:
            if d.get("reencode"):
                cmd = [FFMPEG, "-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-c:v", "libx264", "-crf", "23", "-c:a", "aac", str(out)]
            else:
                cmd = [FFMPEG, "-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-c", "copy", str(out)]
            run_ffmpeg(job, cmd, total)
        finally:
            lst.unlink(missing_ok=True)
        return {"file": to_rel(out)}
    return jsonify({"job": start_job("دمج الفيديوهات", work)})


# ----------------------------------------------------------------------------
# البيانات الوصفية (Metadata)
# ----------------------------------------------------------------------------
EXIF_WRITE = {"artist": 315, "copyright": 33432, "description": 270, "software": 305, "make": 271, "model": 272}
# وسوم ويندوز (تدعم العربية وتظهر في خصائص الملف)
XP_TAGS = {"title": 0x9C9B, "comment": 0x9C9C, "artist": 0x9C9D, "keywords": 0x9C9E, "subject": 0x9C9F}
AUDIO_FIELDS = ["title", "artist", "album", "albumartist", "date", "genre", "tracknumber", "comment", "composer"]
PDF_FIELDS = {"title": "/Title", "author": "/Author", "subject": "/Subject", "keywords": "/Keywords", "creator": "/Creator"}
VIDEO_FIELDS = ["title", "artist", "album", "date", "comment", "description", "genre", "copyright"]


def gps_to_deg(v, ref):
    try:
        d, m, s = [float(x) for x in v]
        r = d + m / 60 + s / 3600
        return -r if ref in ("S", "W") else r
    except Exception:
        return None


def exif_text(v):
    """يفك نص EXIF (قد يكون UTF-8 مخزناً كـ ASCII)."""
    if v is None:
        return ""
    if isinstance(v, bytes):
        b = v
    else:
        try:
            b = str(v).encode("latin-1")
        except UnicodeEncodeError:
            return str(v).strip("\0")
    try:
        return b.decode("utf-8").strip("\0")
    except UnicodeDecodeError:
        return b.decode("latin-1").strip("\0")


def exif_value(text):
    try:
        text.encode("ascii")
        return text
    except UnicodeEncodeError:
        return text.encode("utf-8")


def decode_utf16_guess(b):
    best, score = "", -1
    for enc in ("utf-16-be", "utf-16-le"):
        try:
            t = b.decode(enc)
        except UnicodeDecodeError:
            continue
        sc = sum(1 for c in t if ord(c) < 0x0800 or 0x4E00 <= ord(c) <= 0x9FFF)
        if sc > score:
            best, score = t, sc
    return best.strip("\0")


def user_comment(v):
    if not v:
        return ""
    if isinstance(v, str):
        v = v.encode("latin-1", "ignore")
    head, body = v[:8], v[8:]
    if head == b"UNICODE\0":
        return decode_utf16_guess(body)
    if head in (b"ASCII\0\0\0", b"\0" * 8):
        return exif_text(body)
    return exif_text(v)


def read_image_meta(p, im):
    out = {}
    ex = im.getexif()
    exif_ifd = ex.get_ifd(0x8769)
    raw = {}
    for k, v in list(ex.items()) + list(exif_ifd.items()):
        name = ExifTags.TAGS.get(k, str(k))
        if k in (0x8769, 0x8825, 0x927C, 37500):
            continue
        if k in XP_TAGS.values() and isinstance(v, (bytes, tuple)):
            v = bytes(v).decode("utf-16-le", "ignore").strip("\0")
        elif k == 37510:
            v = user_comment(v)
        elif isinstance(v, (str, bytes)):
            v = exif_text(v)
        raw[name] = str(v)[:200]
    gps = ex.get_ifd(0x8825)
    if gps:
        lat = gps_to_deg(gps.get(2), gps.get(1))
        lon = gps_to_deg(gps.get(4), gps.get(3))
        if lat is not None and lon is not None:
            out["gps"] = {"lat": lat, "lon": lon}
    fields = {k: exif_text(ex.get(tag)) for k, tag in EXIF_WRITE.items()}
    for k, tag in XP_TAGS.items():
        v = ex.get(tag)
        if v:
            txt = bytes(v).decode("utf-16-le", "ignore").strip("\0")
            if txt and not fields.get(k):
                fields[k] = txt
    fields.setdefault("title", "")
    fields.setdefault("keywords", "")
    fields.setdefault("subject", "")
    uc = user_comment(exif_ifd.get(37510))
    fields["comment"] = uc or fields.get("comment", "")
    fields["datetime"] = exif_text(exif_ifd.get(36867) or ex.get(306))
    raw["الأبعاد"] = f"{im.width}×{im.height}"
    out["raw"] = raw
    out["fields"] = fields
    return out


def jpeg_set_exif(path, exif_bytes, strip_all=False):
    """يستبدل كتلة EXIF في ملف JPEG بدون إعادة ضغط الصورة (بدون فقد جودة)."""
    data = Path(path).read_bytes()
    if data[:2] != b"\xff\xd8":
        raise ValueError("ليس JPEG")
    pos = 2
    segs = []
    while pos < len(data) - 4:
        if data[pos] != 0xFF:
            raise ValueError("بنية JPEG غير متوقعة")
        marker = data[pos + 1]
        if marker == 0xFF:
            pos += 1
            continue
        if marker == 0xDA or marker == 0xD9:  # بداية بيانات الصورة
            break
        length = int.from_bytes(data[pos + 2:pos + 4], "big")
        seg = data[pos:pos + 2 + length]
        segs.append((marker, seg))
        pos += 2 + length
    rest = data[pos:]
    keep = []
    for marker, seg in segs:
        payload = seg[4:]
        if marker == 0xE1 and (payload.startswith(b"Exif\0\0") or strip_all):
            continue
        if strip_all and marker in (0xED, 0xFE, 0xE2) and not payload.startswith(b"ICC_PROFILE"):
            continue
        keep.append((marker, seg))
    new_segs = []
    if exif_bytes:
        if not exif_bytes.startswith(b"Exif\0\0"):
            exif_bytes = b"Exif\0\0" + exif_bytes
        if len(exif_bytes) > 65533:
            raise ValueError("بيانات EXIF كبيرة جداً")
        new_segs.append(b"\xff\xe1" + (len(exif_bytes) + 2).to_bytes(2, "big") + exif_bytes)
    # ضع EXIF بعد JFIF (APP0) إن وجد
    out = bytearray(b"\xff\xd8")
    inserted = False
    for marker, seg in keep:
        if not inserted and marker != 0xE0:
            out += b"".join(new_segs)
            inserted = True
        out += seg
    if not inserted:
        out += b"".join(new_segs)
    out += rest
    tmp = Path(path).with_name(f".alma_tmp_{uuid.uuid4().hex[:6]}{Path(path).suffix}")
    tmp.write_bytes(bytes(out))
    os.replace(tmp, path)


def read_meta(p):
    e = ext_of(p.name)
    out = {"kind": "other", "fields": {}, "raw": {}}
    if HAS_PIL and e in IMAGE_EXTS:
        out["kind"] = "image"
        with Image.open(p) as im:
            out.update(read_image_meta(p, im))
    elif HAS_MUTAGEN and e in AUDIO_EXTS:
        out["kind"] = "audio"
        f = mutagen.File(p, easy=True)
        if f is not None:
            out["fields"] = {k: ", ".join(f.get(k, [])) if f.tags is not None else "" for k in AUDIO_FIELDS}
            if f.info:
                out["raw"] = {"المدة": f"{getattr(f.info, 'length', 0):.1f} ث",
                              "معدل البت": f"{getattr(f.info, 'bitrate', 0) // 1000} kbps"}
    elif HAS_PYPDF and e == "pdf":
        out["kind"] = "pdf"
        r = pypdf.PdfReader(str(p))
        md = r.metadata or {}
        out["fields"] = {k: str(md.get(v, "") or "") for k, v in PDF_FIELDS.items()}
        out["raw"] = {"عدد الصفحات": len(r.pages), "مشفر": "نعم" if r.is_encrypted else "لا"}
        out["raw"].update({k: str(v)[:200] for k, v in md.items()})
    elif e in VIDEO_EXTS:
        out["kind"] = "video"
        info = probe(p) or {}
        tags = {k.lower(): v for k, v in (info.get("tags") or {}).items()}
        out["fields"] = {k: tags.get(k, "") for k in VIDEO_FIELDS}
        out["raw"] = {k: v for k, v in info.items() if k != "tags"}
        out["raw"].update(tags)
    return out


def write_meta(p, fields, strip=False):
    e = ext_of(p.name)
    fields = {k: v for k, v in fields.items() if v is not None}
    if HAS_PIL and e in IMAGE_EXTS:
        with Image.open(p) as im:
            im.load()
            fmt = im.format
            ex = Image.Exif() if strip else im.getexif()
            icc = im.info.get("icc_profile")
            if strip:
                # احتفظ فقط باتجاه الصورة حتى لا تنقلب
                orient = im.getexif().get(0x0112)
                if orient:
                    ex[0x0112] = orient
            else:
                for k, tag in EXIF_WRITE.items():
                    if k in fields:
                        if fields[k]:
                            ex[tag] = exif_value(fields[k])
                        else:
                            ex.pop(tag, None)
                for k, tag in XP_TAGS.items():
                    if k in fields:
                        if fields[k]:
                            ex[tag] = fields[k].encode("utf-16-le") + b"\0\0"
                        else:
                            ex.pop(tag, None)
                exif_ifd = ex.get_ifd(0x8769)
                if fields.get("datetime"):
                    exif_ifd[36867] = fields["datetime"]
                    exif_ifd[36868] = fields["datetime"]
                    ex[306] = fields["datetime"]
                if "comment" in fields:
                    if fields["comment"]:
                        exif_ifd[37510] = b"UNICODE\0" + fields["comment"].encode("utf-16-be")
                    else:
                        exif_ifd.pop(37510, None)
            exif_bytes = ex.tobytes() if len(ex) or ex.get_ifd(0x8769) else b""
            if fmt == "JPEG":
                pass  # يُكتب بدون إعادة ضغط أدناه
            else:
                kw = {}
                if fmt in ("WEBP", "TIFF", "PNG") and exif_bytes:
                    kw["exif"] = exif_bytes
                if icc:
                    kw["icc_profile"] = icc
                if fmt == "WEBP":
                    kw["lossless"] = bool(im.info.get("lossless"))
                    kw["quality"] = 95
                tmp = p.with_name(f".alma_tmp_{uuid.uuid4().hex[:6]}{p.suffix}")
                im.save(tmp, fmt, **kw)
        if fmt == "JPEG":
            jpeg_set_exif(p, exif_bytes, strip_all=strip)
        else:
            os.replace(tmp, p)
        return True
    if HAS_MUTAGEN and e in AUDIO_EXTS:
        f = mutagen.File(p, easy=True)
        if f is None:
            raise PathError("صيغة صوت غير مدعومة")
        if strip:
            f.delete()
            return True
        if f.tags is None:
            f.add_tags()
        for k in AUDIO_FIELDS:
            if k in fields:
                try:
                    if fields[k]:
                        f[k] = fields[k]
                    elif k in f:
                        del f[k]
                except (KeyError, ValueError, TypeError):
                    pass
        f.save()
        return True
    if HAS_PYPDF and e == "pdf":
        r = pypdf.PdfReader(str(p))
        w = pypdf.PdfWriter(clone_from=r)
        if strip:
            w.metadata = None
            w.add_metadata({"/Producer": ""})
        else:
            md = {k: str(v) for k, v in (r.metadata or {}).items()}
            for k, key in PDF_FIELDS.items():
                if k in fields:
                    md[key] = fields[k]
            w.add_metadata(md)
        tmp = p.with_name(f".alma_tmp_{uuid.uuid4().hex[:6]}.pdf")
        with open(tmp, "wb") as fh:
            w.write(fh)
        os.replace(tmp, p)
        return True
    if e in VIDEO_EXTS and FFMPEG:
        tmp = p.with_name(f".alma_tmp_{uuid.uuid4().hex[:6]}{p.suffix}")
        cmd = [FFMPEG, "-y", "-i", str(p), "-map", "0", "-c", "copy"]
        if strip:
            cmd += ["-map_metadata", "-1"]
        else:
            for k in VIDEO_FIELDS:
                if k in fields:
                    cmd += ["-metadata", f"{k}={fields[k]}"]
        cmd += [str(tmp)]
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if r.returncode != 0:
            tmp.unlink(missing_ok=True)
            raise RuntimeError(r.stderr[-300:])
        os.replace(tmp, p)
        return True
    raise PathError(f"لا يمكن تعديل بيانات {p.name}")


@app.get("/api/meta")
@login_required
def api_meta_get():
    p = safe_path(request.args.get("path"))
    return jsonify(read_meta(p))


@app.post("/api/meta")
@login_required
def api_meta_set():
    write_guard()
    d = J()
    paths = [p for p in collect_targets(d) if p.is_file()]
    fields = d.get("fields", {})
    strip = bool(d.get("strip"))
    keep_mtime = d.get("keep_mtime", True)

    def work(job):
        ok, errors = 0, []
        for i, p in enumerate(paths):
            if job["cancel"]:
                break
            st = p.stat()
            try:
                # دعم المتغيرات: {name} {n} {parent}
                ff = {k: (v.replace("{name}", p.stem).replace("{n}", str(i + 1)).replace("{parent}", p.parent.name)
                          if isinstance(v, str) else v) for k, v in fields.items()}
                write_meta(p, ff, strip)
                if keep_mtime:
                    os.utime(p, (st.st_atime, st.st_mtime))
                ok += 1
                job_log(job, f"✓ {p.name}")
            except Exception as e:  # noqa
                errors.append(f"{p.name}: {e}")
                job_log(job, f"✗ {p.name}: {e}")
            job["progress"] = int(100 * (i + 1) / max(1, len(paths)))
        return {"ok": ok, "errors": errors}
    return jsonify({"job": start_job(("حذف" if strip else "كتابة") + f" البيانات الوصفية لـ {len(paths)} ملف", work)})


@app.post("/api/set_dates")
@login_required
def api_set_dates():
    """تعديل تاريخ الملف (تاريخ التعديل) يدوياً أو من بيانات EXIF أو من الاسم."""
    write_guard()
    d = J()
    paths = [p for p in collect_targets(d) if p.is_file()]
    mode = d.get("mode", "exif")
    done, failed = 0, []
    for p in paths:
        when = None
        if mode == "exif":
            when = exif_datetime(p)
        elif mode == "name":
            m = re.search(r"(20\d{2}|19\d{2})[-_.]?(\d{2})[-_.]?(\d{2})(?:[-_. T]?(\d{2})[-_.:]?(\d{2})[-_.:]?(\d{2}))?", p.name)
            if m:
                try:
                    g = [int(x) if x else 0 for x in m.groups()]
                    when = dt.datetime(*g)
                except ValueError:
                    when = None
        elif mode == "manual" and d.get("value"):
            when = dt.datetime.fromisoformat(d["value"])
        if when:
            ts = when.timestamp()
            os.utime(p, (ts, ts))
            done += 1
        else:
            failed.append(p.name)
    return jsonify({"ok": True, "count": done, "failed": failed[:50]})


# ----------------------------------------------------------------------------
# أدوات النصوص
# ----------------------------------------------------------------------------
def read_text_any(p):
    raw = p.read_bytes()
    for enc in ("utf-8-sig", "cp1256"):
        try:
            return raw.decode(enc), ("utf-8" if enc == "utf-8-sig" else enc), raw.startswith(b"\xef\xbb\xbf")
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1"), "latin-1", False


@app.post("/api/text_tools")
@login_required
def api_text_tools():
    d = J()
    paths = [p for p in collect_targets(d) if p.is_file() and (ext_of(p.name) in TEXT_EXTS or d.get("force"))]
    if not paths:
        raise PathError("لا توجد ملفات نصية في التحديد")
    op = d.get("op")
    preview = not d.get("apply")
    if not preview:
        write_guard()
    results = []
    if op == "merge":
        sep = d.get("sep", "\n\n")
        parts = []
        for p in sort_paths(paths, d.get("sort", "name")):
            txt, _, _ = read_text_any(p)
            header = d.get("header", "").replace("{name}", p.name)
            parts.append((header + "\n" if header else "") + txt)
        out_name = d.get("name") or "مدمج.txt"
        if preview:
            return jsonify({"preview": sep.join(parts)[:5000], "count": len(paths)})
        out = unique_path(paths[0].parent / out_name)
        out.write_text(sep.join(parts), encoding="utf-8")
        return jsonify({"ok": True, "file": to_rel(out)})
    for i, p in enumerate(paths):
        txt, enc, bom = read_text_any(p)
        new = txt
        if op == "replace":
            find = d.get("find", "")
            if find:
                if d.get("regex"):
                    new = re.sub(find, d.get("repl", ""), txt, flags=0 if d.get("case") else re.IGNORECASE)
                elif d.get("case"):
                    new = txt.replace(find, d.get("repl", ""))
                else:
                    new = re.sub(re.escape(find), lambda m: d.get("repl", ""), txt, flags=re.IGNORECASE)
        elif op == "header":
            line = d.get("text", "").replace("{name}", p.stem).replace("{n}", str(i + 1)).replace("{date}", dt.date.today().isoformat())
            new = line + "\n" + txt
        elif op == "footer":
            line = d.get("text", "").replace("{name}", p.stem).replace("{n}", str(i + 1)).replace("{date}", dt.date.today().isoformat())
            new = txt + ("" if txt.endswith("\n") or not txt else "\n") + line + "\n"
        elif op == "eol":
            new = txt.replace("\r\n", "\n")
            if d.get("eol") == "crlf":
                new = new.replace("\n", "\r\n")
        elif op == "trim":
            new = "\n".join(l.rstrip() for l in txt.splitlines())
            if d.get("remove_empty"):
                new = "\n".join(l for l in new.splitlines() if l.strip())
            if d.get("dedupe"):
                seen = set()
                lines = []
                for l in new.splitlines():
                    if l not in seen:
                        seen.add(l)
                        lines.append(l)
                new = "\n".join(lines)
            if d.get("sort_lines"):
                new = "\n".join(sorted(new.splitlines(), key=natural_key))
            new += "\n" if txt.endswith("\n") else ""
        elif op == "encoding":
            enc = d.get("to", "utf-8")
        changed = new != txt or op == "encoding"
        results.append({"name": p.name, "changed": changed,
                        "count": (len(re.findall(re.escape(d.get("find", "")), txt, flags=re.IGNORECASE)) if op == "replace" and d.get("find") and not d.get("regex") else None)})
        if not preview and changed:
            p.write_text(new, encoding=enc, newline="") if op != "encoding" else p.write_bytes(new.encode(enc, errors="replace"))
    return jsonify({"items": results, "changed": sum(1 for r in results if r["changed"]), "applied": not preview})


# ----------------------------------------------------------------------------
# أدوات PDF
# ----------------------------------------------------------------------------
@app.post("/api/pdf")
@login_required
def api_pdf():
    write_guard()
    if not HAS_PYPDF:
        raise PathError("مكتبة pypdf غير مثبتة: pip install pypdf")
    d = J()
    op = d.get("op")
    paths = sort_paths([p for p in collect_targets(d) if ext_of(p.name) == "pdf"], d.get("sort", "name"))
    if not paths:
        raise PathError("لا توجد ملفات PDF في التحديد")

    def parse_ranges(s, n):
        pages = []
        for part in (s or "").replace("،", ",").split(","):
            part = part.strip()
            if not part:
                continue
            if "-" in part:
                a, b = part.split("-", 1)
                a = int(a or 1)
                b = int(b or n)
                pages += list(range(a - 1, min(b, n)))
            else:
                if 0 < int(part) <= n:
                    pages.append(int(part) - 1)
        return pages

    def work(job):
        if op == "merge":
            w = pypdf.PdfWriter()
            for i, p in enumerate(paths):
                w.append(str(p))
                job["progress"] = int(90 * (i + 1) / len(paths))
            name = (d.get("name") or "مدمج").removesuffix(".pdf") + ".pdf"
            out = unique_path(paths[0].parent / name)
            with open(out, "wb") as fh:
                w.write(fh)
            return {"file": to_rel(out)}
        outs = []
        for i, p in enumerate(paths):
            r = pypdf.PdfReader(str(p))
            n = len(r.pages)
            if op == "split":
                folder = unique_path(p.parent / f"{p.stem}_صفحات")
                folder.mkdir()
                for k, page in enumerate(r.pages):
                    w = pypdf.PdfWriter()
                    w.add_page(page)
                    with open(folder / f"{p.stem}_{k + 1:03d}.pdf", "wb") as fh:
                        w.write(fh)
                outs.append(to_rel(folder))
            elif op in ("extract", "remove"):
                sel = set(parse_ranges(d.get("pages"), n))
                keep = sorted(sel) if op == "extract" else [k for k in range(n) if k not in sel]
                w = pypdf.PdfWriter()
                for k in keep:
                    w.add_page(r.pages[k])
                out = unique_path(p.with_name(p.stem + ("_مقتطف" if op == "extract" else "_معدل") + ".pdf"))
                with open(out, "wb") as fh:
                    w.write(fh)
                outs.append(to_rel(out))
            elif op == "rotate":
                w = pypdf.PdfWriter(clone_from=r)
                sel = set(parse_ranges(d.get("pages"), n)) if d.get("pages") else set(range(n))
                for k, page in enumerate(w.pages):
                    if k in sel:
                        page.rotate(int(d.get("angle") or 90))
                out = unique_path(p.with_name(p.stem + "_مدار.pdf"))
                with open(out, "wb") as fh:
                    w.write(fh)
                outs.append(to_rel(out))
            elif op == "compress":
                w = pypdf.PdfWriter(clone_from=r)
                for page in w.pages:
                    try:
                        page.compress_content_streams()
                    except Exception:
                        pass
                    if d.get("images_quality"):
                        try:
                            for img in page.images:
                                img.replace(img.image, quality=int(d["images_quality"]))
                        except Exception:
                            pass
                for kw in ({"remove_duplicates": True, "remove_unreferenced": True},
                           {"remove_identicals": True, "remove_orphans": True}, {}):
                    try:
                        w.compress_identical_objects(**kw)
                        break
                    except (TypeError, DeprecationWarning, Exception):
                        continue
                out = unique_path(p.with_name(p.stem + "_مضغوط.pdf"))
                with open(out, "wb") as fh:
                    w.write(fh)
                job_log(job, f"{p.name}: {human_size(p.stat().st_size)} ← {human_size(out.stat().st_size)}")
                outs.append(to_rel(out))
            elif op == "protect":
                w = pypdf.PdfWriter(clone_from=r)
                w.encrypt(d.get("password") or "1234", algorithm="AES-256")
                out = unique_path(p.with_name(p.stem + "_محمي.pdf"))
                with open(out, "wb") as fh:
                    w.write(fh)
                outs.append(to_rel(out))
            elif op == "text":
                txt = "\n\n".join((pg.extract_text() or "") for pg in r.pages)
                out = unique_path(p.with_suffix(".txt"))
                out.write_text(txt, encoding="utf-8")
                outs.append(to_rel(out))
            job["progress"] = int(100 * (i + 1) / len(paths))
        return {"files": outs}
    names = {"merge": "دمج", "split": "تقسيم", "extract": "استخراج صفحات", "remove": "حذف صفحات", "rotate": "تدوير",
             "compress": "ضغط", "protect": "حماية", "text": "استخراج نص"}
    return jsonify({"job": start_job(f"PDF: {names.get(op, op)}", work)})


# ----------------------------------------------------------------------------
# التشغيل
# ----------------------------------------------------------------------------
def load_config():
    cfg_file = APP_DIR / "config.json"
    if cfg_file.exists():
        try:
            return json.loads(cfg_file.read_text(encoding="utf-8"))
        except ValueError:
            print("تحذير: ملف config.json غير صالح")
    return {}


def main():
    global PASSWORD, READ_ONLY
    cfg = load_config()
    ap = argparse.ArgumentParser(description="الما — مدير الملفات على الشبكة المحلية")
    ap.add_argument("--root", action="append", help="مجلد للمشاركة (يمكن التكرار). صيغة: المسار أو الاسم=المسار")
    ap.add_argument("--port", type=int, default=cfg.get("port", 8000))
    ap.add_argument("--host", default=cfg.get("host", "0.0.0.0"))
    ap.add_argument("--password", default=cfg.get("password", ""))
    ap.add_argument("--readonly", action="store_true", default=cfg.get("read_only", False))
    args = ap.parse_args()

    roots = args.root or cfg.get("roots") or [str(Path.home())]
    if isinstance(roots, dict):
        roots = [f"{k}={v}" for k, v in roots.items()]
    for r in roots:
        if "=" in r and not re.match(r"^[A-Za-z]:[\\/]", r.split("=", 1)[0]):
            name, path = r.split("=", 1)
        else:
            path = r
            name = Path(r).expanduser().resolve().name or r.rstrip(":\\/") or "root"
        p = Path(path).expanduser().resolve()
        if not p.is_dir():
            print(f"تحذير: المجلد غير موجود: {p}")
            continue
        name = name.replace("/", "_").strip() or "root"
        base = name
        i = 2
        while name in ROOTS:
            name = f"{base}{i}"
            i += 1
        ROOTS[name] = p
    if not ROOTS:
        sys.exit("لا توجد مجلدات صالحة للمشاركة")
    PASSWORD = args.password or ""
    READ_ONLY = args.readonly
    key_file = CACHE_DIR / "secret.key"
    CACHE_DIR.mkdir(exist_ok=True)
    if not key_file.exists():
        key_file.write_text(secrets.token_hex(32))
    app.secret_key = key_file.read_text().strip()
    app.permanent_session_lifetime = dt.timedelta(days=30)

    print("=" * 60)
    print("  الما — مدير الملفات على الشبكة المحلية")
    print("=" * 60)
    for n, p in ROOTS.items():
        print(f"  📁 {n}: {p}")
    print("\n  افتح أحد هذه الروابط من أي جهاز على نفس الشبكة:")
    print(f"     http://localhost:{args.port}")
    for ip in lan_ips():
        print(f"     http://{ip}:{args.port}")
    print(f"\n  Pillow: {'✓' if HAS_PIL else '✗'}   ffmpeg: {'✓' if FFMPEG else '✗'}   "
          f"mutagen: {'✓' if HAS_MUTAGEN else '✗'}   pypdf: {'✓' if HAS_PYPDF else '✗'}")
    if PASSWORD:
        print("  🔒 محمي بكلمة مرور")
    else:
        print("  ⚠ بدون كلمة مرور — أي جهاز على الشبكة يستطيع الدخول (استخدم --password)")
    print("=" * 60)
    try:
        from waitress import serve
        serve(app, host=args.host, port=args.port, threads=16, max_request_body_size=1 << 40)
    except ImportError:
        app.run(host=args.host, port=args.port, threaded=True, debug=False)


if __name__ == "__main__":
    main()
