// استيراد أرقام قديمة من ملف نصي (رقم في كل سطر) إلى المخزن بحالة unknown،
// حتى يمكن إرسال قالب إعادة التفعيل لها.
// صيغة الرقم: دولية بدون + ولا مسافات، مثل 9665xxxxxxxx
//
// الاستخدام: node scripts/import-numbers.js numbers.txt
import { readFileSync } from "node:fs";
import { recordInbound } from "../src/store.js";

const file = process.argv[2];
if (!file) {
  console.error("الاستخدام: node scripts/import-numbers.js <ملف_الأرقام>");
  process.exit(1);
}

const lines = readFileSync(file, "utf8")
  .split("\n")
  .map((l) => l.replace(/[^\d]/g, "")) // نُبقي الأرقام فقط
  .filter((l) => l.length >= 8);

let n = 0;
for (const phone of lines) {
  recordInbound(phone);
  n++;
}
console.log(`تم استيراد ${n} رقم بحالة "unknown".`);
console.log("ملاحظة: لن تُرسل لهم عروض مباشرة — أرسل أولًا قالب إعادة التفعيل.");
