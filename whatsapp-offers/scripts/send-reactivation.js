// إرسال قالب "إعادة التفعيل" للأرقام التي راسلتك سابقًا ولم تُسجّل موافقتها بعد.
// هذا القالب يجب أن يكون معتمدًا من Meta ويحتوي زرّي "نعم / لا".
//
// الاستخدام:
//   node scripts/send-reactivation.js <اسم_القالب>
// مثال:
//   node scripts/send-reactivation.js reactivation_offer
import { allContacts } from "../src/store.js";
import { sendTemplate } from "../src/whatsapp.js";

const templateName = process.argv[2];
if (!templateName) {
  console.error("الاستخدام: node scripts/send-reactivation.js <اسم_القالب>");
  process.exit(1);
}

// نرسل فقط لمن لم يوافق ولم يرفض بعد (consent = unknown)
const targets = allContacts().filter((c) => c.consent === "unknown");

console.log(`سيُرسل قالب "${templateName}" إلى ${targets.length} رقم...`);

let ok = 0;
let fail = 0;
for (const c of targets) {
  try {
    await sendTemplate(c.phone, templateName);
    ok++;
    console.log(`✅ ${c.phone}`);
  } catch (e) {
    fail++;
    console.error(`❌ ${c.phone}: ${e.message}`);
  }
  // تمهّل بسيط بين الرسائل لتجنّب تجاوز الحدود
  await new Promise((r) => setTimeout(r, 400));
}

console.log(`\nانتهى. نجح: ${ok} — فشل: ${fail}`);
