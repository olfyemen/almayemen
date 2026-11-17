// كان «الما» في هذا المكان قبل نقله إلى مجلد alma/.
// هذا الملف يلغي عامل الخدمة القديم في جوالات من ثبّتوا النسخة السابقة،
// فلا يتعارض مع النسخة الجديدة (alma/sw.js). لا يحذف أي ملفات محفوظة.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.registration.unregister()));
