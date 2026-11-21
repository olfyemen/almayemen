/**
 * حقيبة الما — استقبال رسائل المستخدمين في جدول «رسائل حقيبة الما»
 * يضيف كل رسالة صفاً في ورقة «الرسائل» ويرسل لك بريداً بها.
 * البريد يصل إلى صاحب الجدول نفسه (من ينشر السكربت)، فلا حاجة لكتابة البريد هنا.
 */
const SHEET_NAME = 'الرسائل';
const MAX_PER_MINUTE = 20; // حماية بسيطة من الإغراق

function doPost(e) {
  try {
    const d = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    const message = clip(d.message, 5000);
    if (!message.trim()) return out({ ok: false, error: 'empty' });

    const cache = CacheService.getScriptCache();
    const n = Number(cache.get('count') || 0);
    if (n >= MAX_PER_MINUTE) return out({ ok: false, error: 'busy' });
    cache.put('count', String(n + 1), 60);

    const row = [new Date(), clip(d.type, 40), message, clip(d.name, 80), clip(d.contact, 120), clip(d.version, 20), clip(d.device, 80), 'جديدة'];
    SpreadsheetApp.getActiveSpreadsheet().getSheetByName(SHEET_NAME).appendRow(row);

    try {
      MailApp.sendEmail({
        to: Session.getEffectiveUser().getEmail(),
        subject: '📩 حقيبة الما: ' + (row[1] || 'رسالة جديدة'),
        body: 'النوع: ' + row[1] + '\nالاسم: ' + row[3] + '\nللتواصل: ' + row[4] + '\nالإصدار: ' + row[5] + ' · ' + row[6] + '\n\n' + message +
              '\n\nالجدول: ' + SpreadsheetApp.getActiveSpreadsheet().getUrl(),
      });
    } catch (mailError) { /* الرسالة محفوظة في الجدول حتى لو تعذر البريد */ }

    return out({ ok: true });
  } catch (err) {
    return out({ ok: false, error: String(err) });
  }
}

/** للتأكد من أن الرابط يعمل عند فتحه في المتصفح */
function doGet() { return out({ ok: true, app: 'alma-feedback' }); }

/** يقص النص ويمنع تحويله إلى معادلة داخل الجدول */
function clip(v, max) {
  let s = String(v == null ? '' : v).slice(0, max);
  if (/^[=+\-@]/.test(s)) s = "'" + s;
  return s;
}

function out(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
