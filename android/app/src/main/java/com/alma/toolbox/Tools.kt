package com.alma.toolbox

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

enum class Tool(val id: String, val title: String, val emoji: String, val desc: String, val group: String, val color: Long) {
    PERIODIC("periodic", "التنبيه الدوري", "⏰", "تنبيه كل فترة خلال ساعات وأيام محددة", "الوقت والتنبيهات", 0xFFFFE4D6),
    SILENT("silent", "الصامت المجدول", "🔕", "صامت تلقائي بأوقات محددة وأوقات الصلاة والجمعة", "الوقت والتنبيهات", 0xFFE3E8FF),
    REMINDER("reminder", "التنبيه المتكرر", "🔁", "تذكيرات بتكرار مرن وغفوة ومنبه بملء الشاشة", "الوقت والتنبيهات", 0xFFDDF4E4),
    POMODORO("pomodoro", "بومودورو", "🍅", "جلسات تركيز واستراحات", "الوقت والتنبيهات", 0xFFFFE0E0),
    LEDGER("ledger", "سجل الحسابات", "📒", "دفتر له وعليه بمجموعات وعملات وإرسال الكشف", "المال", 0xFFFFF3C4),
    CHECK("check", "فحص الهاتف", "📱", "فحص العتاد والحساسات والبطارية", "الجهاز والشبكة", 0xFFDDF1FF),
    SPEED("speed", "سرعة الإنترنت", "🚀", "قياس التنزيل والرفع والاستجابة", "الجهاز والشبكة", 0xFFE8E0FF),
    SIGNAL("signal", "قوة الإشارة", "📶", "إشارة الواي فاي وشبكة الاتصال لحظياً", "الجهاز والشبكة", 0xFFD9F7F2),
    HIJRI("hijri", "التاريخ الهجري", "🌙", "تاريخ اليوم والتحويل بين التقويمين", "أدوات متنوعة", 0xFFEDE7DB),
    QR("qr", "رمز QR", "🔳", "قراءة وإنشاء رموز QR والباركود", "أدوات متنوعة", 0xFFE6E6E6),
    MORSE("morse", "شفرة مورس", "📡", "تحويل النص وإرساله بالفلاش والصوت", "أدوات متنوعة", 0xFFFFF0D9);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

object Shortcuts {
    fun emojiIcon(emoji: String, bg: Long, size: Int = 216): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(bg.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.36f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
        val fm = p.fontMetrics
        c.drawText(emoji, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        return b
    }

    private fun info(ctx: Context, t: Tool): ShortcutInfoCompat {
        val i = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_TOOL, t.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return ShortcutInfoCompat.Builder(ctx, "tool_${t.id}")
            .setShortLabel(t.title).setLongLabel(t.title)
            .setIcon(IconCompat.createWithAdaptiveBitmap(emojiIcon(t.emoji, t.color)))
            .setIntent(i).build()
    }

    /** Returns false when the launcher doesn't support pinning. */
    fun pin(ctx: Context, t: Tool): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        return ShortcutManagerCompat.requestPinShortcut(ctx, info(ctx, t), null)
    }

    /** Long-press shortcuts on the app icon. */
    fun publishDynamic(ctx: Context) {
        try {
            val list = listOf(Tool.LEDGER, Tool.REMINDER, Tool.SILENT, Tool.POMODORO).map { info(ctx, it) }
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx).coerceAtLeast(1)))
        } catch (_: Exception) {}
    }
}
