// خادم Webhook: يستقبل الرسائل الواردة من واتساب،
// يسجّل الأرقام، ويلتقط موافقة/رفض العميل على العروض.
import "dotenv/config";
import express from "express";
import { recordInbound, setConsent, within24h, getContact } from "./store.js";
import { sendText } from "./whatsapp.js";

const app = express();
app.use(express.json());

const VERIFY_TOKEN = process.env.VERIFY_TOKEN || "my-verify-token-123";
const PORT = process.env.PORT || 3000;

// كلمات يفهمها النظام كموافقة أو رفض (ردّ العميل على رسالة إعادة التفعيل).
const YES = ["نعم", "موافق", "اوافق", "أوافق", "اشترك", "أشترك", "yes", "y", "اي", "أي", "تمام"];
const NO = ["لا", "الغاء", "إلغاء", "ايقاف", "إيقاف", "stop", "no", "n", "الغ"];

function normalize(t = "") {
  return t.trim().toLowerCase();
}

// 1) تحقق الـWebhook (تطلبه Meta مرة واحدة عند الربط).
app.get("/webhook", (req, res) => {
  const mode = req.query["hub.mode"];
  const token = req.query["hub.verify_token"];
  const challenge = req.query["hub.challenge"];
  if (mode === "subscribe" && token === VERIFY_TOKEN) {
    console.log("✅ تم التحقق من الـWebhook");
    return res.status(200).send(challenge);
  }
  return res.sendStatus(403);
});

// 2) استقبال الرسائل والأحداث الواردة.
app.post("/webhook", async (req, res) => {
  res.sendStatus(200); // نرد بسرعة أولًا كما تتطلب Meta

  try {
    const entry = req.body?.entry?.[0];
    const change = entry?.changes?.[0];
    const value = change?.value;
    const message = value?.messages?.[0];
    if (!message) return; // قد يكون تحديث حالة تسليم فقط

    const from = message.from; // رقم العميل بصيغة دولية بدون +
    const profileName = value?.contacts?.[0]?.profile?.name || "";
    recordInbound(from, profileName);

    // نص الرسالة أو ردّ الزر
    let text = "";
    if (message.type === "text") text = message.text?.body || "";
    else if (message.type === "button") text = message.button?.text || "";
    else if (message.type === "interactive") {
      text =
        message.interactive?.button_reply?.title ||
        message.interactive?.list_reply?.title ||
        "";
    }

    const t = normalize(text);
    console.log(`📩 من ${from} (${profileName}): ${text}`);

    // التقاط الموافقة/الرفض
    if (YES.some((w) => t === normalize(w) || t.includes(normalize(w)))) {
      setConsent(from, "yes");
      await safeReply(from, "تم تسجيلك ✅ سنوافيك بأحدث العروض. لإيقافها أرسل: إلغاء");
      return;
    }
    if (NO.some((w) => t === normalize(w) || t.includes(normalize(w)))) {
      setConsent(from, "no");
      await safeReply(from, "تم إيقاف رسائل العروض. شكرًا لك 🌟");
      return;
    }

    // ردّ افتراضي (داخل نافذة 24 ساعة فقط يمكن الرد بنص حر)
    const contact = getContact(from);
    if (within24h(contact)) {
      await safeReply(
        from,
        "شكرًا لتواصلك معنا 🌟 هل ترغب باستقبال عروضنا وخصوماتنا؟ أرسل: نعم / لا"
      );
    }
  } catch (err) {
    console.error("خطأ في معالجة الرسالة:", err.message);
  }
});

async function safeReply(to, body) {
  try {
    await sendText(to, body);
  } catch (e) {
    console.error("تعذّر الرد:", e.message);
  }
}

app.get("/", (_req, res) => res.send("WhatsApp Offers service يعمل ✅"));

app.listen(PORT, () => {
  console.log(`🚀 الخادم يعمل على المنفذ ${PORT}`);
  console.log(`   رابط الـWebhook: http://localhost:${PORT}/webhook`);
});
