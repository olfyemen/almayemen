// إرسال قالب العرض للعملاء الذين وافقوا فقط (consent = yes).
//
// الاستخدام:
//   node scripts/send-offer.js <اسم_القالب> [متغير1] [متغير2] ...
// مثال (قالب فيه {{1}} = نسبة الخصم، {{2}} = تاريخ الانتهاء):
//   node scripts/send-offer.js weekly_offer "20%" "نهاية الأسبوع"
import { allContacts } from "../src/store.js";
import { sendTemplateWithBodyParams } from "../src/whatsapp.js";

const [, , templateName, ...params] = process.argv;
if (!templateName) {
  console.error('الاستخدام: node scripts/send-offer.js <اسم_القالب> [متغيرات...]');
  process.exit(1);
}

// العملاء الموافقون فقط — هذا هو شرط السلامة من الحظر.
const targets = allContacts().filter((c) => c.consent === "yes");

console.log(
  `سيُرسل قالب العرض "${templateName}" إلى ${targets.length} عميل موافق...`
);

let ok = 0;
let fail = 0;
for (const c of targets) {
  try {
    await sendTemplateWithBodyParams(c.phone, templateName, params);
    ok++;
    console.log(`✅ ${c.phone}`);
  } catch (e) {
    fail++;
    console.error(`❌ ${c.phone}: ${e.message}`);
  }
  await new Promise((r) => setTimeout(r, 400));
}

console.log(`\nانتهى. نجح: ${ok} — فشل: ${fail}`);
