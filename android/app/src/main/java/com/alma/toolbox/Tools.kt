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
    PRAYER("prayer", "مواقيت الصلاة", "🕌", "المواقيت والأذان: المدينة المنورة أو الجامع الكبير بصنعاء", "الوقت والتنبيهات", 0xFFE0F2E9),
    ADHKAR("adhkar", "الأذكار والأدعية", "📿", "الصحيفة السجادية على دعاء اليوم وحصن المسلم", "الوقت والتنبيهات", 0xFFF3E8D2),
    POMODORO("pomodoro", "بومودورو", "🍅", "جلسات تركيز واستراحات", "الوقت والتنبيهات", 0xFFFFE0E0),
    LEDGER("ledger", "سجل الحسابات", "📒", "دفتر له وعليه بمجموعات وعملات وإرسال الكشف", "المال", 0xFFFFF3C4),
    TRANSFER("transfer", "نقل الملفات", "📲", "إرسال ملفات ومجلدات وغرف دردشة عبر الواي فاي والهوتسبوت", "الجهاز والشبكة", 0xFFD6F5FF),
    CHECK("check", "فحص الهاتف", "📱", "فحص العتاد والحساسات والبطارية", "الجهاز والشبكة", 0xFFDDF1FF),
    SPEED("speed", "سرعة الإنترنت", "🚀", "قياس التنزيل والرفع والاستجابة", "الجهاز والشبكة", 0xFFE8E0FF),
    SIGNAL("signal", "قوة الإشارة", "📶", "إشارة الواي فاي وشبكة الاتصال لحظياً", "الجهاز والشبكة", 0xFFD9F7F2),
    HIJRI("hijri", "التاريخ الهجري", "🌙", "تاريخ اليوم والتحويل بين التقويمين", "أدوات متنوعة", 0xFFEDE7DB),
    QR("qr", "رمز QR", "🔳", "قراءة وإنشاء رموز QR والباركود", "أدوات متنوعة", 0xFFE6E6E6),
    MORSE("morse", "شفرة مورس", "📡", "تحويل النص وإرساله بالفلاش والصوت", "أدوات متنوعة", 0xFFFFF0D9);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/** User-customisable order & visibility of tools on the home screen. */
object ToolOrder {
    /** Default order shown until the user changes it (edit this list to change the default). */
    val DEFAULT: List<Tool> = Tool.entries.toList()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", 0)

    fun isCustom(ctx: Context) = prefs(ctx).contains("order") || prefs(ctx).contains("hidden")

    fun order(ctx: Context): List<Tool> {
        val saved = prefs(ctx).getString("order", null)?.split(",")?.mapNotNull { Tool.of(it) } ?: return DEFAULT
        return saved + DEFAULT.filter { it !in saved } // tools added in later versions go at the end
    }

    fun hidden(ctx: Context): Set<Tool> = prefs(ctx).getString("hidden", "")!!.split(",").mapNotNull { Tool.of(it) }.toSet()

    fun save(ctx: Context, order: List<Tool>, hidden: Set<Tool>) = prefs(ctx).edit()
        .putString("order", order.joinToString(",") { it.id }).putString("hidden", hidden.joinToString(",") { it.id }).apply()

    fun reset(ctx: Context) = prefs(ctx).edit().clear().apply()
}

object Credits {
    const val DESIGNER = "تصميم م/ماهر العلفي"
    const val PHONE = "00967777408089"
    const val DUA = "لوجه الله * نسألكم الدعاء"
    const val SLOGAN = "نبدأ من حيث انتهى الآخرون - يد ويد"
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
