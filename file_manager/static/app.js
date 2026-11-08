/* الما — مدير الملفات: الواجهة */
"use strict";

// ============================================================================
// أدوات عامة
// ============================================================================
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const enc = encodeURIComponent;

function fmtSize(n) {
  n = Number(n) || 0;
  const u = ["بايت", "ك.ب", "م.ب", "ج.ب", "ت.ب"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return i === 0 ? `${n} ${u[0]}` : `${n.toFixed(n < 10 ? 2 : 1)} ${u[i]}`;
}
function fmtDate(t) {
  if (!t) return "";
  const d = new Date(t * 1000);
  const p = x => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function fmtDur(s) {
  s = Math.round(s || 0);
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), x = s % 60;
  return (h ? h + ":" : "") + String(m).padStart(h ? 2 : 1, "0") + ":" + String(x).padStart(2, "0");
}
function pct(a, b) { return b ? Math.round(100 - (a / b) * 100) : 0; }
const debounce = (fn, ms = 300) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
const store = {
  get(k, d) { try { const v = localStorage.getItem("alma_" + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem("alma_" + k, JSON.stringify(v)); } catch { } }
};

async function api(url, data, method) {
  const opts = { method: method || (data !== undefined ? "POST" : "GET"), headers: {} };
  if (data !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(data); }
  const r = await fetch(url, opts);
  if (r.status === 401) { location.href = "/login"; throw new Error("يلزم تسجيل الدخول"); }
  let j;
  try { j = await r.json(); } catch { throw new Error(`خطأ في الخادم (${r.status})`); }
  if (!r.ok || j.error) throw new Error(j.error || `خطأ ${r.status}`);
  return j;
}

function toast(msg, type = "", ms = 3500) {
  const el = document.createElement("div");
  el.className = "toast " + type;
  el.textContent = msg;
  $("#toasts").appendChild(el);
  setTimeout(() => el.remove(), ms);
}
const errToast = e => toast(e.message || String(e), "err", 6000);

const ICONS = { "مجلد": "📁", "صور": "🖼️", "فيديو": "🎬", "صوتيات": "🎵", "مستندات": "📄", "مضغوطات": "🗜️", "برامج": "⚙️", "برمجة": "💻", "خطوط": "🔤", "أخرى": "📎" };
const EXT_ICONS = { pdf: "📕", doc: "📘", docx: "📘", xls: "📗", xlsx: "📗", csv: "📗", ppt: "📙", pptx: "📙", txt: "📝", md: "📝", apk: "🤖", exe: "🪟" };
const iconOf = e => e.root ? "💽" : (EXT_ICONS[e.ext] || ICONS[e.cat] || "📎");
const IMG_EXT = new Set(["jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "ico"]);
const VID_EXT = new Set(["mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpg", "mpeg", "ts", "mts", "m2ts", "vob"]);
const AUD_EXT = new Set(["mp3", "wav", "aac", "flac", "ogg", "m4a", "wma", "opus", "amr"]);
const TXT_EXT = new Set(["txt", "md", "csv", "json", "xml", "yml", "yaml", "ini", "cfg", "log", "py", "js", "ts", "html", "htm", "css", "java", "c", "cpp", "h", "cs", "php", "rb", "go", "rs", "sql", "sh", "bat", "cmd", "srt", "vtt", "ass", "kt", "swift", "dart", "vue", "env", "toml", "conf", "tsv"]);
const hasThumb = e => !e.is_dir && (IMG_EXT.has(e.ext) || VID_EXT.has(e.ext));

// ============================================================================
// الحالة
// ============================================================================
const S = {
  cwd: "",
  entries: [],
  shown: [],
  sel: new Set(),
  lastIdx: -1,
  sort: store.get("sort", "name"),
  dir: store.get("dir", 1),
  view: store.get("view", "list"),
  hidden: store.get("hidden", false),
  history: [],
  clip: null,     // {mode:"copy"|"cut", paths:[]}
  info: null,
  favs: store.get("favs", []),
  typeFilter: "",
};

// ============================================================================
// النوافذ (يمكن فتح أكثر من نافذة فوق بعض)
// ============================================================================
function modal({ title, body = "", buttons = [], size = "", onClose } = {}) {
  const back = document.createElement("div");
  back.className = "modal-back";
  back.innerHTML = `<div class="modal ${size}"><div class="modal-head"><h3></h3><button class="icon-btn x">✕</button></div>
    <div class="modal-body"></div><div class="modal-foot"></div></div>`;
  $("h3", back).textContent = title || "";
  const bodyEl = $(".modal-body", back);
  if (typeof body === "string") bodyEl.innerHTML = body; else bodyEl.appendChild(body);
  const foot = $(".modal-foot", back);
  const m = {
    el: back, body: bodyEl, foot,
    close() { back.remove(); onClose && onClose(); },
    setButtons(btns) {
      foot.innerHTML = "";
      for (const b of btns) {
        const el = document.createElement("button");
        el.className = "btn " + (b.cls || "");
        el.textContent = b.text;
        if (b.id) el.id = b.id;
        el.onclick = () => b.onClick ? b.onClick(m, el) : m.close();
        foot.appendChild(el);
      }
    },
  };
  m.setButtons(buttons);
  $(".x", back).onclick = () => m.close();
  back.addEventListener("mousedown", e => { if (e.target === back) back._down = true; });
  back.addEventListener("mouseup", e => { if (e.target === back && back._down) m.close(); back._down = false; });
  document.body.appendChild(back);
  const first = $("input:not([type=checkbox]):not([type=hidden]),textarea", bodyEl);
  if (first) setTimeout(() => first.focus(), 30);
  return m;
}

function prompt2(title, value = "", label = "", selectStem = false) {
  return new Promise(res => {
    const m = modal({
      title, size: "small",
      body: `<label class="field"><span>${esc(label)}</span><input type="text" id="pIn"></label>`,
      buttons: [{ text: "موافق", cls: "primary", onClick: () => { m.close(); res($("#pIn", m.el).value); } },
      { text: "إلغاء", onClick: () => { m.close(); res(null); } }],
      onClose: () => res(null),
    });
    const inp = $("#pIn", m.el);
    inp.value = value;
    setTimeout(() => {
      inp.focus();
      const dot = value.lastIndexOf(".");
      if (selectStem && dot > 0) inp.setSelectionRange(0, dot); else inp.select();
    }, 40);
    inp.onkeydown = e => { if (e.key === "Enter") { m.close(); res(inp.value); } };
  });
}

function confirm2(title, msg, okText = "موافق", danger = false) {
  return new Promise(res => {
    const m = modal({
      title, size: "small", body: `<p>${msg}</p>`,
      buttons: [{ text: okText, cls: danger ? "danger" : "primary", onClick: () => { m.close(); res(true); } },
      { text: "إلغاء", onClick: () => { m.close(); res(false); } }],
      onClose: () => res(false),
    });
  });
}

/** يقرأ قيم الحقول التي لها name داخل عنصر */
function formVals(root) {
  const o = {};
  for (const el of $$("[name]", root)) {
    if (el.type === "checkbox") o[el.name] = el.checked;
    else if (el.type === "radio") { if (el.checked) o[el.name] = el.value; }
    else o[el.name] = el.value;
  }
  return o;
}
function setVals(root, vals) {
  for (const [k, v] of Object.entries(vals)) {
    for (const el of $$(`[name="${k}"]`, root)) {
      if (el.type === "checkbox") el.checked = !!v;
      else if (el.type === "radio") el.checked = el.value == v;
      else el.value = v;
      el.dispatchEvent(new Event("input", { bubbles: true }));
      el.dispatchEvent(new Event("change", { bubbles: true }));
    }
  }
}

// ============================================================================
// اختيار مجلد (شجرة)
// ============================================================================
function pickFolder(title = "اختر المجلد", start = S.cwd) {
  return new Promise(res => {
    let chosen = start || "";
    const m = modal({
      title, size: "small",
      body: `<div class="hint">المختار: <b id="pfSel"></b></div><div class="tree" id="pfTree"></div>
             <div class="row" style="margin-top:8px"><button class="btn" id="pfNew">＋ مجلد جديد هنا</button></div>`,
      buttons: [{ text: "اختيار", cls: "primary", onClick: () => { if (!chosen) return toast("اختر مجلداً"); m.close(); res(chosen); } },
      { text: "إلغاء", onClick: () => { m.close(); res(null); } }],
      onClose: () => res(null),
    });
    const selLbl = $("#pfSel", m.el);
    selLbl.textContent = chosen || "—";
    async function load(container, path) {
      const items = await api(`/api/tree?path=${enc(path)}`);
      container.innerHTML = "";
      if (!items.length && path) container.innerHTML = `<div class="muted" style="padding:2px 8px;font-size:12px">لا توجد مجلدات فرعية</div>`;
      for (const it of items) {
        const wrap = document.createElement("div");
        wrap.innerHTML = `<div class="node"><span class="tog">◂</span>📁 <span></span></div><div class="children" hidden></div>`;
        $("span:not(.tog)", wrap).textContent = it.name;
        const node = $(".node", wrap), ch = $(".children", wrap), tog = $(".tog", wrap);
        if (it.path === chosen) node.classList.add("sel");
        node.onclick = async () => {
          $$(".node.sel", m.el).forEach(n => n.classList.remove("sel"));
          node.classList.add("sel");
          chosen = it.path; selLbl.textContent = chosen;
          if (ch.hidden) { ch.hidden = false; tog.textContent = "▾"; await load(ch, it.path); }
          else { ch.hidden = true; tog.textContent = "◂"; }
        };
        container.appendChild(wrap);
      }
    }
    load($("#pfTree", m.el), "").catch(errToast);
    $("#pfNew", m.el).onclick = async () => {
      if (!chosen) return toast("اختر المجلد الأب أولاً");
      const name = await prompt2("مجلد جديد", "", "اسم المجلد");
      if (!name) return;
      try { await api("/api/mkdir", { path: chosen, name }); chosen = chosen + "/" + name; selLbl.textContent = chosen; toast("تم إنشاء المجلد", "ok"); load($("#pfTree", m.el), "").catch(errToast); }
      catch (e) { errToast(e); }
    };
  });
}

// ============================================================================
// المهام الطويلة
// ============================================================================
const jobWatchers = {};
let jobsTimer = null;
function watchJob(id, onDone) {
  jobWatchers[id] = onDone;
  if ($("#jobsPanel").hidden) { $("#jobsPanel").hidden = false; S.jobsAuto = true; }
  pollJobs();
}
async function pollJobs() {
  clearTimeout(jobsTimer);
  let jobs = [];
  try { jobs = await api("/api/jobs"); } catch { }
  const running = jobs.filter(j => j.status === "running");
  const badge = $("#jobsBadge");
  badge.hidden = !running.length;
  badge.textContent = running.length;
  renderJobs(jobs);
  for (const j of jobs) {
    if (j.status !== "running" && jobWatchers[j.id]) {
      const cb = jobWatchers[j.id];
      delete jobWatchers[j.id];
      try {
        const full = await api(`/api/jobs/${j.id}`);
        if (full.status === "error") toast(`فشلت المهمة: ${full.title}\n${full.message}`, "err", 8000);
        else if (full.status === "cancelled") toast(`أُلغيت: ${full.title}`);
        else toast(`اكتملت: ${full.title}`, "ok");
        cb && cb(full);
      } catch (e) { errToast(e); }
    }
  }
  if (running.length || Object.keys(jobWatchers).length) jobsTimer = setTimeout(pollJobs, 900);
  else if (S.jobsAuto) { S.jobsAuto = false; setTimeout(() => { if (!Object.keys(jobWatchers).length) $("#jobsPanel").hidden = true; }, 5000); }
}
function renderJobs(jobs) {
  const list = $("#jobsList");
  if (!jobs.length) { list.innerHTML = `<div class="job muted">لا توجد مهام</div>`; return; }
  const st = { running: "⏳ جارٍ", done: "✓ اكتمل", error: "✗ خطأ", cancelled: "⊘ ملغي" };
  list.innerHTML = jobs.map(j => `<div class="job ${j.status}">
    <div class="jt"><b>${esc(j.title)}</b><span>${st[j.status] || j.status} ${j.status === "running" ? `<button class="link" data-cancel="${j.id}">إلغاء</button>` : ""}</span></div>
    ${j.status === "running" ? `<div class="progress"><div style="width:${j.progress}%"></div></div>` : ""}
    <div class="jm">${esc(j.message || "")}</div>
    ${j.log && j.log.length && j.status !== "running" ? `<button class="link" data-log="${j.id}">عرض السجل (${j.log.length})</button>` : ""}
  </div>`).join("");
  $$("[data-cancel]", list).forEach(b => b.onclick = () => api(`/api/jobs/${b.dataset.cancel}/cancel`, {}).then(pollJobs));
  $$("[data-log]", list).forEach(b => b.onclick = async () => {
    const j = await api(`/api/jobs/${b.dataset.log}`);
    modal({ title: "سجل: " + j.title, body: `<div class="log-box">${esc(j.log.join("\n"))}</div>` });
  });
}
/** يبدأ مهمة ويعرض نتيجتها */
async function runJob(url, data, onDone) {
  try {
    const r = await api(url, data);
    if (r.job) watchJob(r.job, onDone);
    return r;
  } catch (e) { errToast(e); }
}
function sizeReport(res) {
  if (!res || !res.before) return "";
  return `قبل: <b>${fmtSize(res.before)}</b> · بعد: <b>${fmtSize(res.after)}</b> · توفير: <b>${pct(res.after, res.before)}%</b> (${fmtSize(res.before - res.after)})`;
}

// ============================================================================
// التصفح
// ============================================================================
async function loadInfo() {
  try {
    S.info = await api("/api/info");
    renderRoots();
  } catch (e) { errToast(e); }
}
function renderRoots() {
  const rl = $("#rootsList");
  rl.innerHTML = "";
  for (const r of S.info.roots) {
    const b = document.createElement("button");
    b.className = "side-item" + (S.cwd.split("/")[0] === r.name ? " active" : "");
    b.innerHTML = `💽 <span></span>`;
    $("span", b).textContent = r.name;
    b.title = r.path;
    b.onclick = () => { navigate(r.name); closeSidebar(); };
    rl.appendChild(b);
  }
  const root = S.info.roots.find(r => r.name === S.cwd.split("/")[0]) || S.info.roots[0];
  if (root && root.total) {
    $("#diskInfo").innerHTML = `<div class="side-title" style="padding:0">مساحة القرص (${esc(root.name)})</div>
      <div class="bar"><div style="width:${Math.round(root.used / root.total * 100)}%"></div></div>
      متاح ${fmtSize(root.free)} من ${fmtSize(root.total)}`;
  }
  renderFavs();
}
function renderFavs() {
  const fl = $("#favList");
  fl.innerHTML = S.favs.length ? "" : `<div class="muted" style="padding:2px 10px;font-size:12px">اضغط ☆ لإضافة المجلد الحالي</div>`;
  for (const f of S.favs) {
    const b = document.createElement("button");
    b.className = "side-item" + (S.cwd === f ? " active" : "");
    b.innerHTML = `⭐ <span></span><span class="x" title="إزالة">✕</span>`;
    $("span", b).textContent = f.split("/").pop();
    b.title = f;
    b.onclick = e => {
      if (e.target.classList.contains("x")) { S.favs = S.favs.filter(x => x !== f); store.set("favs", S.favs); renderFavs(); updateFavBtn(); return; }
      navigate(f); closeSidebar();
    };
    fl.appendChild(b);
  }
}
function updateFavBtn() { $("#favBtn").textContent = S.favs.includes(S.cwd) ? "★" : "☆"; }

async function navigate(path, push = true) {
  try {
    const r = await api(`/api/list?path=${enc(path)}&hidden=${S.hidden ? 1 : 0}`);
    if (push && S.cwd !== r.path) S.history.push(S.cwd);
    S.cwd = r.path;
    S.entries = r.entries;
    S.sel.clear();
    S.lastIdx = -1;
    $("#quickFilter").value = "";
    store.set("cwd", S.cwd);
    if (location.hash.slice(1) !== enc(S.cwd)) history.replaceState(null, "", "#" + enc(S.cwd));
    render();
    renderCrumbs();
    if (S.info) renderRoots();
    updateFavBtn();
  } catch (e) {
    errToast(e);
    if (!S.entries.length && path) navigate("", false);
  }
}
const refresh = () => navigate(S.cwd, false).then(() => { });

function renderCrumbs() {
  const c = $("#crumbs");
  c.innerHTML = "";
  const home = document.createElement("button");
  home.textContent = "🏠";
  home.onclick = () => navigate("");
  c.appendChild(home);
  if (!S.cwd) return;
  const parts = S.cwd.split("/");
  parts.forEach((p, i) => {
    const sep = document.createElement("span");
    sep.className = "sep"; sep.textContent = "‹";
    c.appendChild(sep);
    const b = document.createElement("button");
    b.textContent = p;
    const target = parts.slice(0, i + 1).join("/");
    b.onclick = () => navigate(target);
    b.ondragover = e => e.preventDefault();
    b.ondrop = e => dropOnFolder(e, target);
    c.appendChild(b);
  });
}

function sortEntries(list) {
  const k = S.sort, d = S.dir;
  const coll = new Intl.Collator("ar", { numeric: true, sensitivity: "base" });
  return [...list].sort((a, b) => {
    if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1;
    let r = 0;
    if (k === "size") r = a.size - b.size;
    else if (k === "mtime") r = a.mtime - b.mtime;
    else if (k === "ext") r = coll.compare(a.ext, b.ext) || coll.compare(a.name, b.name);
    else r = coll.compare(a.name, b.name);
    return r * d;
  });
}

function render() {
  const q = $("#quickFilter").value.trim().toLowerCase();
  let list = S.entries;
  if (q) list = list.filter(e => e.name.toLowerCase().includes(q));
  if (S.typeFilter) list = list.filter(e => e.cat === S.typeFilter);
  S.shown = sortEntries(list);
  const cutSet = new Set(S.clip && S.clip.mode === "cut" ? S.clip.paths : []);
  const tbody = $("#filesBody"), grid = $("#filesGrid");
  $("#filesTable").hidden = S.view !== "list";
  grid.hidden = S.view !== "grid";
  $("#emptyMsg").hidden = S.shown.length > 0;
  if (S.view === "list") {
    tbody.innerHTML = S.shown.map((e, i) => `<tr data-i="${i}" class="${S.sel.has(e.path) ? "sel" : ""} ${cutSet.has(e.path) ? "cut" : ""}" draggable="${!e.root}">
      <td class="c-check"><input type="checkbox" ${S.sel.has(e.path) ? "checked" : ""}></td>
      <td class="c-name"><div class="fname"><span class="ficon">${hasThumb(e) ? `<img loading="lazy" src="/api/thumb?s=64&path=${enc(e.path)}" onerror="this.replaceWith(document.createTextNode('${iconOf(e)}'))">` : iconOf(e)}</span><span class="n" title="${esc(e.name)}">${esc(e.name)}</span></div></td>
      <td class="c-size muted">${e.is_dir ? "" : fmtSize(e.size)}</td>
      <td class="c-type muted">${e.is_dir ? "مجلد" : esc((e.ext || "").toUpperCase() || e.cat)}</td>
      <td class="c-date muted">${fmtDate(e.mtime)}</td></tr>`).join("");
  } else {
    grid.innerHTML = S.shown.map((e, i) => `<div class="tile ${S.sel.has(e.path) ? "sel" : ""} ${cutSet.has(e.path) ? "cut" : ""}" data-i="${i}" draggable="${!e.root}">
      <input type="checkbox" ${S.sel.has(e.path) ? "checked" : ""}>
      <div class="thumb">${hasThumb(e) ? `<img loading="lazy" src="/api/thumb?s=256&path=${enc(e.path)}" onerror="this.replaceWith(document.createTextNode('${iconOf(e)}'))">` : iconOf(e)}</div>
      <div class="tname" title="${esc(e.name)}">${esc(e.name)}</div><div class="tsize">${e.is_dir ? "" : fmtSize(e.size)}</div></div>`).join("");
  }
  $("#checkAll").checked = S.shown.length > 0 && S.shown.every(e => S.sel.has(e.path));
  $$("#filesTable th[data-sort]").forEach(th => {
    th.textContent = th.textContent.replace(/ [▲▼]$/, "") + (th.dataset.sort === S.sort ? (S.dir > 0 ? " ▲" : " ▼") : "");
  });
  $("#sortSel").value = S.sort;
  $("#viewBtn").textContent = S.view === "list" ? "▦" : "☰";
  $("#hiddenBtn").classList.toggle("active", S.hidden);
  $("#pasteBtn").hidden = !S.clip;
  if (S.clip) $("#pasteBtn").textContent = `📋 لصق (${S.clip.paths.length})`;
  updateSelbar();
  const files = S.entries.filter(e => !e.is_dir);
  $("#statusbar").textContent = S.cwd ? `${S.entries.length - files.length} مجلد · ${files.length} ملف · ${fmtSize(files.reduce((a, e) => a + e.size, 0))}` + (S.shown.length !== S.entries.length ? ` · معروض ${S.shown.length}` : "") : "اختر موقعاً";
}

function updateSelbar() {
  const n = S.sel.size;
  $("#selbar").hidden = n === 0;
  if (!n) return;
  const items = selItems();
  $("#selCount").textContent = n;
  const size = items.reduce((a, e) => a + (e.size || 0), 0);
  $("#selSize").textContent = fmtSize(size) + (items.some(e => e.is_dir) ? " + مجلدات" : "");
  const one = n === 1 ? items[0] : null;
  const btn = a => $(`.sel-actions [data-act="${a}"]`);
  btn("open").hidden = !one;
  btn("rename").hidden = !one;
  btn("props").hidden = !one;
  btn("hash").hidden = !(one && !one.is_dir);
  btn("unzip").hidden = !(one && one.ext === "zip");
  btn("images").hidden = !items.some(e => IMG_EXT.has(e.ext));
  btn("videos").hidden = !items.some(e => VID_EXT.has(e.ext));
}
const selItems = () => S.entries.filter(e => S.sel.has(e.path));
const selPaths = () => [...S.sel];

function setSel(i, mode) {
  const e = S.shown[i];
  if (!e) return;
  if (mode === "range" && S.lastIdx >= 0) {
    const [a, b] = [Math.min(S.lastIdx, i), Math.max(S.lastIdx, i)];
    for (let k = a; k <= b; k++) S.sel.add(S.shown[k].path);
  } else if (mode === "toggle") {
    S.sel.has(e.path) ? S.sel.delete(e.path) : S.sel.add(e.path);
    S.lastIdx = i;
  } else {
    S.sel.clear(); S.sel.add(e.path); S.lastIdx = i;
  }
  render();
}

function openEntry(e) {
  if (e.is_dir) return navigate(e.path);
  preview(e);
}

// ============================================================================
// المعاينة
// ============================================================================
function preview(e) {
  const url = `/api/file?path=${enc(e.path)}`;
  let body = "";
  const ext = e.ext;
  let isText = TXT_EXT.has(ext) || (!ext && e.size < 200000);
  if (IMG_EXT.has(ext) || ext === "svg") body = `<div class="preview-media"><img src="${url}"></div>`;
  else if (VID_EXT.has(ext)) body = `<div class="preview-media"><video src="${url}" controls autoplay></video></div><div class="hint">إذا لم يعمل الفيديو فصيغته غير مدعومة في المتصفح — استخدم أداة التحويل إلى MP4.</div>`;
  else if (AUD_EXT.has(ext)) body = `<div class="preview-media" style="background:none"><audio src="${url}" controls autoplay></audio></div>`;
  else if (ext === "pdf") body = `<div class="preview-media"><iframe src="${url}"></iframe></div>`;
  else if (isText) body = `<textarea class="editor" id="edArea" spellcheck="false">جارٍ التحميل...</textarea><div class="hint" id="edInfo"></div>`;
  else body = `<div class="empty">لا توجد معاينة لهذا النوع<br><br>${esc(e.name)} — ${fmtSize(e.size)}</div>`;
  const media = S.shown.filter(x => !x.is_dir && (IMG_EXT.has(x.ext) || VID_EXT.has(x.ext)));
  const idx = media.findIndex(x => x.path === e.path);
  const buttons = [{ text: "⬇ تنزيل", onClick: () => download(e.path) }];
  if (isText) buttons.unshift({ text: "💾 حفظ", cls: "primary", onClick: async (m) => {
    try { await api("/api/text", { path: e.path, content: $("#edArea", m.el).value, encoding: m.encoding }); toast("تم الحفظ", "ok"); }
    catch (er) { errToast(er); }
  } });
  if (idx >= 0 && media.length > 1) {
    buttons.push({ text: "→ السابق", onClick: m => { m.close(); preview(media[(idx - 1 + media.length) % media.length]); } });
    buttons.push({ text: "التالي ←", onClick: m => { m.close(); preview(media[(idx + 1) % media.length]); } });
  }
  buttons.push({ text: "ℹ️ خصائص", onClick: () => showProps(e.path) });
  if (!IMG_EXT.has(ext) || true) buttons.push({ text: "🏷 بيانات وصفية", onClick: () => { S.sel = new Set([e.path]); toolMeta(); } });
  const m = modal({ title: e.name, body, buttons, size: "wide" });
  if (idx >= 0) {
    const key = ev => {
      if (!document.body.contains(m.el)) return document.removeEventListener("keydown", key);
      if (ev.key === "ArrowLeft") { m.close(); preview(media[(idx + 1) % media.length]); }
      if (ev.key === "ArrowRight") { m.close(); preview(media[(idx - 1 + media.length) % media.length]); }
    };
    document.addEventListener("keydown", key);
  }
  if (isText) {
    api(`/api/text?path=${enc(e.path)}`).then(r => {
      $("#edArea", m.el).value = r.content;
      m.encoding = r.encoding;
      $("#edInfo", m.el).textContent = `الترميز: ${r.encoding} · ${r.content.split("\n").length} سطر`;
    }).catch(er => { $("#edArea", m.el).value = er.message; });
    $("#edArea", m.el).addEventListener("keydown", ev => {
      if (ev.key === "s" && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); $(".modal-foot .primary", m.el).click(); }
      if (ev.key === "Tab") { ev.preventDefault(); document.execCommand("insertText", false, "    "); }
    });
  }
}

async function showProps(path) {
  try {
    const p = await api(`/api/props?path=${enc(path)}`);
    const rows = [["الاسم", p.name], ["المسار", p.full], ["النوع", p.is_dir ? "مجلد" : `${p.cat} (${p.mime || "?"})`],
    ["الحجم", `${fmtSize(p.size)} (${(p.size || 0).toLocaleString("en")} بايت)`]];
    if (p.is_dir) rows.push(["المحتوى", `${p.files} ملف · ${p.dirs} مجلد`]);
    if (p.dimensions) rows.push(["الأبعاد", p.dimensions]);
    if (p.duration) rows.push(["المدة", fmtDur(p.duration)]);
    if (p.width) rows.push(["الدقة", `${p.width}×${p.height}`]);
    if (p.vcodec) rows.push(["ترميز الفيديو", p.vcodec]);
    if (p.acodec) rows.push(["ترميز الصوت", p.acodec]);
    if (p.fps) rows.push(["الإطارات/ث", p.fps]);
    if (p.bitrate) rows.push(["معدل البت", Math.round(p.bitrate / 1000) + " kbps"]);
    rows.push(["آخر تعديل", fmtDate(p.mtime)], ["الإنشاء/التغيير", fmtDate(p.ctime)], ["آخر وصول", fmtDate(p.atime)]);
    modal({ title: "خصائص", size: "small", body: `<div class="kv">${rows.map(([k, v]) => `<div>${k}</div><div>${esc(v)}</div>`).join("")}</div>` });
  } catch (e) { errToast(e); }
}

// ============================================================================
// العمليات الأساسية
// ============================================================================
function download(path) {
  const a = document.createElement("a");
  a.href = `/api/file?dl=1&path=${enc(path)}`;
  a.download = "";
  document.body.appendChild(a); a.click(); a.remove();
}
function downloadSelected() {
  const items = selItems();
  if (items.length === 1 && !items[0].is_dir) return download(items[0].path);
  const f = document.createElement("form");
  f.method = "post"; f.action = "/api/download_zip";
  for (const p of selPaths()) { const i = document.createElement("input"); i.type = "hidden"; i.name = "paths"; i.value = p; f.appendChild(i); }
  const n = document.createElement("input"); n.type = "hidden"; n.name = "name"; n.value = (S.cwd.split("/").pop() || "files") + ".zip"; f.appendChild(n);
  document.body.appendChild(f); f.submit(); f.remove();
  toast("جارٍ تجهيز الملف المضغوط للتنزيل...");
}
async function renameOne() {
  const e = selItems()[0];
  if (!e) return;
  const name = await prompt2("إعادة تسمية", e.name, "الاسم الجديد", !e.is_dir);
  if (!name || name === e.name) return;
  try { await api("/api/rename", { path: e.path, name }); await refresh(); toast("تمت إعادة التسمية", "ok"); } catch (er) { errToast(er); }
}
async function deleteSelected(permanent = false) {
  const n = S.sel.size;
  if (!n) return;
  const ok = await confirm2(permanent ? "حذف نهائي" : "نقل إلى السلة",
    permanent ? `سيتم حذف <b>${n}</b> عنصر نهائياً ولا يمكن استرجاعه!` : `نقل <b>${n}</b> عنصر إلى سلة المحذوفات؟ (يمكن الاسترجاع لاحقاً)`,
    permanent ? "حذف نهائي" : "نقل إلى السلة", true);
  if (!ok) return;
  try { const r = await api("/api/delete", { paths: selPaths(), permanent }); toast(`تم حذف ${r.count} عنصر`, "ok"); await refresh(); } catch (e) { errToast(e); }
}
async function mkdir() {
  if (!S.cwd) return toast("افتح موقعاً أولاً");
  const name = await prompt2("مجلد جديد", "مجلد جديد", "اسم المجلد");
  if (!name) return;
  try { await api("/api/mkdir", { path: S.cwd, name }); await refresh(); } catch (e) { errToast(e); }
}
async function newFile() {
  if (!S.cwd) return toast("افتح موقعاً أولاً");
  const name = await prompt2("ملف نصي جديد", "ملف جديد.txt", "اسم الملف", true);
  if (!name) return;
  try { await api("/api/newfile", { path: S.cwd, name }); await refresh(); const e = S.entries.find(x => x.name === name); if (e) preview(e); } catch (e) { errToast(e); }
}
function clipSet(mode) {
  if (!S.sel.size) return;
  S.clip = { mode, paths: selPaths() };
  toast(mode === "cut" ? `تم قص ${S.clip.paths.length} عنصر — افتح الوجهة واضغط لصق` : `تم نسخ ${S.clip.paths.length} عنصر — افتح الوجهة واضغط لصق`);
  render();
}
async function paste() {
  if (!S.clip || !S.cwd) return;
  const { mode, paths } = S.clip;
  try {
    await api(mode === "cut" ? "/api/move" : "/api/copy", { paths, dest: S.cwd });
    if (mode === "cut") S.clip = null;
    toast("تم اللصق", "ok");
    await refresh();
  } catch (e) { errToast(e); }
}
async function transferTo(mode) {
  const dest = await pickFolder(mode === "move" ? "نقل إلى..." : "نسخ إلى...");
  if (!dest) return;
  try { await api(mode === "move" ? "/api/move" : "/api/copy", { paths: selPaths(), dest }); toast("تم", "ok"); await refresh(); } catch (e) { errToast(e); }
}
async function zipSelected() {
  const items = selItems();
  const def = items.length === 1 ? items[0].name.replace(/\.[^.]+$/, "") : (S.cwd.split("/").pop() || "مضغوط");
  const name = await prompt2("ضغط ZIP", def + ".zip", "اسم الملف المضغوط", true);
  if (!name) return;
  runJob("/api/zip", { paths: selPaths(), name }, refresh);
}
async function unzipSelected() {
  const e = selItems()[0];
  if (!e) return;
  runJob("/api/unzip", { path: e.path }, refresh);
}
async function hashSelected() {
  const e = selItems()[0];
  runJob("/api/hash", { path: e.path }, j => {
    const r = j.result;
    if (r) modal({ title: "بصمة الملف: " + r.name, body: `<div class="kv mono"><div>MD5</div><div>${r.md5}</div><div>SHA-1</div><div>${r.sha1}</div><div>SHA-256</div><div>${r.sha256}</div></div>
      <label class="field" style="margin-top:12px"><span>قارن مع بصمة (الصق هنا)</span><input type="text" id="hcmp" class="mono"></label><div id="hres" class="hint"></div>` }).el.querySelector("#hcmp").oninput = ev => {
        const v = ev.target.value.trim().toLowerCase();
        ev.target.closest(".modal").querySelector("#hres").innerHTML = !v ? "" : [r.md5, r.sha1, r.sha256].includes(v) ? `<b style="color:var(--ok)">✓ مطابقة</b>` : `<b style="color:var(--danger)">✗ غير مطابقة</b>`;
      };
  });
}
async function undo() {
  try { const r = await api("/api/undo", {}); toast(`تم التراجع عن: ${r.title} (${r.done})` + (r.failed.length ? ` · فشل ${r.failed.length}` : ""), "ok"); await refresh(); }
  catch (e) { errToast(e); }
}

// ============================================================================
// الرفع
// ============================================================================
function uploadFiles(files, dest = S.cwd) {
  if (!dest) return toast("افتح مجلداً أولاً");
  if (!files.length) return;
  const fd = new FormData();
  fd.append("path", dest);
  let total = 0;
  for (const f of files) {
    fd.append("files", f.file || f, (f.file || f).name);
    fd.append("relpath", f.rel || f.webkitRelativePath || (f.file || f).name);
    total += (f.file || f).size;
  }
  const bar = $("#uploadBar");
  bar.hidden = false;
  $("#uploadText").textContent = `رفع ${files.length} ملف (${fmtSize(total)})...`;
  const xhr = new XMLHttpRequest();
  const t0 = Date.now();
  xhr.upload.onprogress = e => {
    if (!e.lengthComputable) return;
    const p = e.loaded / e.total * 100;
    $("#uploadProg").style.width = p + "%";
    const speed = e.loaded / ((Date.now() - t0) / 1000);
    $("#uploadText").textContent = `رفع ${files.length} ملف · ${Math.round(p)}% · ${fmtSize(speed)}/ث`;
  };
  xhr.onload = () => {
    bar.hidden = true; $("#uploadProg").style.width = "0";
    if (xhr.status === 200) { toast(`تم رفع ${files.length} ملف`, "ok"); if (dest === S.cwd) refresh(); }
    else { try { errToast(new Error(JSON.parse(xhr.responseText).error)); } catch { errToast(new Error("فشل الرفع")); } }
  };
  xhr.onerror = () => { bar.hidden = true; errToast(new Error("انقطع الاتصال أثناء الرفع")); };
  xhr.open("POST", "/api/upload");
  xhr.send(fd);
}
async function entriesFromDrop(dt) {
  const out = [];
  const items = [...(dt.items || [])].map(i => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
  if (!items.length) return [...dt.files];
  async function walk(entry, prefix) {
    if (entry.isFile) {
      const file = await new Promise((res, rej) => entry.file(res, rej));
      out.push({ file, rel: prefix + file.name });
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      let batch;
      do {
        batch = await new Promise((res, rej) => reader.readEntries(res, rej));
        for (const e of batch) await walk(e, prefix + entry.name + "/");
      } while (batch.length);
    }
  }
  for (const it of items) await walk(it, "");
  return out;
}
async function dropOnFolder(e, folder) {
  e.preventDefault(); e.stopPropagation();
  $("#dropOverlay").hidden = true;
  const internal = e.dataTransfer.getData("application/x-alma");
  if (internal) {
    const paths = JSON.parse(internal);
    if (paths.includes(folder)) return;
    const copy = e.ctrlKey;
    try { await api(copy ? "/api/copy" : "/api/move", { paths, dest: folder }); toast(copy ? "تم النسخ" : "تم النقل", "ok"); refresh(); } catch (er) { errToast(er); }
    return;
  }
  uploadFiles(await entriesFromDrop(e.dataTransfer), folder);
}

// ============================================================================
// تحديد الهدف للأدوات (المحدد أو المجلد الحالي)
// ============================================================================
function targetHTML(opts = {}) {
  const n = S.sel.size;
  if (n) return `<div class="hint">سيتم التطبيق على <b>${n}</b> عنصر محدد. <label class="check" style="display:inline-flex"><input type="checkbox" name="_useFolder"> تجاهل التحديد واستخدم كل المجلد</label></div>`;
  return `<div class="hint">سيتم التطبيق على ملفات المجلد الحالي <b>${esc(S.cwd || "—")}</b>
    ${opts.noRecursive ? "" : `<label class="check" style="display:inline-flex"><input type="checkbox" name="_recursive"> مع المجلدات الفرعية</label>`}
    ${opts.filter ? `&nbsp; تصفية بالامتداد: <input type="text" name="_filter" placeholder="مثال: jpg,png" style="width:130px">` : ""}</div>`;
}
function targetPayload(root) {
  const v = formVals(root);
  if (S.sel.size && !v._useFolder) return { paths: selPaths() };
  return { folder: S.cwd, recursive: !!v._recursive, filter: v._filter || "" };
}
function needFolder() { if (!S.cwd) { toast("افتح مجلداً أولاً"); return false; } return true; }

// ============================================================================
// أداة: إعادة التسمية دفعة واحدة
// ============================================================================
const RULES = {
  replace: { label: "🔁 بحث واستبدال", fields: [["find", "ابحث عن", "text"], ["repl", "استبدل بـ", "text"], ["regex", "تعبير نمطي (Regex)", "check"], ["case", "مطابقة حالة الأحرف", "check"], ["scope", "يشمل الامتداد", "checkAll"]] },
  prefix: { label: "⬅ إضافة نص في البداية", fields: [["text", "النص", "text"]] },
  suffix: { label: "➡ إضافة نص في النهاية (قبل الامتداد)", fields: [["text", "النص", "text"]] },
  insert: { label: "📍 إدراج نص في موضع", fields: [["text", "النص", "text"], ["pos", "الموضع (رقم الحرف)", "number", 0], ["from_end", "من النهاية", "check"]] },
  remove: { label: "✂ حذف أحرف", fields: [["start", "من الحرف رقم", "number", 0], ["count", "عدد الأحرف", "number", 1], ["from_end", "من النهاية", "check"]] },
  keep: { label: "📏 الاحتفاظ بأول/آخر أحرف", fields: [["count", "عدد الأحرف", "number", 10], ["from_end", "من النهاية", "check"]] },
  number: { label: "🔢 ترقيم تسلسلي", fields: [["mode", "الموضع", "select", "prefix", { prefix: "في البداية", suffix: "في النهاية", replace: "استبدال الاسم كاملاً" }], ["text", "اسم أساسي (عند الاستبدال)", "text"], ["start", "يبدأ من", "number", 1], ["step", "الزيادة", "number", 1], ["pad", "عدد الخانات (001)", "number", 3], ["sep", "الفاصل", "text", "_"], ["arabic_digits", "أرقام عربية (١٢٣)", "check"]] },
  case: { label: "🔠 حالة الأحرف", fields: [["mode", "التحويل", "select", "lower", { lower: "أحرف صغيرة", upper: "أحرف كبيرة", title: "أول حرف كبير لكل كلمة", sentence: "أول حرف كبير فقط" }]] },
  clean: { label: "🧽 تنظيف الأسماء", fields: [["trim", "حذف المسافات الزائدة بالأطراف", "check", true], ["collapse", "دمج المسافات المتكررة", "check"], ["spaces_to", "استبدال المسافات بـ", "text", ""], ["underscore_to_space", "تحويل _ و . إلى مسافة", "check"], ["remove_digits", "حذف الأرقام", "check"], ["remove_symbols", "حذف الرموز الخاصة", "check"], ["remove_brackets", "حذف ما بين الأقواس", "check"], ["tashkeel", "حذف التشكيل والتطويل", "check"], ["normalize_alef", "توحيد (أإآ←ا، ة←ه، ى←ي)", "check"], ["ar_digits_to_en", "أرقام عربية ← إنجليزية", "check"], ["translit", "تحويل العربي لحروف لاتينية", "check"]] },
  date: { label: "📅 إضافة التاريخ", fields: [["source", "مصدر التاريخ", "select", "mtime", { mtime: "تاريخ التعديل", exif: "تاريخ التصوير (EXIF)", ctime: "تاريخ الإنشاء", now: "تاريخ اليوم" }], ["format", "الصيغة", "text", "%Y-%m-%d"], ["mode", "الموضع", "select", "prefix", { prefix: "في البداية", suffix: "في النهاية", replace: "استبدال الاسم" }], ["sep", "الفاصل", "text", "_"]] },
  template: { label: "🧩 قالب اسم مخصص", fields: [["pattern", "القالب", "text", "{parent}_{n:3}_{name}"], ["start", "بداية الترقيم", "number", 1], ["source", "مصدر {date}", "select", "mtime", { mtime: "تاريخ التعديل", exif: "تاريخ التصوير", ctime: "تاريخ الإنشاء", now: "اليوم" }]] },
  ext: { label: "🏷 تغيير الامتداد", fields: [["mode", "العملية", "select", "lower", { lower: "امتداد بأحرف صغيرة", upper: "امتداد بأحرف كبيرة", set: "تعيين امتداد جديد", remove: "حذف الامتداد" }], ["text", "الامتداد الجديد", "text", ""]] },
};

function ruleEl(type, vals = {}) {
  const def = RULES[type];
  const el = document.createElement("div");
  el.className = "rule";
  el.dataset.type = type;
  let html = `<div class="rule-head"><b>${def.label}</b><button class="icon-btn" data-up title="لأعلى">▲</button><button class="icon-btn" data-down title="لأسفل">▼</button><button class="icon-btn" data-del title="حذف">✕</button></div><div class="row">`;
  for (const [k, label, t, d, opts] of def.fields) {
    const v = vals[k] ?? d ?? "";
    if (t === "check") html += `<label class="check"><input type="checkbox" name="${k}" ${v ? "checked" : ""}> ${label}</label>`;
    else if (t === "checkAll") html += `<label class="check"><input type="checkbox" name="${k}" ${v === "all" || v === true ? "checked" : ""}> ${label}</label>`;
    else if (t === "select") html += `<label class="field"><span>${label}</span><select name="${k}">${Object.entries(opts).map(([ov, ol]) => `<option value="${ov}" ${ov == v ? "selected" : ""}>${ol}</option>`).join("")}</select></label>`;
    else html += `<label class="field" ${t === "text" ? 'style="flex:1"' : ""}><span>${label}</span><input type="${t}" name="${k}" value="${esc(v)}"></label>`;
  }
  html += "</div>";
  if (type === "template") html += `<div class="hint">المتغيرات: <code>{name}</code> الاسم الحالي · <code>{n}</code> أو <code>{n:3}</code> رقم · <code>{date}</code> أو <code>{date:%Y%m%d}</code> · <code>{parent}</code> اسم المجلد · <code>{ext}</code> · <code>{size}</code> · <code>{w}</code>×<code>{h}</code> أبعاد الصورة · <code>{total}</code> العدد · <code>{rand}</code> عشوائي</div>`;
  if (type === "date") html += `<div class="hint">رموز الصيغة: <code>%Y</code> السنة · <code>%m</code> الشهر · <code>%d</code> اليوم · <code>%H</code> الساعة · <code>%M</code> الدقيقة · <code>%S</code> الثانية</div>`;
  if (type === "replace") html += `<div class="hint">مع Regex يمكن استخدام <code>\\1</code> للمجموعات. مثال: <code>(\\d+)</code> ← <code>رقم_\\1</code></div>`;
  el.innerHTML = html;
  return el;
}
function readRules(container) {
  return $$(".rule", container).map(r => {
    const o = { type: r.dataset.type };
    for (const el of $$("[name]", r)) o[el.name] = el.type === "checkbox" ? (el.name === "scope" ? (el.checked ? "all" : "stem") : el.checked) : el.value;
    return o;
  });
}

function toolBatchRename() {
  if (!needFolder()) return;
  const presets = store.get("renamePresets", {});
  const m = modal({
    title: "✏️ إعادة تسمية دفعة واحدة", size: "wide",
    body: `${targetHTML({ filter: true })}
      <div class="row" style="margin-bottom:10px">
        <select id="addRule"><option value="">＋ أضف قاعدة...</option>${Object.entries(RULES).map(([k, v]) => `<option value="${k}">${v.label}</option>`).join("")}</select>
        <label class="field" style="flex-direction:row;align-items:center;gap:6px"><span>ترتيب الترقيم حسب</span>
          <select name="sort"><option value="name">الاسم</option><option value="mtime">تاريخ التعديل</option><option value="exif">تاريخ التصوير</option><option value="size">الحجم</option><option value="none">ترتيب التحديد</option></select></label>
        <label class="check"><input type="checkbox" name="files_only" checked> الملفات فقط (بدون المجلدات)</label>
        <span class="spacer"></span>
        <select id="presetSel"><option value="">القوالب المحفوظة...</option>${Object.keys(presets).map(k => `<option>${esc(k)}</option>`).join("")}</select>
        <button class="btn" id="savePreset">💾 حفظ كقالب</button>
      </div>
      <div class="hint">القواعد تُطبق بالترتيب من الأعلى للأسفل. المعاينة تتحدث تلقائياً.</div>
      <div class="rules" id="rulesBox"></div>
      <div class="preview-box"><table class="preview-table"><thead><tr><th>الاسم الحالي</th><th>الاسم الجديد</th></tr></thead><tbody id="rnPrev"></tbody></table></div>
      <div class="hint" id="rnStat"></div>`,
    buttons: [{ text: "✓ تطبيق إعادة التسمية", cls: "primary", onClick: apply }, { text: "إغلاق" }],
  });
  const box = $("#rulesBox", m.el);
  const update = debounce(async () => {
    const data = { ...targetPayload(m.body), rules: readRules(box), sort: $("[name=sort]", m.el).value, files_only: $("[name=files_only]", m.el).checked };
    try {
      const r = await api("/api/batch_rename", data);
      $("#rnPrev", m.el).innerHTML = r.items.map(x => `<tr><td>${esc(x.old)}</td><td class="${x.error ? "err" : x.changed ? "new" : "same"}">${esc(x.new)}${x.error ? ` ⚠ ${esc(x.error)}` : ""}</td></tr>`).join("");
      $("#rnStat", m.el).innerHTML = `الإجمالي: <b>${r.total}</b> · سيتغير: <b>${r.changed}</b>` + (r.errors ? ` · <span style="color:var(--danger)">أخطاء: ${r.errors} (سيتم تخطيها)</span>` : "");
    } catch (e) { $("#rnStat", m.el).innerHTML = `<span style="color:var(--danger)">${esc(e.message)}</span>`; }
  }, 250);
  const addRule = (type, vals) => { box.appendChild(ruleEl(type, vals)); update(); };
  $("#addRule", m.el).onchange = e => { if (e.target.value) addRule(e.target.value); e.target.value = ""; };
  box.addEventListener("input", update);
  box.addEventListener("change", update);
  box.addEventListener("click", e => {
    const r = e.target.closest(".rule");
    if (!r) return;
    if (e.target.dataset.del !== undefined) { r.remove(); update(); }
    if (e.target.dataset.up !== undefined && r.previousElementSibling) { r.parentNode.insertBefore(r, r.previousElementSibling); update(); }
    if (e.target.dataset.down !== undefined && r.nextElementSibling) { r.parentNode.insertBefore(r.nextElementSibling, r); update(); }
  });
  m.body.addEventListener("change", e => { if (!e.target.closest(".rule")) update(); });
  m.body.addEventListener("input", e => { if (e.target.name === "_filter") update(); });
  $("#savePreset", m.el).onclick = async () => {
    const name = await prompt2("حفظ قالب", "", "اسم القالب");
    if (!name) return;
    const p = store.get("renamePresets", {});
    p[name] = readRules(box);
    store.set("renamePresets", p);
    toast("تم حفظ القالب", "ok");
  };
  $("#presetSel", m.el).onchange = e => {
    const rules = store.get("renamePresets", {})[e.target.value];
    if (!rules) return;
    box.innerHTML = "";
    rules.forEach(r => box.appendChild(ruleEl(r.type, r)));
    update();
  };
  addRule("replace");
  async function apply() {
    const data = { ...targetPayload(m.body), rules: readRules(box), sort: $("[name=sort]", m.el).value, files_only: $("[name=files_only]", m.el).checked, apply: true };
    try {
      const r = await api("/api/batch_rename", data);
      toast(`تمت إعادة تسمية ${r.count} ملف` + (r.skipped ? ` (تخطي ${r.skipped})` : "") + " — يمكنك التراجع بـ Ctrl+Z", "ok", 5000);
      m.close(); refresh();
    } catch (e) { errToast(e); }
  }
}

// ============================================================================
// أداة: التنظيم والفرز
// ============================================================================
function toolOrganize() {
  if (!needFolder()) return;
  const m = modal({
    title: "🗂️ تنظيم وفرز الملفات تلقائياً", size: "wide",
    body: `${targetHTML()}
    <div class="form-grid">
      <label class="field"><span>طريقة التنظيم</span><select name="mode">
        <option value="type">حسب النوع (صور، فيديو، مستندات...)</option>
        <option value="ext">حسب الامتداد (JPG، PDF...)</option>
        <option value="year">حسب السنة</option>
        <option value="month">حسب السنة/الشهر</option>
        <option value="day">حسب السنة/الشهر/اليوم</option>
        <option value="size">حسب الحجم</option>
        <option value="letter">حسب الحرف الأول</option>
        <option value="keyword">حسب كلمات في الاسم</option>
        <option value="dims">الصور: أفقية / عمودية / مربعة</option>
        <option value="chunk">تقسيم إلى مجلدات بعدد ثابت</option>
        <option value="flatten">تسطيح: إخراج كل الملفات من المجلدات الفرعية</option>
      </select></label>
      <label class="field" data-show="year month day"><span>مصدر التاريخ</span><select name="source"><option value="mtime">تاريخ التعديل</option><option value="exif">تاريخ التصوير (EXIF) للصور</option><option value="ctime">تاريخ الإنشاء</option></select></label>
      <label class="check" data-show="month day"><input type="checkbox" name="nested" checked> مجلدات متداخلة (2024/05)</label>
      <label class="check" data-show="month day"><input type="checkbox" name="month_names"> أسماء الأشهر (05-مايو)</label>
      <label class="field" data-show="chunk"><span>عدد الملفات في كل مجلد</span><input type="number" name="chunk" value="100"></label>
      <label class="field" data-show="chunk"><span>اسم المجلدات</span><input type="text" name="chunk_name" value="مجموعة"></label>
      <label class="field full" data-show="keyword"><span>القواعد: كل سطر "كلمات=مجلد"</span><textarea name="rules" rows="4" placeholder="فاتورة,invoice=فواتير&#10;عقد=عقود&#10;screenshot,لقطة=لقطات شاشة"></textarea></label>
      <label class="check" data-show="keyword"><input type="checkbox" name="move_others"> نقل غير المطابق إلى مجلد "أخرى"</label>
      <label class="field"><span>الوجهة (اتركها فارغة = نفس المجلد)</span><div class="row"><input type="text" name="dest" readonly style="flex:1"><button class="btn" id="orgDest">اختيار</button></div></label>
      <label class="check"><input type="checkbox" name="copy"> نسخ بدلاً من النقل</label>
      <label class="check"><input type="checkbox" name="remove_empty"> حذف المجلدات الفارغة بعد النقل</label>
    </div>
    <div style="margin-top:10px"><button class="btn" id="orgPrev">👁 معاينة</button></div>
    <div id="orgRes" style="margin-top:10px"></div>`,
    buttons: [{ text: "✓ تنفيذ التنظيم", cls: "primary", onClick: () => go(true) }, { text: "إغلاق" }],
  });
  const showHide = () => {
    const mode = $("[name=mode]", m.el).value;
    $$("[data-show]", m.el).forEach(el => el.hidden = !el.dataset.show.split(" ").includes(mode));
  };
  $("[name=mode]", m.el).onchange = showHide; showHide();
  $("#orgDest", m.el).onclick = async () => { const d = await pickFolder("وجهة التنظيم"); if (d) $("[name=dest]", m.el).value = d; };
  $("#orgPrev", m.el).onclick = () => go(false);
  async function go(apply) {
    const v = formVals(m.body);
    const t = targetPayload(m.body);
    const data = { ...v, folder: S.cwd, recursive: t.recursive, paths: t.paths, apply };
    if (!v.dest) delete data.dest;
    if (apply) {
      runJob("/api/organize", data, () => { m.close(); refresh(); });
      return;
    }
    try {
      const r = await api("/api/organize", data);
      $("#orgRes", m.el).innerHTML = `<div class="hint">سيتم ${v.copy ? "نسخ" : "نقل"} <b>${r.total}</b> ملف إلى <b>${r.groups.length}</b> مجلد:</div>
        <table class="list-table"><tr><th>المجلد</th><th>العدد</th><th>أمثلة</th></tr>${r.groups.map(g => `<tr><td>${esc(g.folder)}</td><td>${g.count}</td><td class="muted">${esc(g.sample.join("، "))}</td></tr>`).join("")}</table>`;
    } catch (e) { errToast(e); }
  }
}

// ============================================================================
// أداة: تحليل المساحة
// ============================================================================
function bars(items, total, opts = {}) {
  const max = Math.max(...items.map(x => x.size), 1);
  return `<div class="bars">${items.map(x => `<div class="bar-row">
    <div class="lbl" title="${esc(x.name)}">${opts.link && x.path ? `<button class="link" data-nav="${esc(x.path)}">${esc(x.name)}</button>` : esc(x.name)}${x.count != null ? ` <span class="muted">(${x.count})</span>` : ""}</div>
    <div class="b"><div style="width:${(x.size / max * 100).toFixed(1)}%"></div></div>
    <div class="muted">${fmtSize(x.size)}${total ? ` · ${(x.size / total * 100).toFixed(1)}%` : ""}</div></div>`).join("")}</div>`;
}
function toolAnalyze(folder = S.cwd) {
  if (!folder) return toast("افتح مجلداً أولاً");
  const m = modal({ title: "📊 تحليل المساحة: " + folder, size: "wide", body: `<div class="empty">⏳ جارٍ التحليل... (قد يستغرق وقتاً للمجلدات الكبيرة)</div>` });
  runJob("/api/analyze", { folder, top: 200 }, j => {
    const r = j.result;
    if (!r) { m.body.innerHTML = `<div class="empty">${esc(j.message || "فشل")}</div>`; return; }
    m.body.innerHTML = `
      <div class="stat-cards">
        <div class="stat-card"><span>الحجم الكلي</span><b>${fmtSize(r.total)}</b></div>
        <div class="stat-card"><span>عدد الملفات</span><b>${r.count.toLocaleString("en")}</b></div>
        <div class="stat-card"><span>أكبر ملف</span><b>${r.largest[0] ? fmtSize(r.largest[0].size) : "—"}</b></div>
        <div class="stat-card"><span>متوسط الحجم</span><b>${fmtSize(r.count ? r.total / r.count : 0)}</b></div>
      </div>
      <div class="tabs" id="anTabs">
        <button data-t="largest" class="active">🔝 الأكبر حجماً</button><button data-t="children">📁 المجلدات</button>
        <button data-t="cats">🧩 الأنواع</button><button data-t="exts">🏷 الامتدادات</button><button data-t="years">📅 السنوات</button>
      </div>
      <div data-p="largest">
        <div class="row" style="margin-bottom:6px"><button class="btn" id="anSelAll">تحديد الكل</button><button class="btn danger" id="anDel">🗑 حذف المحدد</button><span class="hint">اضغط على المسار لفتح مكان الملف</span></div>
        <div style="max-height:50vh;overflow:auto"><table class="list-table"><tr><th></th><th>#</th><th>الملف</th><th>الحجم</th><th>المجلد</th></tr>
        ${r.largest.map((f, i) => `<tr><td><input type="checkbox" value="${esc(f.path)}"></td><td class="muted">${i + 1}</td><td>${esc(f.name)}</td><td><b>${fmtSize(f.size)}</b></td><td><button class="link" data-nav="${esc(f.path.split("/").slice(0, -1).join("/"))}">${esc(f.path.split("/").slice(0, -1).join("/"))}</button></td></tr>`).join("")}</table></div>
      </div>
      <div data-p="children" hidden>${bars(r.children, r.total, { link: true })}<div class="hint">اضغط على اسم المجلد لتحليله</div></div>
      <div data-p="cats" hidden>${bars(r.cats, r.total)}</div>
      <div data-p="exts" hidden>${bars(r.exts, r.total)}</div>
      <div data-p="years" hidden>${bars(r.years, r.total)}
        ${r.oldest ? `<div class="hint">أقدم ملف: ${esc(r.oldest.path)} (${fmtDate(r.oldest.time)})<br>أحدث ملف: ${esc(r.newest.path)} (${fmtDate(r.newest.time)})</div>` : ""}</div>`;
    tabs(m.body);
    m.body.addEventListener("click", e => {
      const nav = e.target.dataset.nav;
      if (nav !== undefined) {
        if (e.target.closest("[data-p=children]")) { m.close(); toolAnalyze(nav); }
        else { m.close(); navigate(nav); }
      }
    });
    $("#anSelAll", m.el).onclick = () => $$("[data-p=largest] input[type=checkbox]", m.el).forEach(c => c.checked = true);
    $("#anDel", m.el).onclick = async () => {
      const paths = $$("[data-p=largest] input:checked", m.el).map(c => c.value);
      if (!paths.length) return toast("حدد ملفات أولاً");
      if (!await confirm2("حذف", `نقل ${paths.length} ملف إلى السلة؟`, "حذف", true)) return;
      try { await api("/api/delete", { paths }); toast("تم الحذف", "ok"); $$("[data-p=largest] input:checked", m.el).forEach(c => c.closest("tr").remove()); refresh(); } catch (er) { errToast(er); }
    };
  });
}
function tabs(root) {
  const tb = $(".tabs", root);
  tb.onclick = e => {
    const t = e.target.dataset.t;
    if (!t) return;
    $$("button", tb).forEach(b => b.classList.toggle("active", b.dataset.t === t));
    $$("[data-p]", root).forEach(p => p.hidden = p.dataset.p !== t);
    root.dispatchEvent(new CustomEvent("tab", { detail: t }));
  };
}

// ============================================================================
// أداة: البحث المتقدم
// ============================================================================
function toolSearch() {
  if (!needFolder()) return;
  const m = modal({
    title: "🔎 بحث متقدم في: " + S.cwd, size: "wide",
    body: `<div class="form-grid">
      <label class="field"><span>الاسم يحتوي (كلمات)</span><input type="text" name="q"></label>
      <label class="check"><input type="checkbox" name="regex"> تعبير نمطي</label>
      <label class="field"><span>النوع</span><select name="cat"><option value="">الكل</option><option>صور</option><option>فيديو</option><option>صوتيات</option><option>مستندات</option><option>مضغوطات</option><option>برامج</option><option>برمجة</option><option>أخرى</option></select></label>
      <label class="field"><span>الامتدادات (مثال: pdf,docx)</span><input type="text" name="ext"></label>
      <label class="field"><span>الحجم الأدنى (مثال 10M، 500K)</span><input type="text" name="min_size"></label>
      <label class="field"><span>الحجم الأقصى</span><input type="text" name="max_size"></label>
      <label class="field"><span>معدّل بعد تاريخ</span><input type="date" name="after"></label>
      <label class="field"><span>معدّل قبل تاريخ</span><input type="date" name="before"></label>
      <label class="field"><span>يحتوي نص داخل الملف (للملفات النصية)</span><input type="text" name="content"></label>
      <label class="check"><input type="checkbox" name="dirs"> تضمين المجلدات</label>
      <label class="check"><input type="checkbox" name="hidden"> تضمين المخفية</label>
    </div>
    <div class="row" style="margin-top:10px"><button class="btn primary" id="sGo">🔎 بحث</button><span class="hint" id="sStat"></span></div>
    <div id="sRes" style="margin-top:8px"></div>`,
    buttons: [{ text: "إغلاق" }],
  });
  m.body.addEventListener("keydown", e => { if (e.key === "Enter") $("#sGo", m.el).click(); });
  let results = [];
  $("#sGo", m.el).onclick = async () => {
    $("#sStat", m.el).textContent = "جارٍ البحث...";
    try {
      const r = await api("/api/search", { ...formVals(m.body), folder: S.cwd });
      results = r.results;
      const total = results.reduce((a, x) => a + x.size, 0);
      $("#sStat", m.el).textContent = `${results.length} نتيجة · ${fmtSize(total)}` + (r.truncated ? " (تم الاقتصار على أول النتائج)" : "");
      $("#sRes", m.el).innerHTML = results.length ? `<div class="row" style="margin-bottom:6px"><button class="btn" data-a="all">تحديد الكل</button><button class="btn" data-a="dl">⬇ تنزيل المحدد</button><button class="btn" data-a="move">📂 نقل المحدد إلى...</button><button class="btn" data-a="copy">📑 نسخ المحدد إلى...</button><button class="btn danger" data-a="del">🗑 حذف المحدد</button></div>
        <div style="max-height:45vh;overflow:auto"><table class="list-table"><tr><th></th><th>الاسم</th><th>الحجم</th><th>التاريخ</th><th>المكان</th></tr>
        ${results.map((x, i) => `<tr><td><input type="checkbox" value="${i}"></td><td>${iconOf(x)} <button class="link" data-open="${i}">${esc(x.name)}</button></td><td>${x.is_dir ? "" : fmtSize(x.size)}</td><td class="muted">${fmtDate(x.mtime)}</td><td><button class="link" data-nav="${esc(x.dir)}">${esc(x.dir)}</button></td></tr>`).join("")}</table></div>` : `<div class="empty">لا توجد نتائج</div>`;
    } catch (e) { $("#sStat", m.el).textContent = ""; errToast(e); }
  };
  $("#sRes", m.el).onclick = async e => {
    const t = e.target;
    if (t.dataset.nav !== undefined) { m.close(); navigate(t.dataset.nav); return; }
    if (t.dataset.open !== undefined) { openEntry(results[t.dataset.open]); return; }
    const a = t.dataset.a;
    if (!a) return;
    const checked = $$("#sRes input:checked", m.el).map(c => results[c.value].path);
    if (a === "all") return $$("#sRes input[type=checkbox]", m.el).forEach(c => c.checked = true);
    if (!checked.length) return toast("حدد نتائج أولاً");
    if (a === "dl") { S.sel = new Set(checked); S.entries = S.entries.concat(results.filter(x => checked.includes(x.path) && !S.entries.some(y => y.path === x.path))); downloadSelected(); return; }
    if (a === "del") {
      if (!await confirm2("حذف", `نقل ${checked.length} عنصر إلى السلة؟`, "حذف", true)) return;
      try { await api("/api/delete", { paths: checked }); toast("تم الحذف", "ok"); $("#sGo", m.el).click(); refresh(); } catch (er) { errToast(er); }
    }
    if (a === "move" || a === "copy") {
      const dest = await pickFolder(a === "move" ? "نقل إلى" : "نسخ إلى");
      if (!dest) return;
      try { await api(a === "move" ? "/api/move" : "/api/copy", { paths: checked, dest }); toast("تم", "ok"); $("#sGo", m.el).click(); refresh(); } catch (er) { errToast(er); }
    }
  };
}

// ============================================================================
// أداة: الملفات المكررة
// ============================================================================
function toolDuplicates() {
  if (!needFolder()) return;
  const m = modal({
    title: "👯 البحث عن الملفات المكررة في: " + S.cwd, size: "wide",
    body: `<div class="row">
      <label class="field"><span>طريقة المقارنة</span><select name="mode"><option value="content">المحتوى (دقيق — بصمة الملف)</option><option value="name">الاسم فقط (سريع)</option></select></label>
      <label class="field"><span>تجاهل الملفات الأصغر من</span><input type="text" name="min_size" value="1K" style="width:90px"></label>
      <button class="btn primary" id="dGo" style="align-self:flex-end">🔎 ابحث</button></div>
      <div id="dRes" style="margin-top:12px"></div>`,
    buttons: [{ text: "إغلاق" }],
  });
  $("#dGo", m.el).onclick = () => {
    $("#dRes", m.el).innerHTML = `<div class="empty">⏳ جارٍ الفحص...</div>`;
    runJob("/api/duplicates", { ...formVals(m.body), folder: S.cwd }, j => {
      const r = j.result;
      if (!r) return;
      if (!r.groups.length) { $("#dRes", m.el).innerHTML = `<div class="empty">🎉 لا توجد ملفات مكررة</div>`; return; }
      $("#dRes", m.el).innerHTML = `<div class="hint"><b>${r.groups.length}</b> مجموعة مكررة · مساحة مهدرة: <b>${fmtSize(r.wasted)}</b></div>
        <div class="row" style="margin-bottom:8px"><button class="btn" data-a="old">تحديد الكل ماعدا الأقدم</button><button class="btn" data-a="new">تحديد الكل ماعدا الأحدث</button><button class="btn" data-a="none">إلغاء التحديد</button><button class="btn danger" data-a="del">🗑 حذف المحدد</button></div>
        <div style="max-height:50vh;overflow:auto">${r.groups.map((g, gi) => `<div class="dup-group"><div class="head">${g.files.length} نسخ · ${fmtSize(g.size)} لكل ملف</div>
        ${g.files.map((f, fi) => `<label><input type="checkbox" data-g="${gi}" data-f="${fi}" value="${esc(f.path)}" ${fi > 0 ? "checked" : ""}> ${esc(f.name)} <span class="muted">— ${esc(f.dir)} · ${fmtDate(f.mtime)}</span></label>`).join("")}</div>`).join("")}</div>`;
      $("#dRes", m.el).onclick = async e => {
        const a = e.target.dataset.a;
        if (!a) return;
        const boxes = $$("#dRes input[type=checkbox]", m.el);
        if (a === "none") boxes.forEach(b => b.checked = false);
        if (a === "old") boxes.forEach(b => b.checked = b.dataset.f !== "0");
        if (a === "new") boxes.forEach(b => b.checked = +b.dataset.f !== r.groups[b.dataset.g].files.length - 1);
        if (a === "del") {
          const paths = boxes.filter(b => b.checked).map(b => b.value);
          if (!paths.length) return toast("لا يوجد تحديد");
          const groupsAll = r.groups.some((g, gi) => g.files.every((_, fi) => boxes.find(b => b.dataset.g == gi && b.dataset.f == fi).checked));
          if (groupsAll && !await confirm2("تنبيه", "يوجد مجموعة تم تحديد كل نسخها! سيتم حذف جميع النسخ. متابعة؟", "متابعة", true)) return;
          if (!await confirm2("حذف المكررات", `نقل ${paths.length} ملف إلى السلة؟`, "حذف", true)) return;
          try { await api("/api/delete", { paths }); toast(`تم حذف ${paths.length} ملف مكرر`, "ok"); $("#dGo", m.el).click(); refresh(); } catch (er) { errToast(er); }
        }
      };
    });
  };
}

// ============================================================================
// أداة: الصور
// ============================================================================
const IMG_PRESETS = {
  "واتساب / مشاركة": { resize: "long", width: 1600, quality: 80, format: "jpg" },
  "ويب (WebP)": { resize: "long", width: 1920, quality: 80, format: "webp" },
  "صورة مصغرة 300px": { resize: "crop", width: 300, height: 300, quality: 85, format: "jpg" },
  "تقليل 50%": { resize: "percent", percent: 50, quality: 85, format: "keep" },
  "حجم أقل من 200KB": { resize: "long", width: 2048, target_kb: 200, format: "jpg" },
  "ضغط فقط بدون تغيير الأبعاد": { resize: "none", quality: 75, format: "keep" },
};
function toolImages() {
  if (!needFolder()) return;
  if (!S.info.features.pil) return toast("مكتبة Pillow غير مثبتة على الخادم", "err");
  const m = modal({
    title: "🖼️ تصغير وتحويل الصور", size: "wide",
    body: `${targetHTML({ filter: false })}
    <div class="row" style="margin-bottom:10px"><span class="muted">قوالب جاهزة:</span>${Object.keys(IMG_PRESETS).map(k => `<button class="btn" data-preset="${esc(k)}">${esc(k)}</button>`).join("")}</div>
    <fieldset><legend>الأبعاد</legend><div class="form-grid">
      <label class="field"><span>طريقة التصغير</span><select name="resize">
        <option value="long">تحديد الضلع الأطول</option><option value="fit">احتواء داخل عرض×ارتفاع</option><option value="percent">نسبة مئوية</option>
        <option value="exact">أبعاد محددة (قد يتمدد)</option><option value="crop">قص وتوسيط لأبعاد محددة</option><option value="none">بدون تغيير الأبعاد</option></select></label>
      <label class="field" data-r="long fit exact crop"><span data-lbl>العرض (px)</span><input type="number" name="width" value="1920"></label>
      <label class="field" data-r="fit exact crop"><span>الارتفاع (px)</span><input type="number" name="height" value="1080"></label>
      <label class="field" data-r="percent"><span>النسبة %</span><input type="number" name="percent" value="50"></label>
    </div></fieldset>
    <fieldset><legend>الصيغة والجودة</legend><div class="form-grid">
      <label class="field"><span>الصيغة</span><select name="format"><option value="keep">نفس الصيغة</option><option value="jpg">JPG</option><option value="webp">WebP (أصغر)</option><option value="png">PNG</option><option value="bmp">BMP</option><option value="gif">GIF</option><option value="tiff">TIFF</option><option value="ico">ICO (أيقونة)</option></select></label>
      <label class="field"><span>الجودة: <b id="qv">85</b></span><input type="range" name="quality" min="10" max="100" value="85"></label>
      <label class="field"><span>حجم مستهدف (ك.ب) — اختياري</span><input type="number" name="target_kb" placeholder="مثال: 300"></label>
      <label class="check"><input type="checkbox" name="keep_exif"> الاحتفاظ بالبيانات الوصفية (EXIF)</label>
      <label class="check"><input type="checkbox" name="skip_bigger" checked> تخطي الصور التي لن يقل حجمها</label>
    </div></fieldset>
    <fieldset><legend>تعديلات</legend><div class="form-grid">
      <label class="field"><span>تدوير</span><select name="rotate"><option value="0">بدون</option><option value="90">90° يمين</option><option value="-90">90° يسار</option><option value="180">180°</option></select></label>
      <label class="check"><input type="checkbox" name="flip_h"> قلب أفقي</label>
      <label class="check"><input type="checkbox" name="flip_v"> قلب عمودي</label>
      <label class="check"><input type="checkbox" name="grayscale"> أبيض وأسود</label>
      <label class="check"><input type="checkbox" name="autocontrast"> تحسين التباين تلقائياً</label>
      <label class="field"><span>علامة مائية (نص)</span><input type="text" name="watermark" placeholder="© اسمك"></label>
      <label class="field"><span>موضع العلامة</span><select name="wm_pos"><option value="br">أسفل يمين</option><option value="bl">أسفل يسار</option><option value="tr">أعلى يمين</option><option value="tl">أعلى يسار</option><option value="c">المنتصف</option></select></label>
      <label class="field"><span>حجم العلامة %</span><input type="number" name="wm_size" value="5"></label>
      <label class="field"><span>شفافية العلامة %</span><input type="number" name="wm_opacity" value="60"></label>
    </div></fieldset>
    <fieldset><legend>الحفظ</legend><div class="form-grid">
      <label class="field"><span>مكان الحفظ</span><select name="output"><option value="folder">في مجلد فرعي جديد</option><option value="suffix">بجانب الأصل مع لاحقة</option><option value="replace">استبدال الأصل (الأصل يذهب للسلة)</option></select></label>
      <label class="field" data-o="folder"><span>اسم المجلد</span><input type="text" name="folder_name" value="مصغرة"></label>
      <label class="field" data-o="suffix"><span>اللاحقة</span><input type="text" name="suffix" value="_مصغر"></label>
    </div></fieldset>
    <div id="imgRes"></div>`,
    buttons: [
      { text: "✓ تنفيذ", cls: "primary", onClick: () => {
        const opts = formVals(m.body);
        runJob("/api/images", { ...targetPayload(m.body), options: opts }, j => {
          if (j.result) $("#imgRes", m.el).innerHTML = `<div class="hint">✓ ${sizeReport(j.result)} · ${j.result.items.length} صورة${j.result.items.some(x => x.error) ? ` · أخطاء: ${j.result.items.filter(x => x.error).length}` : ""}</div>`;
          refresh();
        });
      } },
      { text: "📕 دمج الصور في PDF", onClick: async () => {
        const name = await prompt2("صور إلى PDF", "صور.pdf", "اسم ملف PDF", true);
        if (name) runJob("/api/images_to_pdf", { ...targetPayload(m.body), name, max_side: 2000 }, refresh);
      } },
      { text: "🧱 لوحة صور مجمعة", onClick: () => runJob("/api/contact_sheet", { ...targetPayload(m.body), cols: 5, cell: 300 }, j => { refresh(); if (j.result) toast("تم إنشاء: " + j.result.file, "ok"); }) },
      { text: "إغلاق" },
    ],
  });
  const upd = () => {
    const v = formVals(m.body);
    $$("[data-r]", m.el).forEach(el => el.hidden = !el.dataset.r.split(" ").includes(v.resize));
    $$("[data-o]", m.el).forEach(el => el.hidden = el.dataset.o !== v.output);
    $("[data-r] [data-lbl]", m.el).textContent = v.resize === "long" ? "الضلع الأطول (px)" : "العرض (px)";
    $("#qv", m.el).textContent = v.quality;
  };
  m.body.addEventListener("input", upd); m.body.addEventListener("change", upd); upd();
  $$("[data-preset]", m.el).forEach(b => b.onclick = () => { setVals(m.body, { target_kb: "", ...IMG_PRESETS[b.dataset.preset] }); upd(); toast("تم تطبيق القالب: " + b.dataset.preset); });
}

// ============================================================================
// أداة: الفيديو
// ============================================================================
const VID_PRESETS = {
  "واتساب 720p": { mode: "compress", height: "720", crf: 28, codec: "h264", preset: "fast", audio_bitrate: "96k" },
  "جودة عالية 1080p": { mode: "compress", height: "1080", crf: 23, codec: "h264", preset: "medium", audio_bitrate: "160k" },
  "صغير جداً 480p": { mode: "compress", height: "480", crf: 30, codec: "h264", preset: "fast", audio_bitrate: "64k" },
  "H.265 توفير كبير": { mode: "compress", height: "", crf: 28, codec: "h265", preset: "medium", audio_bitrate: "128k" },
  "استخراج MP3": { mode: "audio", audio_format: "mp3", audio_bitrate: "192k" },
  "تحويل إلى GIF": { mode: "gif", gif_width: 480, fps: 10 },
};
function toolVideos() {
  if (!needFolder()) return;
  if (!S.info.features.ffmpeg) return modal({ title: "ffmpeg غير مثبت", size: "small", body: `<p>أدوات الفيديو تحتاج برنامج <b>ffmpeg</b>.</p><p class="hint">ويندوز: <code>winget install ffmpeg</code><br>لينكس: <code>sudo apt install ffmpeg</code><br>ماك: <code>brew install ffmpeg</code><br>ثم أعد تشغيل البرنامج.</p>` });
  const m = modal({
    title: "🎬 ضغط وتحويل الفيديو", size: "wide",
    body: `${targetHTML()}
    <div class="row" style="margin-bottom:10px"><span class="muted">قوالب جاهزة:</span>${Object.keys(VID_PRESETS).map(k => `<button class="btn" data-preset="${esc(k)}">${esc(k)}</button>`).join("")}</div>
    <div class="form-grid">
      <label class="field"><span>العملية</span><select name="mode">
        <option value="compress">ضغط / تصغير الحجم</option><option value="convert">تحويل إلى MP4</option><option value="audio">استخراج الصوت</option>
        <option value="gif">تحويل إلى GIF متحرك</option><option value="frames">استخراج لقطات (صور)</option><option value="mute">إزالة الصوت</option><option value="cut">قص مقطع (سريع بدون إعادة ترميز)</option></select></label>
      <label class="field" data-m="compress convert"><span>الدقة (الارتفاع الأقصى)</span><select name="height"><option value="">الأصلية</option><option value="2160">4K (2160p)</option><option value="1440">1440p</option><option value="1080">1080p</option><option value="720">720p</option><option value="480">480p</option><option value="360">360p</option><option value="240">240p</option></select></label>
      <label class="field" data-m="compress convert"><span>الجودة CRF: <b id="crfv">26</b> (أقل = أفضل وأكبر)</span><input type="range" name="crf" min="16" max="40" value="26"></label>
      <label class="field" data-m="compress convert"><span>الترميز</span><select name="codec"><option value="h264">H.264 (متوافق مع كل الأجهزة)</option><option value="h265">H.265/HEVC (أصغر بـ 40%)</option></select></label>
      <label class="field" data-m="compress convert"><span>السرعة</span><select name="preset"><option value="ultrafast">سريع جداً (حجم أكبر)</option><option value="fast">سريع</option><option value="medium" selected>متوسط</option><option value="slow">بطيء (حجم أصغر)</option></select></label>
      <label class="field" data-m="compress convert audio"><span>جودة الصوت</span><select name="audio_bitrate"><option value="64k">64k</option><option value="96k">96k</option><option value="128k" selected>128k</option><option value="160k">160k</option><option value="192k">192k</option><option value="320k">320k</option></select></label>
      <label class="check" data-m="compress convert"><input type="checkbox" name="remove_audio"> بدون صوت</label>
      <label class="field" data-m="compress convert gif"><span>إطارات/ثانية (اختياري)</span><input type="number" name="fps" placeholder="مثال 30"></label>
      <label class="field" data-m="compress convert"><span>تدوير</span><select name="rotate"><option value="">بدون</option><option value="90">90° يمين</option><option value="-90">90° يسار</option><option value="180">180°</option></select></label>
      <label class="field" data-m="compress convert"><span>السرعة (تسريع/إبطاء)</span><select name="speed"><option value="1">عادي</option><option value="0.5">0.5x</option><option value="1.25">1.25x</option><option value="1.5">1.5x</option><option value="2">2x</option></select></label>
      <label class="field" data-m="audio"><span>صيغة الصوت</span><select name="audio_format"><option value="mp3">MP3</option><option value="m4a">M4A (AAC)</option><option value="wav">WAV</option></select></label>
      <label class="field" data-m="gif"><span>عرض GIF (px)</span><input type="number" name="gif_width" value="480"></label>
      <label class="field" data-m="frames"><span>لقطة كل (ثانية)</span><input type="number" name="every" value="10"></label>
      <label class="field"><span>من الثانية (اختياري، أو 00:01:30)</span><input type="text" name="start"></label>
      <label class="field"><span>إلى الثانية (اختياري)</span><input type="text" name="end"></label>
      <label class="field" data-m="compress convert audio gif mute cut"><span>مكان الحفظ</span><select name="output"><option value="folder">مجلد فرعي جديد</option><option value="suffix">بجانب الأصل مع لاحقة</option><option value="replace">استبدال الأصل (الأصل يذهب للسلة)</option></select></label>
      <label class="field" data-o="folder"><span>اسم المجلد</span><input type="text" name="folder_name" placeholder="مضغوطة"></label>
      <label class="field" data-o="suffix"><span>اللاحقة</span><input type="text" name="suffix" placeholder="_مضغوط"></label>
    </div>
    <div class="hint">💡 الضغط يستغرق وقتاً حسب طول الفيديو وقوة الجهاز. تابع التقدم من لوحة المهام ⏳ — يمكنك إغلاق هذه النافذة.</div>
    <div id="vidRes"></div>`,
    buttons: [
      { text: "✓ تنفيذ", cls: "primary", onClick: () => {
        runJob("/api/videos", { ...targetPayload(m.body), options: formVals(m.body) }, j => {
          if (j.result && document.body.contains(m.el)) $("#vidRes", m.el).innerHTML = `<div class="hint">✓ ${sizeReport(j.result)}</div>`;
          refresh();
        });
      } },
      { text: "🔗 دمج الفيديوهات المحددة", onClick: async () => {
        const name = await prompt2("دمج فيديوهات", "مدمج.mp4", "اسم الملف الناتج (الترتيب حسب الاسم)", true);
        if (name) runJob("/api/video_merge", { ...targetPayload(m.body), name }, refresh);
      } },
      { text: "إغلاق" },
    ],
  });
  const upd = () => {
    const v = formVals(m.body);
    $$("[data-m]", m.el).forEach(el => el.hidden = !el.dataset.m.split(" ").includes(v.mode));
    $$("[data-o]", m.el).forEach(el => el.hidden = el.dataset.o !== v.output || ["frames"].includes(v.mode));
    $("#crfv", m.el).textContent = v.crf;
  };
  m.body.addEventListener("input", upd); m.body.addEventListener("change", upd); upd();
  $$("[data-preset]", m.el).forEach(b => b.onclick = () => { setVals(m.body, VID_PRESETS[b.dataset.preset]); upd(); toast("تم تطبيق القالب: " + b.dataset.preset); });
}

// ============================================================================
// أداة: البيانات الوصفية
// ============================================================================
const META_LABELS = {
  artist: "الفنان / المصور", copyright: "حقوق النشر", description: "الوصف", software: "البرنامج", make: "الشركة المصنعة", model: "طراز الكاميرا",
  comment: "تعليق", datetime: "تاريخ التصوير (YYYY:MM:DD HH:MM:SS)", title: "العنوان", album: "الألبوم", albumartist: "فنان الألبوم",
  date: "السنة / التاريخ", genre: "النوع", tracknumber: "رقم المقطع", composer: "الملحن", author: "المؤلف", subject: "الموضوع",
  keywords: "الكلمات المفتاحية / الوسوم", creator: "أداة الإنشاء",
};
async function toolMeta() {
  if (!S.sel.size) return toast("حدد ملفاً أو أكثر أولاً (صور، صوتيات، فيديو، PDF)");
  const paths = selPaths().filter(p => !S.entries.find(e => e.path === p)?.is_dir);
  if (!paths.length) return toast("حدد ملفات وليس مجلدات");
  let meta;
  try { meta = await api(`/api/meta?path=${enc(paths[0])}`); } catch (e) { return errToast(e); }
  if (meta.kind === "other") return toast("هذا النوع لا يدعم البيانات الوصفية (المدعوم: صور، صوتيات، فيديو، PDF)", "err");
  const multi = paths.length > 1;
  const kindAr = { image: "صورة", audio: "ملف صوتي", video: "فيديو", pdf: "PDF" }[meta.kind];
  const m = modal({
    title: `🏷️ البيانات الوصفية — ${multi ? paths.length + " ملف" : paths[0].split("/").pop()}`, size: "wide",
    body: `<div class="tabs"><button data-t="edit" class="active">✏️ تعديل</button><button data-t="raw">📋 كل البيانات</button></div>
    <div data-p="edit">
      ${multi ? `<div class="hint">النوع حسب أول ملف: <b>${kindAr}</b>. في التعديل الجماعي يتم كتابة الحقول المعبأة فقط. يمكنك استخدام <code>{name}</code> اسم الملف · <code>{n}</code> الترقيم · <code>{parent}</code> اسم المجلد.</div>` : `<div class="hint">النوع: <b>${kindAr}</b> · امسح الحقل لحذف قيمته. المتغيرات: <code>{name}</code> <code>{parent}</code></div>`}
      <div class="form-grid">${Object.entries(meta.fields).map(([k, v]) => `<label class="field"><span>${META_LABELS[k] || k}</span><input type="text" name="${k}" value="${multi ? "" : esc(v)}" placeholder="${multi ? esc(v) : ""}"></label>`).join("")}</div>
      <label class="check" style="margin-top:10px"><input type="checkbox" id="keepMtime" checked> الحفاظ على تاريخ تعديل الملف الأصلي</label>
      ${meta.gps ? `<div class="hint">📍 الموقع الجغرافي: ${meta.gps.lat.toFixed(5)}, ${meta.gps.lon.toFixed(5)} — <a href="https://maps.google.com/?q=${meta.gps.lat},${meta.gps.lon}" target="_blank">فتح في الخريطة</a> (احذف البيانات لإزالة الموقع قبل المشاركة)</div>` : ""}
    </div>
    <div data-p="raw" hidden><div class="kv">${Object.entries(meta.raw || {}).map(([k, v]) => `<div>${esc(k)}</div><div>${esc(typeof v === "object" ? JSON.stringify(v) : v)}</div>`).join("") || "لا توجد بيانات"}</div></div>`,
    buttons: [
      { text: "💾 حفظ البيانات", cls: "primary", onClick: () => {
        let fields = formVals($("[data-p=edit]", m.el));
        if (multi) fields = Object.fromEntries(Object.entries(fields).filter(([, v]) => v !== ""));
        if (multi && !Object.keys(fields).length) return toast("املأ حقلاً واحداً على الأقل");
        runJob("/api/meta", { paths, fields, keep_mtime: $("#keepMtime", m.el).checked }, j => { if (j.result?.errors?.length) toast(j.result.errors.slice(0, 3).join("\n"), "err", 8000); m.close(); refresh(); });
      } },
      { text: "🧹 حذف كل البيانات الوصفية (خصوصية)", cls: "danger", onClick: async () => {
        if (!await confirm2("حذف البيانات", `حذف كل البيانات الوصفية (EXIF، الموقع، الكاميرا...) من ${paths.length} ملف؟`, "حذف", true)) return;
        runJob("/api/meta", { paths, strip: true, keep_mtime: true }, () => { m.close(); refresh(); });
      } },
      { text: "إغلاق" },
    ],
  });
  tabs(m.body);
}

function toolDates() {
  if (!needFolder()) return;
  const m = modal({
    title: "📅 تعديل تواريخ الملفات", size: "small",
    body: `${targetHTML()}
      <label class="check"><input type="radio" name="mode" value="exif" checked> من تاريخ التصوير (EXIF) — لإصلاح تواريخ الصور المنسوخة</label>
      <label class="check"><input type="radio" name="mode" value="name"> من الاسم (مثل IMG_20240512_143000.jpg)</label>
      <label class="check"><input type="radio" name="mode" value="manual"> تاريخ محدد:</label>
      <input type="datetime-local" name="value" style="margin-top:6px;width:100%">`,
    buttons: [{ text: "✓ تطبيق", cls: "primary", onClick: async () => {
      try { const r = await api("/api/set_dates", { ...targetPayload(m.body), ...formVals(m.body) }); toast(`تم تعديل ${r.count} ملف` + (r.failed.length ? ` · لم يُعثر على تاريخ لـ ${r.failed.length}` : ""), "ok", 5000); m.close(); refresh(); }
      catch (e) { errToast(e); }
    } }, { text: "إغلاق" }],
  });
}

// ============================================================================
// أداة: النصوص
// ============================================================================
function toolText() {
  if (!needFolder()) return;
  const m = modal({
    title: "📝 أدوات الملفات النصية", size: "wide",
    body: `${targetHTML({ filter: true })}
    <div class="tabs"><button data-t="replace" class="active">بحث واستبدال داخل الملفات</button><button data-t="header">إضافة سطر في البداية</button><button data-t="footer">إضافة سطر في النهاية</button><button data-t="trim">تنظيف الأسطر</button><button data-t="eol">نهايات الأسطر</button><button data-t="encoding">تحويل الترميز</button><button data-t="merge">دمج الملفات</button></div>
    <div data-p="replace"><div class="form-grid"><label class="field"><span>ابحث عن</span><input type="text" name="find"></label><label class="field"><span>استبدل بـ</span><input type="text" name="repl"></label>
      <label class="check"><input type="checkbox" name="regex"> تعبير نمطي</label><label class="check"><input type="checkbox" name="case"> مطابقة حالة الأحرف</label></div></div>
    <div data-p="header" hidden><label class="field"><span>النص (يدعم {name} {n} {date})</span><textarea name="text_h" rows="3"></textarea></label></div>
    <div data-p="footer" hidden><label class="field"><span>النص (يدعم {name} {n} {date})</span><textarea name="text_f" rows="3"></textarea></label></div>
    <div data-p="trim" hidden><label class="check"><input type="checkbox" name="remove_empty"> حذف الأسطر الفارغة</label><label class="check"><input type="checkbox" name="dedupe"> حذف الأسطر المكررة</label><label class="check"><input type="checkbox" name="sort_lines"> ترتيب الأسطر أبجدياً</label><div class="hint">يتم دائماً حذف المسافات الزائدة في نهاية الأسطر.</div></div>
    <div data-p="eol" hidden><label class="field"><span>التحويل إلى</span><select name="eol"><option value="lf">LF (لينكس/ماك)</option><option value="crlf">CRLF (ويندوز)</option></select></label></div>
    <div data-p="encoding" hidden><label class="field"><span>تحويل الترميز إلى</span><select name="to"><option value="utf-8">UTF-8</option><option value="cp1256">Windows-1256 (عربي قديم)</option></select></label><div class="hint">يتم اكتشاف الترميز الحالي تلقائياً — مفيد لإصلاح ملفات الترجمة SRT التي تظهر رموزاً غريبة.</div></div>
    <div data-p="merge" hidden><div class="form-grid"><label class="field"><span>اسم الملف الناتج</span><input type="text" name="name" value="مدمج.txt"></label><label class="field"><span>عنوان قبل كل ملف (يدعم {name})</span><input type="text" name="header" value="===== {name} ====="></label></div></div>
    <div class="row" style="margin-top:10px"><button class="btn" id="tPrev">👁 معاينة</button></div>
    <div id="tRes" class="hint"></div>`,
    buttons: [{ text: "✓ تطبيق", cls: "primary", onClick: () => go(true) }, { text: "إغلاق" }],
  });
  let op = "replace";
  tabs(m.body);
  m.body.addEventListener("tab", e => { op = e.detail; $("#tRes", m.el).innerHTML = ""; });
  $("#tPrev", m.el).onclick = () => go(false);
  async function go(apply) {
    const v = formVals(m.body);
    const data = { ...targetPayload(m.body), ...v, op, apply, text: op === "header" ? v.text_h : v.text_f };
    try {
      const r = await api("/api/text_tools", data);
      if (op === "merge") {
        if (apply) { toast("تم إنشاء " + r.file, "ok"); refresh(); }
        else $("#tRes", m.el).innerHTML = `<div class="log-box">${esc(r.preview)}</div>`;
        return;
      }
      $("#tRes", m.el).innerHTML = `${apply ? "✓ تم التعديل" : "سيتم تعديل"}: <b>${r.changed}</b> من ${r.items.length} ملف<br>${r.items.filter(x => x.changed).slice(0, 50).map(x => esc(x.name) + (x.count != null ? ` (${x.count} تطابق)` : "")).join("، ")}`;
      if (apply) refresh();
    } catch (e) { errToast(e); }
  }
}

// ============================================================================
// أداة: PDF
// ============================================================================
function toolPdf() {
  if (!needFolder()) return;
  if (!S.info.features.pypdf) return toast("مكتبة pypdf غير مثبتة على الخادم", "err");
  const m = modal({
    title: "📕 أدوات PDF", size: "",
    body: `${targetHTML()}
    <div class="form-grid">
      <label class="field"><span>العملية</span><select name="op">
        <option value="merge">دمج عدة ملفات في ملف واحد</option><option value="split">تقسيم كل صفحة لملف</option><option value="extract">استخراج صفحات محددة</option>
        <option value="remove">حذف صفحات محددة</option><option value="rotate">تدوير الصفحات</option><option value="compress">ضغط (تقليل الحجم)</option>
        <option value="protect">حماية بكلمة مرور</option><option value="text">استخراج النص إلى TXT</option></select></label>
      <label class="field" data-op="merge"><span>اسم الملف الناتج</span><input type="text" name="name" value="مدمج.pdf"></label>
      <label class="field" data-op="extract remove rotate"><span>الصفحات (مثال: 1-3, 5, 8-)</span><input type="text" name="pages"></label>
      <label class="field" data-op="rotate"><span>الزاوية</span><select name="angle"><option value="90">90° يمين</option><option value="270">90° يسار</option><option value="180">180°</option></select></label>
      <label class="field" data-op="compress"><span>جودة الصور داخل الملف (اختياري 10-95)</span><input type="number" name="images_quality" placeholder="مثال 60"></label>
      <label class="field" data-op="protect"><span>كلمة المرور</span><input type="text" name="password"></label>
      <label class="field" data-op="merge"><span>ترتيب الدمج</span><select name="sort"><option value="name">حسب الاسم</option><option value="mtime">حسب التاريخ</option><option value="none">حسب التحديد</option></select></label>
    </div>`,
    buttons: [{ text: "✓ تنفيذ", cls: "primary", onClick: () => runJob("/api/pdf", { ...targetPayload(m.body), ...formVals(m.body) }, () => refresh()) }, { text: "إغلاق" }],
  });
  const upd = () => { const op = $("[name=op]", m.el).value; $$("[data-op]", m.el).forEach(el => el.hidden = !el.dataset.op.split(" ").includes(op)); };
  $("[name=op]", m.el).onchange = upd; upd();
}

// ============================================================================
// أداة: مقارنة مجلدين
// ============================================================================
function toolCompare() {
  const m = modal({
    title: "⚖️ مقارنة ومزامنة مجلدين", size: "wide",
    body: `<div class="form-grid">
      <label class="field"><span>المجلد (أ)</span><div class="row"><input type="text" name="a" value="${esc(S.cwd)}" readonly style="flex:1"><button class="btn" data-pick="a">اختيار</button></div></label>
      <label class="field"><span>المجلد (ب)</span><div class="row"><input type="text" name="b" readonly style="flex:1"><button class="btn" data-pick="b">اختيار</button></div></label>
      <label class="check"><input type="checkbox" name="deep"> مقارنة دقيقة بالمحتوى (أبطأ)</label></div>
      <div class="row" style="margin-top:8px"><button class="btn primary" id="cGo">⚖️ قارن</button></div><div id="cRes" style="margin-top:10px"></div>`,
    buttons: [{ text: "إغلاق" }],
  });
  $$("[data-pick]", m.el).forEach(b => b.onclick = async () => { const d = await pickFolder("اختر المجلد"); if (d) $(`[name=${b.dataset.pick}]`, m.el).value = d; });
  $("#cGo", m.el).onclick = () => {
    const v = formVals(m.body);
    if (!v.a || !v.b) return toast("اختر المجلدين");
    runJob("/api/compare", v, j => {
      const r = j.result;
      if (!r) return;
      const lst = arr => `<div style="max-height:40vh;overflow:auto"><table class="list-table">${arr.map(x => `<tr><td>${esc(typeof x === "string" ? x : x.name)}</td>${typeof x === "string" ? "" : `<td>${fmtSize(x.a_size)}</td><td>${fmtSize(x.b_size)}</td><td>الأحدث: ${x.newer === "a" ? "أ" : "ب"}</td>`}</tr>`).join("") || "<tr><td class='muted'>لا يوجد</td></tr>"}</table></div>`;
      $("#cRes", m.el).innerHTML = `<div class="stat-cards"><div class="stat-card"><span>متطابقة</span><b>${r.same}</b></div><div class="stat-card"><span>فقط في (أ)</span><b>${r.only_a.length}</b></div><div class="stat-card"><span>فقط في (ب)</span><b>${r.only_b.length}</b></div><div class="stat-card"><span>مختلفة</span><b>${r.diff.length}</b></div></div>
        <div class="row" style="margin-bottom:8px"><button class="btn" data-sync="ab">نسخ الناقص من (أ) إلى (ب)</button><button class="btn" data-sync="ba">نسخ الناقص من (ب) إلى (أ)</button><label class="check"><input type="checkbox" id="syncUpd"> تحديث الملفات الأقدم أيضاً</label></div>
        <div class="tabs"><button data-t="a" class="active">فقط في (أ)</button><button data-t="b">فقط في (ب)</button><button data-t="d">مختلفة</button></div>
        <div data-p="a">${lst(r.only_a)}</div><div data-p="b" hidden>${lst(r.only_b)}</div><div data-p="d" hidden>${lst(r.diff)}</div>`;
      tabs($("#cRes", m.el));
      $$("[data-sync]", m.el).forEach(b => b.onclick = () => {
        const [src, dst] = b.dataset.sync === "ab" ? [r.a, r.b] : [r.b, r.a];
        runJob("/api/sync_copy", { src, dst, update: $("#syncUpd", m.el).checked }, jj => { toast(`تم نسخ ${jj.result?.copied || 0} ملف`, "ok"); $("#cGo", m.el).click(); });
      });
    });
  };
}

// ============================================================================
// أدوات أخرى
// ============================================================================
async function toolEmptyDirs() {
  if (!needFolder()) return;
  try {
    const r = await api("/api/empty_dirs", { folder: S.cwd });
    const m = modal({
      title: "🧹 المجلدات الفارغة في: " + S.cwd,
      body: r.items.length ? `<div class="hint">${r.items.length} مجلد فارغ:</div><div class="log-box">${esc(r.items.join("\n"))}</div>` : `<div class="empty">🎉 لا توجد مجلدات فارغة</div>`,
      buttons: r.items.length ? [{ text: "🗑 حذفها كلها", cls: "danger", onClick: async () => { try { const x = await api("/api/empty_dirs", { folder: S.cwd, delete: true }); toast(`تم حذف ${x.items.length} مجلد`, "ok"); m.close(); refresh(); } catch (e) { errToast(e); } } }, { text: "إغلاق" }] : [{ text: "إغلاق" }],
    });
  } catch (e) { errToast(e); }
}
function toolExportList() {
  if (!needFolder()) return;
  const m = modal({
    title: "📋 تصدير قائمة الملفات", size: "small",
    body: `<p>تصدير قائمة بكل ملفات <b>${esc(S.cwd)}</b> (الاسم، المجلد، النوع، الحجم، التاريخ) كملف CSV يفتح في Excel.</p><label class="check"><input type="checkbox" id="exRec" checked> مع المجلدات الفرعية</label>`,
    buttons: [{ text: "⬇ تصدير", cls: "primary", onClick: () => {
      const f = document.createElement("form");
      f.method = "post"; f.action = "/api/export_list";
      f.innerHTML = `<input type="hidden" name="folder" value="${esc(S.cwd)}"><input type="hidden" name="recursive" value="${$("#exRec", m.el).checked ? 1 : 0}">`;
      document.body.appendChild(f); f.submit(); f.remove(); m.close();
    } }, { text: "إغلاق" }],
  });
}
async function toolTrash() {
  let items;
  try { items = await api("/api/trash"); } catch (e) { return errToast(e); }
  const total = items.reduce((a, x) => a + x.size, 0);
  const m = modal({
    title: `🗑️ سلة المحذوفات (${items.length} · ${fmtSize(total)})`, size: "wide",
    body: items.length ? `<div class="row" style="margin-bottom:6px"><button class="btn" id="tAll">تحديد الكل</button></div><div style="max-height:55vh;overflow:auto"><table class="list-table"><tr><th></th><th>الاسم</th><th>المكان الأصلي</th><th>الحجم</th><th>تاريخ الحذف</th></tr>
      ${items.map(x => `<tr><td><input type="checkbox" value="${esc(x.id)}"></td><td>${x.is_dir ? "📁" : "📄"} ${esc(x.name)}</td><td class="muted">${esc(x.orig)}</td><td>${fmtSize(x.size)}</td><td class="muted">${fmtDate(x.time)}</td></tr>`).join("")}</table></div>` : `<div class="empty">السلة فارغة</div>`,
    buttons: [
      { text: "↩ استرجاع المحدد", cls: "primary", onClick: async () => {
        const ids = $$("input:checked", m.body).map(c => c.value);
        if (!ids.length) return toast("حدد عناصر");
        try { const r = await api("/api/trash/restore", { ids }); toast(`تم استرجاع ${r.count}`, "ok"); m.close(); refresh(); toolTrash(); } catch (e) { errToast(e); }
      } },
      { text: "حذف المحدد نهائياً", onClick: async () => {
        const ids = $$("input:checked", m.body).map(c => c.value);
        if (!ids.length) return toast("حدد عناصر");
        if (!await confirm2("حذف نهائي", `حذف ${ids.length} عنصر نهائياً؟`, "حذف", true)) return;
        try { await api("/api/trash/empty", { ids }); m.close(); toolTrash(); } catch (e) { errToast(e); }
      } },
      { text: "🔥 إفراغ السلة", cls: "danger", onClick: async () => {
        if (!await confirm2("إفراغ السلة", "حذف كل محتويات السلة نهائياً؟", "إفراغ", true)) return;
        try { await api("/api/trash/empty", {}); toast("تم إفراغ السلة", "ok"); m.close(); } catch (e) { errToast(e); }
      } },
      { text: "إغلاق" },
    ],
  });
  const all = $("#tAll", m.el);
  if (all) all.onclick = () => $$("input[type=checkbox]", m.body).forEach(c => c.checked = true);
}
function showInfo() {
  const i = S.info;
  modal({
    title: "📶 الدخول من الأجهزة الأخرى", size: "small",
    body: `<p>افتح أحد هذه الروابط من الجوال أو أي كمبيوتر متصل بنفس الشبكة (الواي فاي):</p>
      ${i.urls.map(u => `<div class="row" style="margin:6px 0"><input type="text" value="${esc(u)}" readonly class="mono" style="flex:1;font-size:16px"><button class="btn" onclick="navigator.clipboard&&navigator.clipboard.writeText('${esc(u)}');this.textContent='✓'">نسخ</button></div>`).join("")}
      <div class="hint">${i.password ? "🔒 البرنامج محمي بكلمة مرور." : "⚠ البرنامج غير محمي بكلمة مرور — شغّله بـ <code>--password</code> لحمايته."}${i.read_only ? "<br>👁 وضع القراءة فقط مفعّل." : ""}</div>
      <div class="hint">المكتبات: الصور ${i.features.pil ? "✓" : "✗"} · الفيديو (ffmpeg) ${i.features.ffmpeg ? "✓" : "✗"} · الصوتيات ${i.features.mutagen ? "✓" : "✗"} · PDF ${i.features.pypdf ? "✓" : "✗"}</div>
      <div class="hint"><b>اختصارات:</b> Ctrl+A تحديد الكل · F2 إعادة تسمية · Delete حذف · Ctrl+C/X/V نسخ/قص/لصق · Ctrl+Z تراجع · Backspace للأعلى · Enter فتح · F5 تحديث</div>
      ${i.password ? `<a class="btn" href="/logout">تسجيل الخروج</a>` : ""}`,
  });
}

const TOOLS = {
  batchRename: toolBatchRename, organize: toolOrganize, analyze: () => toolAnalyze(), search: toolSearch, duplicates: toolDuplicates,
  images: toolImages, videos: toolVideos, meta: toolMeta, dates: toolDates, text: toolText, pdf: toolPdf, compare: toolCompare,
  emptyDirs: toolEmptyDirs, exportList: toolExportList, trash: toolTrash,
};

// ============================================================================
// القائمة اليمنى
// ============================================================================
function showCtx(x, y, onItem) {
  const menu = $("#ctxMenu");
  const items = selItems();
  const one = items.length === 1 ? items[0] : null;
  const list = [];
  if (onItem && items.length) {
    if (one) list.push(["open", "📂 فتح"]);
    list.push(["download", "⬇ تنزيل"]);
    if (one) list.push(["rename", "✏️ إعادة تسمية (F2)"]);
    list.push(["batchRename", "✏️ إعادة تسمية دفعة"], "-", ["copy", "📄 نسخ (Ctrl+C)"], ["cut", "✂️ قص (Ctrl+X)"], ["moveTo", "📂 نقل إلى..."], ["copyTo", "📑 نسخ إلى..."], ["duplicate", "⧉ عمل نسخة هنا"], "-", ["zip", "🗜 ضغط ZIP"]);
    if (one && one.ext === "zip") list.push(["unzip", "📦 فك الضغط"]);
    if (items.some(e => IMG_EXT.has(e.ext))) list.push(["images", "🖼 تصغير/تحويل الصور"]);
    if (items.some(e => VID_EXT.has(e.ext))) list.push(["videos", "🎬 ضغط/تحويل الفيديو"]);
    if (items.some(e => !e.is_dir)) list.push(["meta", "🏷 البيانات الوصفية"]);
    if (one && one.is_dir) list.push(["analyzeDir", "📊 تحليل المساحة"]);
    list.push("-");
    if (one) list.push(["props", "ℹ️ خصائص"]);
    if (one && !one.is_dir) list.push(["hash", "#️⃣ بصمة الملف"]);
    list.push(["delete", "🗑 حذف (Delete)"], ["deletePerm", "⛔ حذف نهائي (Shift+Delete)"]);
  } else {
    if (S.clip) list.push(["paste", `📋 لصق (${S.clip.paths.length})`]);
    list.push(["mkdir", "＋ مجلد جديد"], ["newFile", "＋ ملف نصي جديد"], ["upload", "⬆ رفع ملفات"], "-", ["selectAll", "تحديد الكل"], ["refresh", "⟳ تحديث"], ["analyzeHere", "📊 تحليل هذا المجلد"]);
  }
  menu.innerHTML = list.map(i => i === "-" ? "<hr>" : `<button data-act="${i[0]}">${i[1]}</button>`).join("");
  menu.hidden = false;
  const r = menu.getBoundingClientRect();
  menu.style.left = Math.max(4, Math.min(x - r.width, innerWidth - r.width - 4)) + "px";
  menu.style.top = Math.min(y, innerHeight - r.height - 4) + "px";
}
function doAction(a) {
  const one = selItems()[0];
  const acts = {
    open: () => one && openEntry(one), download: downloadSelected, rename: renameOne, batchRename: toolBatchRename,
    copy: () => clipSet("copy"), cut: () => clipSet("cut"), moveTo: () => transferTo("move"), copyTo: () => transferTo("copy"),
    duplicate: async () => { try { await api("/api/duplicate", { paths: selPaths() }); refresh(); } catch (e) { errToast(e); } },
    zip: zipSelected, unzip: unzipSelected, images: toolImages, videos: toolVideos, meta: toolMeta, organize: toolOrganize,
    props: () => one && showProps(one.path), hash: hashSelected, delete: () => deleteSelected(false), deletePerm: () => deleteSelected(true),
    clear: () => { S.sel.clear(); render(); }, paste, mkdir, newFile, upload: () => $("#fileInput").click(),
    selectAll: () => { S.shown.forEach(e => S.sel.add(e.path)); render(); }, refresh, analyzeHere: () => toolAnalyze(),
    analyzeDir: () => one && toolAnalyze(one.path),
  };
  acts[a] && acts[a]();
}

// ============================================================================
// الأحداث
// ============================================================================
function closeSidebar() { $("#sidebar").classList.remove("open"); }
function bind() {
  $("#menuBtn").onclick = () => $("#sidebar").classList.toggle("open");
  $("#toolsNav").onclick = e => { const t = e.target.closest("[data-tool]"); if (t) { closeSidebar(); TOOLS[t.dataset.tool](); } };
  $("#backBtn").onclick = () => { if (S.history.length) navigate(S.history.pop(), false); };
  $("#upBtn").onclick = () => { if (S.cwd) navigate(S.cwd.includes("/") ? S.cwd.split("/").slice(0, -1).join("/") : ""); };
  $("#refreshBtn").onclick = () => refresh();
  $("#favBtn").onclick = () => {
    if (!S.cwd) return;
    S.favs = S.favs.includes(S.cwd) ? S.favs.filter(f => f !== S.cwd) : [...S.favs, S.cwd];
    store.set("favs", S.favs); renderFavs(); updateFavBtn();
  };
  $("#uploadBtn").onclick = () => $("#fileInput").click();
  $("#uploadDirBtn").onclick = () => $("#dirInput").click();
  $("#fileInput").onchange = e => { uploadFiles([...e.target.files]); e.target.value = ""; };
  $("#dirInput").onchange = e => { uploadFiles([...e.target.files]); e.target.value = ""; };
  $("#mkdirBtn").onclick = mkdir;
  $("#newFileBtn").onclick = newFile;
  $("#pasteBtn").onclick = paste;
  $("#undoBtn").onclick = undo;
  $("#infoBtn").onclick = showInfo;
  $("#themeBtn").onclick = () => {
    const t = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = t; store.set("theme", t);
    $("#themeBtn").textContent = t === "dark" ? "☀️" : "🌙";
  };
  $("#jobsBtn").onclick = () => { const p = $("#jobsPanel"); p.hidden = !p.hidden; S.jobsAuto = false; if (!p.hidden) pollJobs(); };
  $("#jobsClose").onclick = () => $("#jobsPanel").hidden = true;
  $("#jobsClear").onclick = () => api("/api/jobs/clear", {}).then(pollJobs);
  $("#quickFilter").oninput = debounce(render, 120);
  $("#typeFilter").onchange = e => { S.typeFilter = e.target.value; render(); };
  $("#sortSel").onchange = e => { S.sort = e.target.value; store.set("sort", S.sort); render(); };
  $("#sortDirBtn").onclick = () => { S.dir *= -1; store.set("dir", S.dir); render(); };
  $("#hiddenBtn").onclick = () => { S.hidden = !S.hidden; store.set("hidden", S.hidden); refresh(); };
  $("#viewBtn").onclick = () => { S.view = S.view === "list" ? "grid" : "list"; store.set("view", S.view); render(); };
  $$("#filesTable th[data-sort]").forEach(th => th.onclick = () => {
    if (S.sort === th.dataset.sort) S.dir *= -1; else { S.sort = th.dataset.sort; S.dir = 1; }
    store.set("sort", S.sort); store.set("dir", S.dir); render();
  });
  $("#checkAll").onchange = e => { if (e.target.checked) S.shown.forEach(x => S.sel.add(x.path)); else S.sel.clear(); render(); };
  $(".sel-actions").onclick = e => { const a = e.target.closest("[data-act]"); if (a) doAction(a.dataset.act); };
  $("#ctxMenu").onclick = e => { const a = e.target.closest("[data-act]"); if (a) { $("#ctxMenu").hidden = true; doAction(a.dataset.act); } };
  document.addEventListener("click", e => { if (!e.target.closest("#ctxMenu")) $("#ctxMenu").hidden = true; });

  // النقر على الملفات
  const wrap = $("#filesWrap");
  const itemOf = el => { const r = el.closest("[data-i]"); return r ? +r.dataset.i : -1; };
  wrap.addEventListener("click", e => {
    const i = itemOf(e.target);
    if (i < 0) { if (!e.target.closest("thead")) { S.sel.clear(); render(); } return; }
    if (e.target.type === "checkbox") return setSel(i, e.shiftKey ? "range" : "toggle");
    if (e.shiftKey) setSel(i, "range");
    else if (e.ctrlKey || e.metaKey) setSel(i, "toggle");
    else if (matchMedia("(max-width: 800px)").matches && S.sel.size === 0) openEntry(S.shown[i]);
    else setSel(i, "single");
  });
  wrap.addEventListener("dblclick", e => { const i = itemOf(e.target); if (i >= 0 && e.target.type !== "checkbox") openEntry(S.shown[i]); });
  wrap.addEventListener("contextmenu", e => {
    e.preventDefault();
    const i = itemOf(e.target);
    if (i >= 0 && !S.sel.has(S.shown[i].path)) setSel(i, "single");
    if (i < 0) { S.sel.clear(); render(); }
    showCtx(e.clientX, e.clientY, i >= 0);
  });
  // السحب والإفلات
  wrap.addEventListener("dragstart", e => {
    const i = itemOf(e.target);
    if (i < 0) return;
    if (!S.sel.has(S.shown[i].path)) setSel(i, "single");
    e.dataTransfer.setData("application/x-alma", JSON.stringify(selPaths()));
    e.dataTransfer.effectAllowed = "copyMove";
  });
  let dragDepth = 0;
  const main = $("#main");
  main.addEventListener("dragenter", e => { if ([...e.dataTransfer.types].includes("Files")) { dragDepth++; $("#dropOverlay").hidden = false; } });
  main.addEventListener("dragleave", () => { dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) $("#dropOverlay").hidden = true; });
  main.addEventListener("dragover", e => {
    e.preventDefault();
    $$(".drop-target").forEach(x => x.classList.remove("drop-target"));
  });
  main.addEventListener("drop", e => {
    dragDepth = 0;
    const i = itemOf(e.target);
    const target = i >= 0 && S.shown[i].is_dir ? S.shown[i].path : S.cwd;
    if (e.dataTransfer.getData("application/x-alma") && target === S.cwd) { e.preventDefault(); return; }
    dropOnFolder(e, target);
  });

  // لوحة المفاتيح
  document.addEventListener("keydown", e => {
    const typing = e.target.matches("input, textarea, select");
    if (e.key === "Escape") {
      const ms = $$(".modal-back");
      if (ms.length) { $(".x", ms[ms.length - 1]).click(); return; }
      $("#ctxMenu").hidden = true; S.sel.clear(); render(); return;
    }
    if ($$(".modal-back").length || typing) return;
    const ctrl = e.ctrlKey || e.metaKey;
    if (ctrl && e.key === "a") { e.preventDefault(); S.shown.forEach(x => S.sel.add(x.path)); render(); }
    else if (ctrl && e.key === "c") clipSet("copy");
    else if (ctrl && e.key === "x") clipSet("cut");
    else if (ctrl && e.key === "v") paste();
    else if (ctrl && e.key === "z") { e.preventDefault(); undo(); }
    else if (ctrl && e.key === "f") { e.preventDefault(); $("#quickFilter").focus(); }
    else if (e.key === "Delete") deleteSelected(e.shiftKey);
    else if (e.key === "F2") { e.preventDefault(); S.sel.size > 1 ? toolBatchRename() : renameOne(); }
    else if (e.key === "F5") { e.preventDefault(); refresh(); }
    else if (e.key === "Backspace" || (e.altKey && e.key === "ArrowUp")) { e.preventDefault(); $("#upBtn").click(); }
    else if (e.altKey && e.key === "ArrowLeft") $("#backBtn").click();
    else if (e.key === "Enter") { const s = selItems(); if (s.length === 1) openEntry(s[0]); }
    else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const cur = S.lastIdx < 0 ? -1 : S.lastIdx;
      const step = S.view === "grid" ? Math.max(1, Math.floor($("#filesGrid").clientWidth / 140)) : 1;
      const n = Math.min(S.shown.length - 1, Math.max(0, cur + (e.key === "ArrowDown" ? step : -step)));
      setSel(n, e.shiftKey ? "range" : "single");
      const el = $(`[data-i="${n}"]`, wrap); el && el.scrollIntoView({ block: "nearest" });
    }
  });
  window.addEventListener("hashchange", () => { const h = decodeURIComponent(location.hash.slice(1)); if (h !== S.cwd) navigate(h); });
}

// ============================================================================
// البدء
// ============================================================================
(async function init() {
  const theme = store.get("theme", matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
  document.documentElement.dataset.theme = theme;
  $("#themeBtn").textContent = theme === "dark" ? "☀️" : "🌙";
  bind();
  await loadInfo();
  let start = decodeURIComponent(location.hash.slice(1)) || store.get("cwd", "");
  if (!start && S.info && S.info.roots.length === 1) start = S.info.roots[0].name;
  await navigate(start, false);
  pollJobs();
})();
