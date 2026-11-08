<script>
(function(){
"use strict";
/* ---------------- أدوات ---------------- */
const $ = s => document.querySelector(s);
const AR = '٠١٢٣٤٥٦٧٨٩';
const ar = n => String(n).replace(/\d/g, d => AR[d]);
const esc = s => String(s).replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const store = {
  get(k, def){ try{ const v = localStorage.getItem(k); return v === null ? def : JSON.parse(v); }catch(e){ return def; } },
  set(k, v){ try{ localStorage.setItem(k, JSON.stringify(v)); }catch(e){} }
};
const H_MONTHS = ["محرّم","صفر","ربيع الأول","ربيع الآخر","جمادى الأولى","جمادى الآخرة","رجب","شعبان","رمضان","شوّال","ذو القعدة","ذو الحجة"];
const G_MONTHS = ["يناير","فبراير","مارس","أبريل","مايو","يونيو","يوليو","أغسطس","سبتمبر","أكتوبر","نوفمبر","ديسمبر"];
const WDAYS = ["الأحد","الاثنين","الثلاثاء","الأربعاء","الخميس","الجمعة","السبت"];
const WD_SAT = ["السبت","الأحد","الاثنين","الثلاثاء","الأربعاء","الخميس","الجمعة"];

const mkDate = (y,m,d) => new Date(y, m, d, 12, 0, 0);
const addDays = (dt, n) => mkDate(dt.getFullYear(), dt.getMonth(), dt.getDate() + n);
const key = dt => dt.getFullYear() + '-' + String(dt.getMonth()+1).padStart(2,'0') + '-' + String(dt.getDate()).padStart(2,'0');

/* ---------------- التحويل إلى الهجري (أم القرى، مع بديل حسابي) ---------------- */
let intlFmt = null;
try{
  intlFmt = new Intl.DateTimeFormat('en-u-ca-islamic-umalqura-nu-latn', {day:'numeric', month:'numeric', year:'numeric'});
  const t = intlFmt.formatToParts(mkDate(2026,10,8));
  const y = +t.find(p => p.type === 'year').value;
  if (y !== 1448) intlFmt = null;
}catch(e){ intlFmt = null; }

function tabular(dt){
  const jd = Math.floor(Date.UTC(dt.getFullYear(), dt.getMonth(), dt.getDate()) / 86400000) + 2440588;
  let l = jd - 1948440 + 10632;
  const n = Math.floor((l - 1) / 10631);
  l = l - 10631 * n + 354;
  const j = Math.floor((10985 - l) / 5316) * Math.floor(50 * l / 17719) + Math.floor(l / 5670) * Math.floor(43 * l / 15238);
  l = l - Math.floor((30 - j) / 15) * Math.floor(17719 * j / 50) - Math.floor(j / 16) * Math.floor(15238 * j / 43) + 29;
  const m = Math.floor(24 * l / 709);
  const d = l - Math.floor(709 * m / 24);
  return {y: 30 * n + j - 30, m, d};
}
function hijriRaw(dt){
  if (intlFmt){
    const p = intlFmt.formatToParts(dt), g = t => +p.find(x => x.type === t).value;
    return {y: g('year'), m: g('month'), d: g('day')};
  }
  return tabular(dt);
}

/* ---------------- الإعدادات ---------------- */
const S = Object.assign({adj:0, theme:'auto', fs:'n', view:'day'}, store.get('rz-set', {}));
const saveS = () => store.set('rz-set', S);
function applyLook(){
  const r = document.documentElement;
  if (S.theme === 'auto') r.removeAttribute('data-theme'); else r.setAttribute('data-theme', S.theme);
  r.classList.toggle('fs-l', S.fs === 'l');
  document.querySelectorAll('[data-theme-set]').forEach(b => b.classList.toggle('on', b.dataset.themeSet === S.theme));
  document.querySelectorAll('[data-fs]').forEach(b => b.classList.toggle('on', b.dataset.fs === S.fs));
  $('#adjOut').textContent = (S.adj > 0 ? '+' : '') + ar(S.adj);
}
const hijri = dt => hijriRaw(addDays(dt, S.adj));

/* ---------------- بناء أيام السنة الهجرية الحالية ---------------- */
let DAYS = [], TODAY = 0, YEAR = 0;
function build(){
  const now = new Date(), t = mkDate(now.getFullYear(), now.getMonth(), now.getDate());
  const th = hijri(t);
  YEAR = th.y;
  let start = addDays(t, -(th.d - 1));
  for (let k = 0; k < 13; k++){                 // الرجوع إلى ١ محرّم
    const h = hijri(start);
    if (h.m === 1 && h.d === 1 && h.y === YEAR) break;
    const prev = addDays(start, -1), ph = hijri(prev);
    start = addDays(prev, -(ph.d - 1));
  }
  DAYS = [];
  for (let dt = start, i = 0; i < 400; i++, dt = addDays(dt, 1)){
    const h = hijri(dt);
    if (h.y !== YEAR) break;
    DAYS.push({g: dt, y: h.y, m: h.m, d: h.d, i, k: key(dt)});
  }
  TODAY = DAYS.findIndex(x => x.k === key(t));
  if (TODAY < 0) TODAY = 0;
  $('#yearLbl').textContent = ar(YEAR) + 'هـ';
}

/* ---------------- اختيار المحتوى اليومي ---------------- */
const gcd = (a,b) => b ? gcd(b, a % b) : a;
function stepFor(n, seed){
  const c = [7,11,13,17,19,23,29,31,37];
  for (let s = 0; s < c.length; s++){ const k = c[(s + seed) % c.length]; if (gcd(k, n) === 1 && k < n) return k; }
  return 1;
}
const pick = (arr, i, seed, off) => arr[((i * stepFor(arr.length, seed)) + off) % arr.length];
function content(i){
  const N = WORDS.length, kw = stepFor(N, 3), off = YEAR % 7;
  return {
    wis: pick(WISDOM, i, 0, off), had: pick(HADITH, i, 1, off), sto: pick(STORIES, i, 2, off),
    mot: pick(MOTIV, i, 3, off), ch: pick(CHALLENGE, i, 4, off), rf: pick(REFLECT, i, 5, off),
    dh: pick(DHIKR, i, 6, off), ie: pick(IELTS, i, 7, off), wis2: pick(WISDOM, i + 31, 8, off),
    words: [0,1,2].map(j => WORDS[(((i * 3 + j) * kw) + off) % N])
  };
}

/* ---------------- المناسبات والأحداث ---------------- */
const OCC = {
 "1-1":[["رأس السنة الهجرية","بداية عام هجري جديد؛ ذكرى هجرة النبي ﷺ من مكة إلى المدينة. جدّد نيتك وخطّط لعامك."]],
 "1-9":[["يوم تاسوعاء","يُستحب صيامه مع عاشوراء؛ قال ﷺ: «لئن بقيتُ إلى قابل لأصومنّ التاسع» (رواه مسلم)."]],
 "1-10":[["يوم عاشوراء","اليوم الذي نجّى الله فيه موسى عليه السلام وقومه من فرعون. وصيامه «يُكفّر السنة التي قبله» (رواه مسلم)."]],
 "3-12":[["ذكرى مولد النبي ﷺ","على القول المشهور وُلد النبي ﷺ في ربيع الأول عام الفيل، وفي الثاني عشر منه — على المشهور أيضًا — كانت وفاته سنة ١١هـ. فرصة لقراءة سيرته والاقتداء به."]],
 "7-27":[["ذكرى الإسراء والمعراج","على قولٍ مشهور. رحلة النبي ﷺ من المسجد الحرام إلى المسجد الأقصى، ثم العروج إلى السماء، وفيها فُرضت الصلوات الخمس."]],
 "8-15":[["منتصف شعبان","شهر تُرفع فيه الأعمال إلى الله؛ كان ﷺ يُكثر الصيام فيه. استعد لرمضان من الآن."]],
 "9-1":[["أول أيام رمضان المبارك","«من صام رمضان إيمانًا واحتسابًا غُفر له ما تقدّم من ذنبه» (متفق عليه). رمضان كريم!"]],
 "9-17":[["ذكرى غزوة بدر الكبرى","سنة ٢هـ، يوم الفرقان، أول معركة فاصلة في الإسلام وانتصر فيها المسلمون على قلّتهم."]],
 "9-20":[["ذكرى فتح مكة","سنة ٨هـ، دخل النبي ﷺ مكة فاتحًا وقال لأهلها: «اذهبوا فأنتم الطلقاء»."]],
 "10-1":[["عيد الفطر المبارك","تقبّل الله منا ومنكم. يُسنّ التكبير، ولُبس الأحسن، وصلة الأرحام. ويحرم صوم هذا اليوم."]],
 "10-2":[["صيام ست من شوال","«من صام رمضان ثم أتبعه ستًا من شوال كان كصيام الدهر» (رواه مسلم)."]],
 "12-1":[["بداية العشر من ذي الحجة","أفضل أيام الدنيا؛ «ما من أيامٍ العملُ الصالح فيهن أحبّ إلى الله من هذه الأيام» (رواه البخاري). أكثِر من التكبير والتهليل."]],
 "12-8":[["يوم التروية","يتوجّه فيه الحجاج إلى منى."]],
 "12-9":[["يوم عرفة","خير يوم طلعت فيه الشمس، وصيامه لغير الحاج «يُكفّر السنة التي قبله والسنة التي بعده» (رواه مسلم). أكثِر من الدعاء."]],
 "12-10":[["عيد الأضحى المبارك","يوم النحر، أعظم الأيام عند الله. تقبّل الله طاعتكم وأضاحيكم."]],
 "12-11":[["أول أيام التشريق","«أيام أكلٍ وشربٍ وذِكرٍ لله» (رواه مسلم)."]],
 "12-12":[["ثاني أيام التشريق","يوم ذِكرٍ وتكبير."]],
 "12-13":[["آخر أيام التشريق","آخر أيام التكبير المقيّد بعد الصلوات."]]
};
const HIST_DAY = {
 "1-10":"سنة ٦١هـ: استشهاد الحسين بن علي رضي الله عنهما في كربلاء.",
 "4-24":"سنة ٥٨٣هـ: معركة حطّين بقيادة صلاح الدين الأيوبي.",
 "5-20":"سنة ٨٥٧هـ: فتح القسطنطينية على يد السلطان محمد الفاتح.",
 "6-22":"سنة ١٣هـ: وفاة الخليفة أبي بكر الصديق رضي الله عنه (على المشهور).",
 "7-27":"سنة ٥٨٣هـ: تحرير صلاح الدين الأيوبي لبيت المقدس.",
 "8-15":"سنة ٢هـ (على قولٍ لكثير من أهل السِّير): تحويل القبلة من بيت المقدس إلى الكعبة.",
 "9-25":"سنة ٦٥٨هـ: معركة عين جالوت وانكسار المغول.",
 "10-15":"سنة ٣هـ: غزوة أُحد (في منتصف شوال على المشهور).",
 "12-18":"سنة ٣٥هـ: استشهاد الخليفة عثمان بن عفان رضي الله عنه.",
 "12-26":"سنة ٢٣هـ: طُعن الخليفة عمر بن الخطاب رضي الله عنه (على المشهور)."
};
const HIST_MONTH = {
 1:["سنة ٧هـ: غزوة خيبر (على قول).","سنة ٦١هـ: معركة كربلاء.","تبدأ به السنة الهجرية منذ عهد عمر بن الخطاب رضي الله عنه."],
 2:["سنة ٢هـ: غزوة الأبواء (ودّان)، أول غزوة غزاها النبي ﷺ.","سنة ٤هـ: حادثة بئر معونة."],
 3:["سنة ١هـ: وصول النبي ﷺ إلى قباء ثم المدينة مهاجرًا.","سنة ١١هـ: وفاة النبي ﷺ.","سنة ٤هـ: غزوة بني النضير."],
 4:["سنة ٥٨٣هـ: معركة حطّين.","سنة ٢٣٣هـ تقريبًا: من أيام ازدهار بيت الحكمة في بغداد."],
 5:["سنة ٨هـ: غزوة مؤتة.","سنة ٨٥٧هـ: فتح القسطنطينية."],
 6:["سنة ١٣هـ: وفاة أبي بكر الصديق وتولّي عمر بن الخطاب الخلافة.","سنة ٣٦هـ: موقعة الجمل (على قول)."],
 7:["سنة ٩هـ: غزوة تبوك، آخر غزوات النبي ﷺ.","سنة ١٥هـ: معركة اليرموك (على المشهور).","سنة ٥٨٣هـ: تحرير بيت المقدس.","سنة ٤٧٩هـ: معركة الزلّاقة في الأندلس."],
 8:["سنة ٢هـ: فرض صيام رمضان وتحويل القبلة (على قول).","سنة ٥هـ أو ٦هـ: غزوة بني المصطلق."],
 9:["بدء نزول القرآن الكريم على النبي ﷺ في غار حراء.","سنة ٢هـ: غزوة بدر الكبرى.","سنة ٨هـ: فتح مكة.","سنة ٩٢هـ: فتح الأندلس بقيادة طارق بن زياد.","سنة ٦٥٨هـ: معركة عين جالوت."],
 10:["سنة ٣هـ: غزوة أُحد.","سنة ٨هـ: غزوة حُنين والطائف.","سنة ٥هـ: غزوة الخندق (الأحزاب) على قول."],
 11:["سنة ٦هـ: صلح الحديبية وبيعة الرضوان.","سنة ٧هـ: عمرة القضاء."],
 12:["قبل الهجرة بعام: بيعة العقبة الثانية.","سنة ١٠هـ: حجة الوداع وخطبة النبي ﷺ في عرفات.","سنة ٢٣هـ: طعن عمر بن الخطاب رضي الله عنه.","سنة ٣٥هـ: استشهاد عثمان بن عفان رضي الله عنه."]
};
const G_DAYS = {
 "1-1":"رأس السنة الميلادية.",
 "2-21":"اليوم الدولي للغة الأم.",
 "4-7":"يوم الصحة العالمي.",
 "4-23":"اليوم العالمي للكتاب.",
 "5-22":"العيد الوطني للجمهورية اليمنية (يوم الوحدة).",
 "6-5":"اليوم العالمي للبيئة.",
 "9-8":"اليوم الدولي لمحو الأمية.",
 "9-26":"ذكرى ثورة ٢٦ سبتمبر اليمنية.",
 "10-5":"اليوم العالمي للمعلّم.",
 "10-14":"ذكرى ثورة ١٤ أكتوبر اليمنية.",
 "10-16":"يوم الأغذية العالمي.",
 "11-30":"ذكرى عيد الاستقلال اليمني.",
 "12-18":"اليوم العالمي للغة العربية."
};
function occasions(day){
  const mk = day.m + '-' + day.d, list = (OCC[mk] || []).slice(), sun = [];
  if (day.m === 9 && day.d >= 21) list.push(["من العشر الأواخر","أيام الاجتهاد والاعتكاف؛ كان ﷺ إذا دخلت العشر أحيا ليله وأيقظ أهله وشدّ مئزره."]);
  if (day.m === 9 && day.d >= 20 && day.d <= 28 && day.d % 2 === 0) list.push(["الليلة ليلة الـ" + ar(day.d + 1),"من ليالي الوتر التي تُرجى فيها ليلة القدر؛ أكثِر من قول: «اللهم إنك عفوٌّ تحب العفو فاعفُ عني»."]);
  if (day.m === 12 && day.d >= 2 && day.d <= 7) list.push(["من العشر الأوائل من ذي الحجة","أكثِر من التكبير والتهليل والتحميد والعمل الصالح."]);
  if ([13,14,15].includes(day.d) && !(day.m === 12 && day.d === 13) && day.m !== 9) sun.push("من الأيام البيض: يُسنّ صيام الثالث عشر والرابع عشر والخامس عشر من كل شهر.");
  const wd = day.g.getDay();
  if (wd === 5) sun.push("يوم الجمعة: قراءة سورة الكهف، والإكثار من الصلاة على النبي ﷺ، وتحرّي ساعة الإجابة.");
  if ((wd === 1 || wd === 4) && day.m !== 9 && !(day.m === 10 && day.d === 1) && !(day.m === 12 && day.d >= 10 && day.d <= 13)) sun.push("يُسنّ صيام يوم " + WDAYS[wd] + "؛ «تُعرض الأعمال يوم الاثنين والخميس» (رواه الترمذي).");
  const hist = HIST_DAY[mk] ? [HIST_DAY[mk]] : [];
  const gk = (day.g.getMonth() + 1) + '-' + day.g.getDate();
  if (G_DAYS[gk]) hist.push(G_DAYS[gk]);
  return {list, sun, hist};
}

/* ---------------- الرسم: صفحة اليوم ---------------- */
const stage = $('#stage');
let cur = 0, view = S.view || 'day', dir = '';
const fmtG = dt => ar(dt.getDate()) + ' ' + G_MONTHS[dt.getMonth()] + ' ' + ar(dt.getFullYear()) + 'م';
const fmtH = d => ar(d.d) + ' ' + H_MONTHS[d.m - 1] + ' ' + ar(d.y) + 'هـ';
const LOCK_SVG = '<svg viewBox="0 0 24 24"><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>';

function headHTML(day, isToday){
  return `<div class="rings"><i></i><i></i><i></i><i></i></div>
  <div class="head">${isToday ? '<span class="badge-today">اليوم</span>' : ''}
    <div class="wd">${WDAYS[day.g.getDay()]}</div>
    <div class="big">${ar(day.d)}</div>
    <div class="hm">${H_MONTHS[day.m - 1]} ${ar(day.y)}هـ</div>
    <div class="gd">${fmtG(day.g)}</div>
  </div>`;
}

function dayHTML(i){
  const day = DAYS[i], c = content(i), o = occasions(day), isToday = i === TODAY;
  const pct = Math.round((i + 1) / DAYS.length * 100);
  let occ = '';
  if (o.list.length || o.hist.length || o.sun.length){
    occ = `<div class="occ"><h4>مناسبات وأحداث</h4>
      ${o.list.map(x => `<p><span class="t">${esc(x[0])}:</span> ${esc(x[1])}</p>`).join('')}
      ${o.hist.map(x => `<p>📜 ${esc(x)}</p>`).join('')}
      ${o.sun.map(x => `<p style="font-size:14.5px">🌙 ${esc(x)}</p>`).join('')}
    </div>`;
  }
  const words = c.words.map(w => `<div class="en-item">
      <span class="ar">${esc(w[2])}</span>
      <span class="w">${esc(w[0])}</span><span class="pos">${esc(w[1])}</span><button class="say" data-say="${esc(w[0])}" aria-label="استمع">🔊</button>
      <div class="ex">${esc(w[3])}</div></div>`).join('');
  const ie = c.ie;
  const ieRows = ie.w.map(r => `<div class="syn-row"><b>${esc(r[0])}</b><div class="d">${esc(r[1])}<span class="x">${esc(r[2])}</span></div></div>`).join('');
  const nk = 'rz-note-' + day.k, ck = 'rz-ch-' + day.k;
  const monthEv = (HIST_MONTH[day.m] || []).map(x => `<li>${esc(x)}</li>`).join('');

  return `<article class="page ${dir}"><div class="sheet">
    ${headHTML(day, isToday)}
    <div class="prog"><span>اليوم ${ar(i + 1)} من ${ar(DAYS.length)}</span><span class="bar"><b style="width:${pct}%"></b></span><span>${ar(pct)}٪</span></div>
    <div class="body">
      ${occ}
      <section class="card c-wis"><div class="lbl"><span class="dot"></span>حكمة اليوم</div>
        <p class="wisdom">${esc(c.wis[0])}</p><p class="wisdom-o">— ${esc(c.wis[1])}</p></section>
      <section class="card c-hadith"><div class="lbl"><span class="dot"></span>${c.had[0].startsWith('أثر') ? 'أثر' : 'من هدي النبي ﷺ'}</div>
        <p class="hadith">${esc(c.had[0].replace(/^أثر: /,''))}</p><p class="hadith-s">${esc(c.had[1])}</p></section>
      <section class="card c-story story"><div class="lbl"><span class="dot"></span>قصة من تراث العالم<span class="src">${esc(c.sto[1])}</span></div>
        <h3>${esc(c.sto[0])}</h3><p class="txt clamp" id="stTxt">${esc(c.sto[2])}</p>
        <button class="say" id="stToggle" style="margin:6px 0 0;padding:2px 12px;color:var(--red)">أكمل القصة ←</button>
        <p class="lesson" id="stLesson" hidden>💡 ${esc(c.sto[3])}</p></section>
      <section class="card"><p class="motiv">${esc(c.mot)}</p></section>
      <section class="card c-en"><div class="lbl"><span class="dot"></span>ثلاث كلمات إنجليزية<span class="src">مستوى متوسط</span></div>
        <div class="en en-list">${words}</div></section>
      <section class="card c-ielts"><div class="lbl"><span class="dot"></span>استعداد للآيلتس IELTS<span class="src">كلمات متقاربة المعنى</span></div>
        <div class="syn-words">${ie.w.map(r => `<span>${esc(r[0])}</span>`).join('')}</div>
        <p class="syn-mean">المعنى المشترك: «${esc(ie.m)}» — فما الفرق؟</p>
        ${ieRows}<p class="tip">📝 ${esc(ie.t)}</p></section>

      <button class="more-btn" id="moreBtn">المزيد لهذا اليوم ⌄</button>
      <div class="more" id="more">
        <section class="card"><div class="lbl"><span class="dot"></span>حدث في مثل هذا الشهر (${H_MONTHS[day.m - 1]})</div><ul class="list-ev">${monthEv}</ul></section>
        <section class="card c-wis"><div class="lbl"><span class="dot"></span>حكمة إضافية</div><p class="wisdom" style="font-size:20px">${esc(c.wis2[0])}</p><p class="wisdom-o">— ${esc(c.wis2[1])}</p></section>
        <section class="card"><div class="lbl"><span class="dot"></span>تحدّي اليوم</div>
          <label class="check"><input type="checkbox" id="chBox" ${store.get(ck, false) ? 'checked' : ''}> <span>${esc(c.ch)}</span></label></section>
        <section class="card"><div class="lbl"><span class="dot"></span>سؤال للتأمل</div><p style="font-size:18px;font-weight:700">${esc(c.rf)}</p></section>
        <section class="card c-hadith"><div class="lbl"><span class="dot"></span>ذِكر اليوم</div><p class="hadith" style="text-align:center">${esc(c.dh)}</p></section>
        <section class="card"><div class="lbl"><span class="dot"></span>يومياتي — ملاحظة خاصة بهذا اليوم</div>
          <textarea class="note" id="note" placeholder="اكتب ما تعلّمته اليوم، أو ما تشكر الله عليه…">${esc(store.get(nk, ''))}</textarea>
          <div class="saved" id="saved">تُحفظ في هذا المتصفح فقط.</div></section>
      </div>
    </div></div></article>`;
}

function lockHTML(i){
  const day = DAYS[i], w = LOCK_WISDOM[i % LOCK_WISDOM.length];
  const diff = i - TODAY;
  let when;
  if (diff === 1){
    const now = new Date(), mid = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 0);
    const mins = Math.max(1, Math.round((mid - now) / 60000)), h = Math.floor(mins / 60), m = mins % 60;
    when = 'تُفتح هذه الصفحة بعد ' + (h ? ar(h) + ' ساعة' + (m ? ' و' : '') : '') + (m ? ar(m) + ' دقيقة' : '');
  } else when = 'تُفتح هذه الصفحة بعد ' + ar(diff) + ' يومًا';
  return `<article class="page ${dir}"><div class="sheet">${headHTML(day, false)}
    <div class="lock"><div class="ic">${LOCK_SVG}</div>
      <p class="s">لا تستعجل الغد…</p>
      <p class="q">${esc(w[0])}</p>
      <p class="s">— ${esc(w[1])}</p>
      <span class="cd">⏳ ${when}</span><br>
      <button class="back" id="backToday">عُد إلى يومك</button>
    </div></div></article>`;
}

/* ---------------- الرسم: الأسبوع ---------------- */
function weekStart(i){ const wd = (DAYS[i].g.getDay() + 1) % 7; return i - wd; }
function weekHTML(){
  const s = weekStart(cur);
  const first = DAYS[Math.max(0, s)], last = DAYS[Math.min(DAYS.length - 1, s + 6)];
  let cells = '';
  for (let k = 0; k < 7; k++){
    const i = s + k;
    if (i < 0 || i >= DAYS.length){ cells += `<div class="wcell out"><div class="n">—</div><div class="info"><div class="a">${WD_SAT[k]}</div><div class="b">خارج هذه السنة</div></div></div>`; continue; }
    const d = DAYS[i], o = occasions(d), fut = i > TODAY;
    const tag = o.list[0] ? `<span class="tag">${esc(o.list[0][0])}</span>` : (o.hist[0] ? '<span class="tag">📜</span>' : '');
    const snip = fut ? '🔒 لم يحن بعد — لا تستعجل' : esc(content(i).wis[0]);
    cells += `<button class="wcell ${i === TODAY ? 'today' : ''} ${fut ? 'fut' : ''}" data-go="${i}">
      <div class="n">${ar(d.d)}<small>${H_MONTHS[d.m - 1]}</small></div>
      <div class="info"><div class="a">${WDAYS[d.g.getDay()]}${i === TODAY ? ' — اليوم' : ''}</div><div class="b">${fmtG(d.g)}</div><div class="c">${snip}</div></div>${tag}</button>`;
  }
  return `<div class="page ${dir}"><div class="vhead"><div><h2>الأسبوع</h2><p>${fmtH(first)} — ${fmtH(last)}</p></div></div><div class="week">${cells}</div></div>`;
}

/* ---------------- الرسم: الشهر ---------------- */
function monthHTML(){
  const m = DAYS[cur].m, md = DAYS.filter(d => d.m === m);
  const lead = (md[0].g.getDay() + 1) % 7;
  let g = WD_SAT.map(x => `<div class="dn">${x.replace('ال','')}</div>`).join('');
  for (let k = 0; k < lead; k++) g += '<div class="mc empty"></div>';
  const evs = [];
  md.forEach(d => {
    const o = occasions(d), fut = d.i > TODAY, has = o.list.length || o.hist.length;
    if (has) evs.push(`<li><b>${ar(d.d)} ${H_MONTHS[d.m - 1]}</b> (${ar(d.g.getDate())} ${G_MONTHS[d.g.getMonth()]}): ${esc(o.list.map(x => x[0]).concat(o.hist).join(' · '))}</li>`);
    g += `<button class="mc ${d.i === TODAY ? 'today' : ''} ${fut ? 'fut' : ''}" data-go="${d.i}" aria-label="${fmtH(d)}">
      ${has ? '<span class="ev"></span>' : ''}<span class="h">${ar(d.d)}</span><span class="g">${ar(d.g.getDate())}${(d.g.getDate() === 1 || d.d === 1) ? ' ' + G_MONTHS[d.g.getMonth()] : ''}</span></button>`;
  });
  const g0 = md[0].g, g1 = md[md.length - 1].g;
  const gr = G_MONTHS[g0.getMonth()] + (g0.getMonth() !== g1.getMonth() ? ' — ' + G_MONTHS[g1.getMonth()] : '') + ' ' + ar(g1.getFullYear()) + 'م';
  return `<div class="page ${dir}"><div class="vhead"><div><h2>${H_MONTHS[m - 1]} ${ar(YEAR)}هـ</h2><p>${gr} · ${ar(md.length)} يومًا</p></div></div>
    <div class="mgrid">${g}</div>
    <div class="legend"><span><i></i> مناسبة أو حدث</span><span>الأيام الباهتة لم يحن وقتها 🔒</span></div>
    ${evs.length ? `<section class="card mevents"><div class="lbl"><span class="dot"></span>مناسبات هذا الشهر</div><ul class="list-ev">${evs.join('')}</ul></section>` : ''}
  </div>`;
}

/* ---------------- العرض والتنقل ---------------- */
function render(){
  document.querySelectorAll('.seg button').forEach(b => b.classList.toggle('on', b.dataset.view === view));
  if (view === 'day') stage.innerHTML = cur > TODAY ? lockHTML(cur) : dayHTML(cur);
  else if (view === 'week') stage.innerHTML = weekHTML();
  else stage.innerHTML = monthHTML();
  dir = '';
  wire();
  navState();
  $('#foot').textContent = 'التاريخ الهجري وفق تقويم أم القرى' + (S.adj ? ' (مع تعديل ' + (S.adj > 0 ? '+' : '') + ar(S.adj) + ' يوم)' : '') + (intlFmt ? '' : ' — حساب تقريبي');
}
function navState(){
  const P = $('#prevBtn'), N = $('#nextBtn'), T = $('#todayBtn');
  let canP, canN, pt, nt, ps = '', ns = '';
  if (view === 'day'){
    canP = cur > 0; canN = cur <= TODAY && cur < DAYS.length - 1;
    pt = cur === TODAY ? 'أمس' : cur === TODAY + 1 ? 'اليوم' : 'السابق';
    nt = cur === TODAY ? 'غدًا' : cur === TODAY - 1 ? 'اليوم' : 'التالي';
    if (canP) ps = ar(DAYS[cur - 1].d) + ' ' + H_MONTHS[DAYS[cur - 1].m - 1];
    if (canN) ns = cur === TODAY ? 'لم يحن بعد 🔒' : ar(DAYS[cur + 1].d) + ' ' + H_MONTHS[DAYS[cur + 1].m - 1];
    T.hidden = cur === TODAY;
  } else if (view === 'week'){
    const s = weekStart(cur); canP = s > 0; canN = s + 7 < DAYS.length;
    pt = 'الأسبوع السابق'; nt = 'الأسبوع التالي';
    T.hidden = s <= TODAY && TODAY < s + 7;
  } else {
    const m = DAYS[cur].m; canP = m > DAYS[0].m; canN = m < DAYS[DAYS.length - 1].m;
    pt = 'الشهر السابق'; nt = 'الشهر التالي';
    if (canP) ps = H_MONTHS[m - 2]; if (canN) ns = H_MONTHS[m];
    T.hidden = m === DAYS[TODAY].m;
  }
  P.disabled = !canP; N.disabled = !canN;
  $('#prevTx').textContent = pt; $('#nextTx').textContent = nt;
  $('#prevSub').textContent = ps; $('#nextSub').textContent = ns;
}
function go(step){
  if (view === 'day'){
    const n = cur + step;
    if (n < 0 || n >= DAYS.length || (step > 0 && cur > TODAY)) return;
    cur = n;
  } else if (view === 'week'){
    const s = weekStart(cur) + step * 7;
    if (s + 6 < 0 || s >= DAYS.length) return;
    cur = Math.min(DAYS.length - 1, Math.max(0, s + 3));
    if (s <= TODAY && TODAY < s + 7) cur = TODAY;
  } else {
    const m = DAYS[cur].m + step, f = DAYS.find(d => d.m === m);
    if (!f) return;
    cur = DAYS[TODAY].m === m ? TODAY : (f.i > TODAY ? f.i : Math.min(TODAY, DAYS.filter(d => d.m === m).pop().i));
  }
  dir = step > 0 ? 'in-next' : 'in-prev';
  render();
  window.scrollTo({top: 0, behavior: 'smooth'});
}
function goToday(){ dir = cur > TODAY ? 'in-prev' : 'in-next'; cur = TODAY; render(); window.scrollTo({top:0, behavior:'smooth'}); }
function setView(v){ view = v; S.view = v; saveS(); if (v === 'day' && cur > TODAY) cur = TODAY; dir = ''; render(); }

function speak(t){
  try{ const u = new SpeechSynthesisUtterance(t); u.lang = 'en-US'; u.rate = .9; speechSynthesis.cancel(); speechSynthesis.speak(u); }catch(e){}
}
function wire(){
  stage.querySelectorAll('[data-go]').forEach(b => b.addEventListener('click', () => { cur = +b.dataset.go; view = 'day'; S.view = 'day'; saveS(); dir = 'in-next'; render(); window.scrollTo({top:0}); }));
  stage.querySelectorAll('[data-say]').forEach(b => b.addEventListener('click', () => speak(b.dataset.say)));
  const bt = $('#backToday'); if (bt) bt.addEventListener('click', goToday);
  const st = $('#stToggle');
  const openStory = open => { $('#stTxt').classList.toggle('clamp', !open); $('#stLesson').hidden = !open; st.textContent = open ? 'إخفاء ↑' : 'أكمل القصة ←'; };
  if (st) st.addEventListener('click', () => openStory($('#stTxt').classList.contains('clamp')));
  const mb = $('#moreBtn');
  if (mb) mb.addEventListener('click', () => {
    const m = $('#more'), open = !m.classList.contains('open');
    m.classList.toggle('open', open); mb.textContent = open ? 'إخفاء المزيد ⌃' : 'المزيد لهذا اليوم ⌄';
    openStory(open);
    store.set('rz-more', open);
  });
  if (mb && store.get('rz-more', false)) mb.click();
  const note = $('#note');
  if (note){
    const k = 'rz-note-' + DAYS[cur].k; let t;
    note.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { store.set(k, note.value); $('#saved').textContent = '✓ حُفظت'; }, 400); });
  }
  const cb = $('#chBox');
  if (cb) cb.addEventListener('change', () => store.set('rz-ch-' + DAYS[cur].k, cb.checked));
}

/* ---------------- الأحداث العامة ---------------- */
$('#prevBtn').addEventListener('click', () => go(-1));
$('#nextBtn').addEventListener('click', () => go(1));
$('#todayBtn').addEventListener('click', goToday);
document.querySelectorAll('.seg button').forEach(b => b.addEventListener('click', () => setView(b.dataset.view)));
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'TEXTAREA' || $('#modal').classList.contains('open')) return;
  if (e.key === 'ArrowLeft') go(1);          // في الواجهة العربية: اليسار = التالي
  else if (e.key === 'ArrowRight') go(-1);
  else if (e.key === 'Home' || e.key === 't') goToday();
});
let tx = 0, ty = 0, tt = 0;
stage.addEventListener('touchstart', e => { const p = e.changedTouches[0]; tx = p.clientX; ty = p.clientY; tt = Date.now(); }, {passive: true});
stage.addEventListener('touchend', e => {
  if (e.target.closest('textarea')) return;
  const p = e.changedTouches[0], dx = p.clientX - tx, dy = p.clientY - ty;
  if (Math.abs(dx) > 60 && Math.abs(dy) < Math.abs(dx) * 0.6 && Date.now() - tt < 800) go(dx > 0 ? 1 : -1);
}, {passive: true});

const modal = $('#modal');
$('#setBtn').addEventListener('click', () => { modal.classList.add('open'); modal.setAttribute('aria-hidden', 'false'); });
const closeM = () => { modal.classList.remove('open'); modal.setAttribute('aria-hidden', 'true'); };
$('#closeSet').addEventListener('click', closeM);
modal.addEventListener('click', e => { if (e.target === modal) closeM(); });
function changeAdj(n){
  S.adj = Math.max(-2, Math.min(2, S.adj + n)); saveS(); applyLook();
  const wasToday = cur === TODAY, k = DAYS[cur] && DAYS[cur].k;
  build();
  const f = DAYS.findIndex(d => d.k === k);
  cur = wasToday || f < 0 ? TODAY : f;
  render();
}
$('#adjMinus').addEventListener('click', () => changeAdj(-1));
$('#adjPlus').addEventListener('click', () => changeAdj(1));
document.querySelectorAll('[data-theme-set]').forEach(b => b.addEventListener('click', () => { S.theme = b.dataset.themeSet; saveS(); applyLook(); }));
document.querySelectorAll('[data-fs]').forEach(b => b.addEventListener('click', () => { S.fs = b.dataset.fs; saveS(); applyLook(); }));

/* عند منتصف الليل: تنتقل الروزنامة تلقائيًا إلى اليوم الجديد */
let lastKey = key(new Date());
setInterval(() => {
  const k = key(new Date());
  if (k !== lastKey){
    lastKey = k; const wasToday = cur === TODAY || cur === TODAY + 1;
    build(); if (wasToday) cur = TODAY; render();
  } else if (view === 'day' && cur === TODAY + 1) render();
}, 60000);

/* ---------------- البدء ---------------- */
applyLook();
build();
cur = TODAY;
render();
})();
</script>
</body>
</html>
