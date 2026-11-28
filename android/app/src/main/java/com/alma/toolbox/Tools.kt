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
    // default order on the home screen
    PERIODIC("periodic", "التنبيه الدوري", "⏰", "تنبيه كل فترة خلال ساعات وأيام محددة", "الوقت والتنبيهات", 0xFFFFE4D6),
    ROZNAMA("roznama", "الروزنامة اليومية", "📅", "روزنامة ١٤٤٨هـ: تأمل ومثل يمني ومعالم زراعية وحكاية وحكمة كل يوم", "الوقت والتنبيهات", 0xFFE9F0FF),
    PRAYER("prayer", "مواقيت الصلاة", "🕌", "المواقيت والأذان: المدينة المنورة أو الجامع الكبير بصنعاء", "الوقت والتنبيهات", 0xFFE0F2E9),
    SILENT("silent", "الصامت المجدول", "🔕", "صامت تلقائي بأوقات محددة وأوقات الصلاة والجمعة", "الوقت والتنبيهات", 0xFFE3E8FF),
    REMINDER("reminder", "التنبيه المتكرر", "🔁", "تذكيرات بتكرار مرن وغفوة ومنبه بملء الشاشة", "الوقت والتنبيهات", 0xFFDDF4E4),
    POMODORO("pomodoro", "بومودورو", "🍅", "جلسات تركيز واستراحات", "الوقت والتنبيهات", 0xFFFFE0E0),
    ADHKAR("adhkar", "الأذكار والأدعية", "📿", "الصحيفة السجادية على دعاء اليوم وحصن المسلم", "الوقت والتنبيهات", 0xFFF3E8D2),
    LEDGER("ledger", "سجل حسابات الما", "📒", "دفتر له وعليه بمجموعات وعملات وإرسال الكشف", "المال", 0xFFFFF3C4),
    ZAKAT("zakat", "حاسبة الزكاة", "🧮", "النقد والذهب والفضة وعروض التجارة", "المال", 0xFFE6F4D7),
    ALMAHER("almaher", "حاسبة الماهر", "🧮", "حاسبة الكاشير والمحاسب: مبسطة وعلمية وسجل 200 عملية", "المال", 0xFFE0F2E9),
    TRANSFER("transfer", "نقل الملفات", "📲", "إرسال ملفات ومجلدات وغرف دردشة عبر الواي فاي والهوتسبوت", "الجهاز والشبكة", 0xFFD6F5FF),
    SPEED("speed", "سرعة الإنترنت", "🚀", "قياس التنزيل والرفع والاستجابة", "الجهاز والشبكة", 0xFFE8E0FF),
    SIGNAL("signal", "قوة الإشارة", "📶", "إشارة الواي فاي وشبكة الاتصال لحظياً", "الجهاز والشبكة", 0xFFD9F7F2),
    DATA("data", "توفير النت", "📉", "استهلاك كل تطبيق، وحجب ما تريد، وأوضاع توفير وإيقاف الخلفية", "الجهاز والشبكة", 0xFFDDF7EA),
    CHECK("check", "فحص الهاتف", "📱", "فحص العتاد والحساسات والبطارية", "الجهاز والشبكة", 0xFFDDF1FF),
    NOTES("notes", "الملاحظات والمهام", "📝", "ملاحظات ومهام مع تذكير", "أدوات متنوعة", 0xFFFFF6CC),
    QR("qr", "رمز QR", "🔳", "قراءة وإنشاء رموز QR والباركود", "أدوات متنوعة", 0xFFE6E6E6),
    OCCASIONS("occasions", "المناسبات", "🎂", "أعياد الميلاد والذكريات بالهجري والميلادي", "أدوات متنوعة", 0xFFFFE3F1),
    HIJRI("hijri", "التاريخ الهجري", "🌙", "تاريخ اليوم والتحويل بين التقويمين", "أدوات متنوعة", 0xFFEDE7DB),
    MORSE("morse", "شفرة مورس", "📡", "مورس بالفلاش والصوت، وشفرات أخرى، ولعبة تعلّم", "أدوات متنوعة", 0xFFFFF0D9),
    MEMORIES("memories", "الذكريات", "📔", "ذكريات ومذكرات بالصور، كبسولة زمنية، وقفل برمز", "أدوات متنوعة", 0xFFFCE7D6),
    RECORDER("recorder", "مسجّل الصوت", "🎙️", "تسجيل سريع وحفظ ومشاركة", "أدوات متنوعة", 0xFFE7E9F5);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/** User-customisable order & visibility of tools on the home screen. */
object ToolOrder {
    /** Default order (the designer's choice). */
    val DEFAULT: List<Tool> = Tool.entries.toList()
    val DEFAULT_GROUPS = listOf("الوقت والتنبيهات", "المال", "الجهاز والشبكة", "أدوات متنوعة")

    /** auto = most used, fav = the user's favourites, off = no top section */
    enum class Top { AUTO, FAV, OFF }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", 0)

    fun order(ctx: Context): List<Tool> {
        val saved = prefs(ctx).getString("order", null)?.split(",")?.mapNotNull { Tool.of(it) } ?: return DEFAULT
        return saved + DEFAULT.filter { it !in saved } // tools added in later versions go at the end
    }

    fun groups(ctx: Context): List<String> {
        val saved = prefs(ctx).getString("groups", null)?.split(",")?.filter { it in DEFAULT_GROUPS } ?: return DEFAULT_GROUPS
        return saved + DEFAULT_GROUPS.filter { it !in saved }
    }

    fun hidden(ctx: Context): Set<Tool> = prefs(ctx).getString("hidden", "")!!.split(",").mapNotNull { Tool.of(it) }.toSet()
    fun favorites(ctx: Context): List<Tool> = prefs(ctx).getString("fav", "")!!.split(",").mapNotNull { Tool.of(it) }
    fun top(ctx: Context): Top = runCatching { Top.valueOf(prefs(ctx).getString("top", "AUTO")!!) }.getOrDefault(Top.AUTO)

    fun save(ctx: Context, order: List<Tool>, groups: List<String>, hidden: Set<Tool>, fav: List<Tool>, top: Top) = prefs(ctx).edit()
        .putString("order", order.joinToString(",") { it.id }).putString("groups", groups.joinToString(","))
        .putString("hidden", hidden.joinToString(",") { it.id }).putString("fav", fav.joinToString(",") { it.id })
        .putString("top", top.name).apply()

    fun reset(ctx: Context) {
        val use = prefs(ctx).all.filterKeys { it.startsWith("use_") }
        prefs(ctx).edit().clear().apply()
        prefs(ctx).edit().apply { use.forEach { (k, v) -> putInt(k, v as Int) } }.apply()
    }

    /** Counts openings so the top section can show the most used tools (kept tiny: one int per tool). */
    fun used(ctx: Context, t: Tool) { val k = "use_" + t.id; prefs(ctx).edit().putInt(k, prefs(ctx).getInt(k, 0) + 1).apply() }

    fun mostUsed(ctx: Context, n: Int = 4): List<Tool> =
        Tool.entries.map { it to prefs(ctx).getInt("use_" + it.id, 0) }.filter { it.second >= 2 }.sortedByDescending { it.second }.take(n).map { it.first }
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
