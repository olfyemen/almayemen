// مخزن بسيط لجهات الاتصال في ملف JSON.
// لكل رقم نسجّل: أول/آخر مراسلة، وحالة الموافقة على العروض (consent).
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const FILE = resolve(__dirname, "../data/contacts.json");

function load() {
  if (!existsSync(FILE)) return {};
  try {
    return JSON.parse(readFileSync(FILE, "utf8"));
  } catch {
    return {};
  }
}

function save(db) {
  mkdirSync(dirname(FILE), { recursive: true });
  writeFileSync(FILE, JSON.stringify(db, null, 2), "utf8");
}

// يسجّل أن رقمًا راسلنا (رسالة واردة). يُنشئ السجل إن لم يوجد.
export function recordInbound(phone, name = "") {
  const db = load();
  const now = new Date().toISOString();
  if (!db[phone]) {
    db[phone] = {
      phone,
      name,
      firstContact: now,
      lastContact: now,
      lastInbound: now, // آخر رسالة واردة منه (تحدد نافذة 24 ساعة)
      consent: "unknown", // unknown | yes | no
      consentAt: null,
    };
  } else {
    db[phone].lastContact = now;
    db[phone].lastInbound = now;
    if (name) db[phone].name = name;
  }
  save(db);
  return db[phone];
}

// يحدّث حالة الموافقة: "yes" أو "no".
export function setConsent(phone, consent) {
  const db = load();
  if (!db[phone]) {
    db[phone] = {
      phone,
      name: "",
      firstContact: new Date().toISOString(),
      lastContact: new Date().toISOString(),
      lastInbound: null,
      consent,
      consentAt: new Date().toISOString(),
    };
  } else {
    db[phone].consent = consent;
    db[phone].consentAt = new Date().toISOString();
  }
  save(db);
  return db[phone];
}

export function getContact(phone) {
  return load()[phone] || null;
}

export function allContacts() {
  return Object.values(load());
}

// هل ما زلنا داخل نافذة الـ24 ساعة من آخر رسالة واردة؟
export function within24h(contact) {
  if (!contact?.lastInbound) return false;
  const diff = Date.now() - new Date(contact.lastInbound).getTime();
  return diff < 24 * 60 * 60 * 1000;
}
