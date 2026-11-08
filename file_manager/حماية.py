# -*- coding: utf-8 -*-
# الما — مدير الملفات · أداة الحماية من التعديل
# إعداد م/ماهر العلفي / 00967777408089 · جميع الحقوق محفوظة
#
# 1) ختم: بعد أي تعديل تقوم به أنت، شغّل الختم وأدخل رمزك السري.
# 2) تحقق: لمعرفة هل نسخة ما أصلية أم معدّلة (يحتاج رمزك).
# البرنامج نفسه يفحص ملفاته عند كل تشغيل، ويُظهر تحذير «نسخة معدّلة» إذا تغيّر أي ملف.
import hashlib
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
FILES = ["app.py", "templates/index.html", "static/app.js", "static/style.css"]
CREDIT = "إعداد م/ماهر العلفي / 00967777408089"
SEAL_RE = re.compile(r"^# ALMA-SEAL \| (\{.*\}) \| ([0-9a-f]{64})\s*$", re.M)


def body(rel):
    raw = open(os.path.join(HERE, rel), "rb").read().replace(b"\r\n", b"\n")
    if rel == "app.py":
        raw = b"\n".join(l for l in raw.split(b"\n") if not l.startswith(b"# ALMA-SEAL |"))
    return raw


def manifest():
    return {f: hashlib.sha256(body(f)).hexdigest()[:20] for f in FILES}


def owner_seal(code, man):
    return hashlib.sha256((code + "\n" + json.dumps(man, sort_keys=True)).encode("utf-8")).hexdigest()


def ask(msg):
    try:
        return input(msg).strip()
    except EOFError:
        return ""


def seal():
    for f in FILES:
        if not os.path.isfile(os.path.join(HERE, f)):
            print("ملف ناقص:", f)
            return
    if CREDIT.split(" / ")[0].replace("إعداد ", "") not in body("app.py").decode("utf-8"):
        print("⚠ اسم صاحب البرنامج غير موجود في app.py — لن يتم الختم.")
        return
    code = ask("أدخل رمز الحماية السري (احفظه ولا تخبر به أحداً): ")
    if not code:
        print("لا يمكن الختم برمز فارغ.")
        return
    man = manifest()
    line = f"# ALMA-SEAL | {json.dumps(man, sort_keys=True)} | {owner_seal(code, man)}"
    p = os.path.join(HERE, "app.py")
    text = body("app.py").decode("utf-8").rstrip("\n")
    with open(p, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text + "\n" + line + "\n")
    print("\n✅ تم ختم البرنامج — " + CREDIT)


def verify():
    raw = open(os.path.join(HERE, "app.py"), encoding="utf-8").read()
    m = SEAL_RE.search(raw)
    if not m:
        print("⚠ البرنامج غير مختوم.")
        return
    sealed = json.loads(m.group(1))
    now = manifest()
    changed = [f for f in FILES if sealed.get(f) != now.get(f)]
    if changed:
        print("⚠ ملفات معدّلة بعد الختم:", "، ".join(changed))
    code = ask("أدخل رمز الحماية للتحقق من أن الختم أصلي: ")
    genuine = owner_seal(code, sealed) == m.group(2)
    print()
    if genuine and not changed:
        print("✅ النسخة أصلية وغير معدّلة — " + CREDIT)
    elif genuine:
        print("⚠ الختم أصلي لكن تم تعديل ملفات بعده.")
    else:
        print("⚠ النسخة معدّلة أو مختومة من غير صاحبها، أو الرمز غير صحيح.")


if __name__ == "__main__":
    print("=" * 50)
    print("  الما — أداة الحماية")
    print("  " + CREDIT)
    print("=" * 50)
    print("1) ختم البرنامج (بعد تعديلاتك)")
    print("2) التحقق من نسخة")
    c = sys.argv[1] if len(sys.argv) > 1 else ask("اختر 1 أو 2: ")
    if c in ("1", "ختم"):
        seal()
    elif c in ("2", "تحقق"):
        verify()
    if sys.stdin and sys.stdin.isatty():
        ask("\nاضغط Enter للخروج.")
