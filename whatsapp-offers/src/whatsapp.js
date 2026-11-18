// دوال الاتصال بـ WhatsApp Cloud API (Graph API).
import "dotenv/config";

const TOKEN = process.env.WHATSAPP_TOKEN;
const PHONE_NUMBER_ID = process.env.PHONE_NUMBER_ID;
const LANG = process.env.TEMPLATE_LANG || "ar";
const BASE = "https://graph.facebook.com/v21.0";

function assertConfig() {
  if (!TOKEN || !PHONE_NUMBER_ID) {
    throw new Error(
      "ينقص WHATSAPP_TOKEN أو PHONE_NUMBER_ID في ملف .env"
    );
  }
}

async function send(payload) {
  assertConfig();
  const res = await fetch(`${BASE}/${PHONE_NUMBER_ID}/messages`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ messaging_product: "whatsapp", ...payload }),
  });
  const data = await res.json();
  if (!res.ok) {
    const msg = data?.error?.message || JSON.stringify(data);
    throw new Error(`فشل الإرسال (${res.status}): ${msg}`);
  }
  return data;
}

// رسالة نصية حرة — تُسمح فقط داخل نافذة الـ24 ساعة.
export function sendText(to, body) {
  return send({
    to,
    type: "text",
    text: { preview_url: false, body },
  });
}

// رسالة قالب معتمد — للإرسال خارج نافذة الـ24 ساعة.
// components اختيارية لتمرير متغيّرات ({{1}} ...) أو أزرار.
export function sendTemplate(to, templateName, components = []) {
  return send({
    to,
    type: "template",
    template: {
      name: templateName,
      language: { code: LANG },
      ...(components.length ? { components } : {}),
    },
  });
}

// اختصار: قالب فيه متغيّرات نصية في جسم الرسالة مثل {{1}} {{2}}.
export function sendTemplateWithBodyParams(to, templateName, params = []) {
  const components = params.length
    ? [
        {
          type: "body",
          parameters: params.map((text) => ({ type: "text", text: String(text) })),
        },
      ]
    : [];
  return sendTemplate(to, templateName, components);
}
