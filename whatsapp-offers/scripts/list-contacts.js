// عرض ملخّص جهات الاتصال وحالات الموافقة.
// الاستخدام: node scripts/list-contacts.js
import { allContacts } from "../src/store.js";

const contacts = allContacts();
const byConsent = { yes: 0, no: 0, unknown: 0 };
for (const c of contacts) byConsent[c.consent] = (byConsent[c.consent] || 0) + 1;

console.log(`إجمالي الأرقام: ${contacts.length}`);
console.log(`  موافقون (yes): ${byConsent.yes}`);
console.log(`  رافضون (no): ${byConsent.no}`);
console.log(`  لم يحسموا (unknown): ${byConsent.unknown}\n`);

for (const c of contacts) {
  console.log(
    `${c.phone}\t${c.consent}\t${c.name || ""}\tآخر تواصل: ${c.lastContact}`
  );
}
