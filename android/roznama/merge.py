"""
Builds the app's roznama page:  roznama-1448.html  (the Yemeni roznama, base)
+ the daily extras from roznama-old.html (wisdom, hadith, motivation, challenge,
reflection question, dhikr, English words, IELTS) as extra sections in the
same show/hide system. English and IELTS start hidden.

Also: weekly auto-update (Friday, or when a Friday was missed), the sheet can
switch the roznama off or hide sections, and window.rzApi for the Android app.

Usage: python3 merge.py roznama-1448.html roznama-old.html out.html
"""
import re, sys

new_path, old_path, out_path = sys.argv[1:4]
new = open(new_path, encoding="utf-8").read()
old = open(old_path, encoding="utf-8").read()
osc = re.findall(r"<script[^>]*>(.*?)</script>", old, re.S)

def must_replace(s, a, b, count=1):
    if a not in s:
        raise SystemExit("pattern not found: " + a[:80])
    return s.replace(a, b, count)

# 1) old daily content, namespaced so nothing clashes
data = "window.RZOLD = (function(){\n" + osc[0] + "\n" + osc[2] + """
var W = typeof WORDS === "string" ? WORDS.trim().split("\\n").map(function(l){ return l.split("|"); }) : WORDS;
return {WISDOM:WISDOM, HADITH:HADITH, MOTIV:MOTIV, CHALLENGE:CHALLENGE, REFLECT:REFLECT, DHIKR:DHIKR, WORDS:W, IELTS:IELTS};
})();
"""
extra_css = """
<style>
.rzx-wis{font-size:1.15em;font-weight:700;line-height:1.9}
.rzx-src{opacity:.7;font-size:.9em;margin-top:6px}
.rzx-en{display:grid;gap:10px}
.rzx-en .it{padding:8px 10px;border-radius:12px;background:rgba(127,127,127,.08)}
.rzx-en .w{font-weight:700;direction:ltr;unicode-bidi:isolate}
.rzx-en .pos{opacity:.6;font-size:.85em;margin-inline-start:6px}
.rzx-en .ex{direction:ltr;text-align:left;opacity:.8;font-size:.9em;margin-top:4px}
.rzx-say{border:0;background:none;cursor:pointer;font-size:1em}
.rzx-row{padding:6px 0;border-bottom:1px dashed rgba(127,127,127,.3)}
.rzx-row b{direction:ltr;unicode-bidi:isolate}
.rzx-off{padding:40px 16px;text-align:center;font-size:1.2em;line-height:2}
/* settings: a full-screen page instead of a bottom sheet */
.sheet{background:var(--card)}
.panel{inset:0;max-height:none;height:100%;border-radius:0;box-sizing:border-box;
  padding:0 16px calc(24px + env(safe-area-inset-bottom))}
.panel > .opt:first-child{position:sticky;top:0;z-index:2;background:var(--card);
  margin:0 -16px;padding:calc(10px + env(safe-area-inset-top)) 16px 10px;border-bottom:1px solid var(--line)}
.panel > .opt:first-child h3{margin:0;font-size:1.25em}
</style>
"""
first_script = new.find("<script")
new = new[:first_script] + extra_css + "<script>\n" + data + "\n</script>\n" + new[first_script:]

# 2) new sections (inserted before «مذكراتي»)
new = must_replace(new, '  ["notes","📝","مذكراتي"]', '''  ["wisdom","💡","حكمة اليوم"],
  ["hadith","🕊️","من الهدي النبوي"],
  ["motiv","✨","كلمة تحفيزية"],
  ["challenge","🎯","تحدّي اليوم"],
  ["question","❓","سؤال للتأمل"],
  ["dhikr","📿","ذكر اليوم"],
  ["english","🔤","ثلاث كلمات إنجليزية"],
  ["ielts","🎓","استعداد للآيلتس IELTS"],
  ["notes","📝","مذكراتي"]''')

# 3) defaults: English + IELTS hidden the first time
new = must_replace(new, "function persist(){ return save(KEY, state); }", """function persist(){ return save(KEY, state); }
if (!state.mergeDefaults){ state.hidden.english = true; state.hidden.ielts = true; state.mergeDefaults = 1; save(KEY, state); }
if (!state.ch) state.ch = {};""")

# 4) sheet controls: «إيقاف_الروزنامة» = نعم and «أقسام_مخفية» (ids or names, comma separated)
new = must_replace(new, "function shown(id){ return !state.hidden[id]; }", """function sheetHidden(){
  var v = String(settings()["أقسام_مخفية"] || "").split(/[،,\\n]/).map(function(x){ return x.trim(); }).filter(Boolean);
  return v;
}
function shown(id){
  if (state.hidden[id]) return false;
  var h = sheetHidden(); if (!h.length) return true;
  var s = SECTIONS.find(function(x){ return x[0] === id; });
  return h.indexOf(id) < 0 && !(s && h.indexOf(s[2]) >= 0);
}""")

# 5) render the extra sections, and the «off» switch
new = must_replace(new, '''  (SIMPLE ? ["reflect","maalim","proverb","idea","story","poem","onday","custom2","person","notes"]
          : ["reflect","proverb","maalim","poem","story","onday","custom2","person","home","idea","notes"]).forEach(function(k){ if (S[k]) h += S[k]; });''', '''  // ---- extras merged from the earlier daily roznama ----
  (function(){
    var O = window.RZOLD; if (!O) return;
    var n = Math.floor(toDate(current) / 86400000), off = 3;
    function g(a, b){ while (b){ var t = b; b = a % b; a = t; } return a; }
    function step(len, seed){ var c = [7,11,13,17,19,23,29,31,37]; for (var s = 0; s < c.length; s++){ var k = c[(s + seed) % c.length]; if (g(k, len) === 1 && k < len) return k; } return 1; }
    function pick(arr, seed){ return arr[((n * step(arr.length, seed)) % arr.length + arr.length + off) % arr.length]; }
    if (shown("wisdom")){ var w = pick(O.WISDOM, 0); S.wisdom = card("wisdom", "", '<div class="rzx-wis">'+esc(w[0])+'</div><div class="rzx-src">— '+esc(w[1])+'</div>'); }
    if (shown("hadith")){ var hd = pick(O.HADITH, 1); var at = String(hd[0]).indexOf("أثر") === 0;
      S.hadith = card("hadith", at ? "أثر" : "", '<div class="rzx-wis">'+esc(String(hd[0]).replace(/^أثر: /, ""))+'</div><div class="rzx-src">'+esc(hd[1])+'</div>'); }
    if (shown("motiv")) S.motiv = card("motiv", "", '<div class="rzx-wis">'+esc(pick(O.MOTIV, 3))+'</div>');
    if (shown("challenge")){ var c = pick(O.CHALLENGE, 4);
      S.challenge = card("challenge", "", '<label style="display:flex;gap:10px;align-items:center"><input type="checkbox" id="rzxCh" '+(state.ch[current] ? "checked" : "")+'> <span>'+esc(c)+'</span></label>'); }
    if (shown("question")) S.question = card("question", "", '<div class="rzx-wis">'+esc(pick(O.REFLECT, 5))+'</div>');
    if (shown("dhikr")) S.dhikr = card("dhikr", "", '<div class="rzx-wis" style="text-align:center">'+esc(pick(O.DHIKR, 6))+'</div>');
    if (shown("english")){
      var N = O.WORDS.length, kw = step(N, 3);
      var ws = [0,1,2].map(function(j){ return O.WORDS[(((n * 3 + j) * kw) % N + N + off) % N]; });
      S.english = card("english", "", '<div class="rzx-en">'+ws.map(function(w){
        return '<div class="it"><span class="w">'+esc(w[0])+'</span><span class="pos">'+esc(w[1])+'</span> <button class="rzx-say" data-say="'+esc(w[0])+'" aria-label="استمع">🔊</button> — '+esc(w[2])+'<div class="ex">'+esc(w[3])+'</div></div>';
      }).join("")+'</div>');
    }
    if (shown("ielts")){ var ie = pick(O.IELTS, 7);
      S.ielts = card("ielts", "", '<div style="font-weight:700">المعنى: '+esc(ie.m)+'</div>'+ie.w.map(function(r){
        return '<div class="rzx-row"><b>'+esc(r[0])+'</b> <button class="rzx-say" data-say="'+esc(r[0])+'">🔊</button> — '+esc(r[1])+'<div class="ex" style="direction:ltr;text-align:left;opacity:.8">'+esc(r[2])+'</div></div>';
      }).join("")+'<div class="rzx-src">💡 '+esc(ie.t)+'</div>');
    }
  })();
  (SIMPLE ? ["reflect","maalim","proverb","idea","story","poem","onday","custom2","person","wisdom","hadith","motiv","challenge","question","dhikr","english","ielts","notes"]
          : ["reflect","proverb","maalim","poem","story","onday","custom2","person","home","idea","wisdom","hadith","motiv","challenge","question","dhikr","english","ielts","notes"]).forEach(function(k){ if (S[k]) h += S[k]; });
  if (/^(نعم|yes|1|true|إيقاف)$/i.test(String(st["إيقاف_الروزنامة"] || "").trim())){
    h = '<div class="rzx-off">⏸️ '+esc(st["رسالة_الإيقاف"] || "الروزنامة متوقفة مؤقتاً")+'</div>';
  }''')

# 6) wire the challenge checkbox + English pronunciation after render
new = must_replace(new, "  startTyper(wk);\n  startFriday();", """  var rch = $("rzxCh"); if (rch) rch.onchange = function(){ if (rch.checked) state.ch[current] = 1; else delete state.ch[current]; persist(); };
  Array.prototype.forEach.call(document.querySelectorAll(".rzx-say"), function(b){ b.onclick = function(){
    try { var u = new SpeechSynthesisUtterance(b.getAttribute("data-say")); u.lang = "en-US"; speechSynthesis.cancel(); speechSynthesis.speak(u); } catch(e){}
  }; });
  startTyper(wk);
  startFriday();""")

# 7) weekly auto-update: every Friday, or as soon as a Friday was missed (7+ days)
new = must_replace(new, "if ((!state.lastFetch || (now.getDay() === 5 && state.lastFetch !== iso)) && navigator.onLine !== false){",
    "var age = state.lastFetch ? (toDate(iso) - toDate(state.lastFetch)) / 86400000 : 99;\n  if ((!state.lastFetch || (now.getDay() === 5 && state.lastFetch !== iso) || age >= 7) && navigator.onLine !== false){")

# 7b) manual update says whether anything really changed
new = must_replace(new, "    remote = {fetched:new Date().toISOString(), days:days, settings:st, weeks:wk};",
    "    var same = !!remote && JSON.stringify([remote.days, remote.settings, remote.weeks]) === JSON.stringify([days, st, wk]);\n    remote = {fetched:new Date().toISOString(), days:days, settings:st, weeks:wk};")
new = must_replace(new, '    if (manual) $("remoteMsg").textContent = "✓ تحدّث المحتوى";',
    '    if (manual) $("remoteMsg").textContent = same ? "✓ لا تغييرات جديدة، المحتوى محدّث" : "✓ تحدّث المحتوى";')

# 8) a small API for the Android app (manual update from «حول», last update date)
idx = new.rstrip().rfind("})();")
new = new[:idx] + """window.rzApi = {
  refresh: function(){ refreshRemote(true); },
  lastFetch: function(){ return state.lastFetch || ""; },
  /** what the app shows above the page: last update, linked sheet, result of the last manual update */
  status: function(){
    var m = document.getElementById("remoteMsg");
    return JSON.stringify({ last: state.lastFetch || "", linked: !!sheetId(remoteLink()), msg: m ? m.textContent : "" });
  }
};
""" + new[idx:]

open(out_path, "w", encoding="utf-8").write(new)
print("ok", len(new))
