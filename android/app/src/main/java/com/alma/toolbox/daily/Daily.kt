package com.alma.toolbox.daily

import android.content.Context
import androidx.core.app.NotificationCompat
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import com.alma.toolbox.tools.HDate
import com.alma.toolbox.tools.Hijri
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// ---------------- notes & tasks ----------------
@Serializable
data class Note(
    val id: Long,
    val text: String,
    val task: Boolean = false,
    val done: Boolean = false,
    val pinned: Boolean = false,
    val created: Long = System.currentTimeMillis(),
    /** epoch millis of a reminder (0 = none); stored as a Reminder with the same id */
    val remindAt: Long = 0,
)

@Serializable data class NotesData(val notes: List<Note> = emptyList())

/** Importing notes from other apps (Android gives no shared «notes» store, so: shared text and exported files). */
object NotesImport {
    fun addText(text: String, title: String = ""): Note? {
        val body = text.trim(); if (body.isEmpty()) return null
        val n = Note(com.alma.toolbox.core.newId(), if (title.isNotBlank() && !body.startsWith(title)) "$title\n$body" else body)
        Stores.notes.update { it.copy(notes = it.notes + n) }
        return n
    }

    /** Google Keep (Takeout) JSON, or any text / markdown / html file. Returns how many notes were added. */
    fun importFile(name: String, content: String): Int {
        val trimmed = content.trim()
        if (name.endsWith(".json", true) && trimmed.startsWith("{")) runCatching {
            val o = com.alma.toolbox.core.AppJson.parseToJsonElement(trimmed) as kotlinx.serialization.json.JsonObject
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            if (str("isTrashed") == "true") return 0
            val list = (o["listContent"] as? kotlinx.serialization.json.JsonArray)?.joinToString("\n") { item ->
                val io = item as kotlinx.serialization.json.JsonObject
                (if ((io["isChecked"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") "☑ " else "☐ ") + ((io["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
            } ?: ""
            val body = listOf(str("textContent"), list).filter { it.isNotBlank() }.joinToString("\n")
            val created = str("createdTimestampUsec").toLongOrNull()?.div(1000) ?: System.currentTimeMillis()
            val text = (if (str("title").isNotBlank()) str("title") + "\n" else "") + body
            if (text.isBlank()) return 0
            val n = Note(com.alma.toolbox.core.newId(), text.trim(), task = list.isNotEmpty() && body == list, pinned = str("isPinned") == "true", created = created)
            Stores.notes.update { it.copy(notes = it.notes + n) }
            return 1
        }
        val plain = if (name.endsWith(".html", true) || name.endsWith(".htm", true))
            android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_LEGACY).toString() else content
        return if (addText(plain, name.substringBeforeLast('.')) != null) 1 else 0
    }
}

// ---------------- occasions ----------------
@Serializable
data class Occasion(
    val id: Long,
    val name: String,
    val emoji: String = "🎂",
    /** gregorian yyyy-MM-dd, or for hijri: y-m-d with m 0..11 */
    val date: String,
    val hijri: Boolean = false,
    val yearly: Boolean = true,
    val notifyDays: Int = 1,
    /** once | days | weekly | monthly | yearly ("" = old data: yearly flag decides) */
    val repeat: String = "",
    /** every N units of [repeat] */
    val every: Int = 1,
)

fun Occasion.repeatKind() = repeat.ifEmpty { if (yearly) "yearly" else "once" }

val RepeatKinds = linkedMapOf("once" to "مرة واحدة", "days" to "كل عدد أيام", "weekly" to "أسبوعياً", "monthly" to "شهرياً", "yearly" to "سنوياً")

fun Occasion.repeatLabel(): String { val n = every.coerceAtLeast(1); return when (repeatKind()) {
    "once" -> "مرة واحدة"; "days" -> if (n == 1) "كل يوم" else "كل $n أيام"
    "weekly" -> if (n == 1) "كل أسبوع" else "كل $n أسابيع"; "monthly" -> if (n == 1) "كل شهر" else "كل $n أشهر"
    else -> if (n == 1) "كل سنة" else "كل $n سنوات" } }

@Serializable data class OccasionsData(val items: List<Occasion> = emptyList(), val notify: Boolean = true)

object Occasions {
    /** Next gregorian date this occasion falls on (today counts), for every repeat kind. */
    fun next(o: Occasion, today: LocalDate = LocalDate.now()): LocalDate? = runCatching {
        val n = o.every.coerceAtLeast(1).toLong()
        if (!o.hijri) {
            val d = LocalDate.parse(o.date)
            when (o.repeatKind()) {
                "once" -> d
                "days" -> if (!d.isBefore(today)) d else d.plusDays(((ChronoUnit.DAYS.between(d, today) + n - 1) / n) * n)
                "weekly" -> if (!d.isBefore(today)) d else d.plusDays(((ChronoUnit.DAYS.between(d, today) + 7 * n - 1) / (7 * n)) * 7 * n)
                "monthly" -> { var k = 0L; var x = d; while (x.isBefore(today)) { k += n; x = d.plusMonths(k) }; x }
                else -> { var k = 0L; var x = d; while (x.isBefore(today)) { k += n; x = d.plusYears(k) }; x }
            }
        } else {
            val (y, m, dd) = o.date.split("-").map { it.toInt() }
            fun at(hy: Int, hm: Int) = Hijri.toGregorian(HDate(hy, hm, dd.coerceAtMost(Hijri.monthLength(hy, hm))))
            val first = at(y, m)
            when (o.repeatKind()) {
                "once" -> first
                "days" -> if (!first.isBefore(today)) first else first.plusDays(((ChronoUnit.DAYS.between(first, today) + n - 1) / n) * n)
                "weekly" -> if (!first.isBefore(today)) first else first.plusDays(((ChronoUnit.DAYS.between(first, today) + 7 * n - 1) / (7 * n)) * 7 * n)
                "monthly" -> { var hy = y; var hm = m; var x = first; var guard = 0
                    while (x.isBefore(today) && guard++ < 2000) { hm += n.toInt(); while (hm > 11) { hm -= 12; hy++ }; x = at(hy, hm) }; x }
                else -> { var hy = y; var x = first; var guard = 0
                    while (x.isBefore(today) && guard++ < 500) { hy += n.toInt(); x = at(hy, m) }; x }
            }
        }
    }.getOrNull()

    fun daysLeft(o: Occasion, today: LocalDate = LocalDate.now()) = next(o, today)?.let { ChronoUnit.DAYS.between(today, it) }

    /** Years since the first date (ages, anniversaries). */
    fun years(o: Occasion, today: LocalDate = LocalDate.now()): Int? = if (o.repeatKind() != "yearly") null else runCatching {
        if (o.hijri) { val y = o.date.substringBefore('-').toInt(); val n = next(o, today) ?: return@runCatching null; Hijri.fromGregorian(n).y - y }
        else { val d = LocalDate.parse(o.date); (next(o, today) ?: return@runCatching null).year - d.year }
    }.getOrNull()?.takeIf { it > 0 }

    /** Daily check at 9:00 — notifies occasions that are today or within their notice period. */
    fun schedule(ctx: Context) {
        val d = Stores.occasions.value
        if (!d.notify || d.items.isEmpty()) { Alarms.cancel(ctx, "alma://occasions/0"); return }
        var at = LocalDate.now().atTime(9, 0)
        if (!at.isAfter(java.time.LocalDateTime.now())) at = at.plusDays(1)
        Alarms.set(ctx, "alma://occasions/0", at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    fun check(ctx: Context) {
        Stores.occasions.value.items.forEach { o ->
            val left = daysLeft(o) ?: return@forEach
            if (left == 0L || left == o.notifyDays.toLong()) {
                val n = NotificationCompat.Builder(ctx, Notify.channelFor(ctx, null, true)).setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("${o.emoji} ${o.name}").setContentText(if (left == 0L) "اليوم!" else "بعد $left يوم")
                    .setAutoCancel(true).setContentIntent(Notify.openApp(ctx, Tool.OCCASIONS.id, 41)).build()
                if (Notify.canPost(ctx)) Notify.nm(ctx).notify((o.id % 100000).toInt() + 400000, n)
            }
        }
        schedule(ctx)
    }
}

// ---------------- zakat ----------------
object Zakat {
    const val GOLD_NISAB_G = 85.0
    const val SILVER_NISAB_G = 595.0
    const val RATE = 0.025

    data class Result(val total: Double, val nisab: Double, val due: Boolean, val zakat: Double)

    /**
     * Values are in one currency. Gold is converted to 24k equivalent by karat.
     * Debts due now are subtracted.
     */
    fun compute(cash: Double, gold24: Double, gold21: Double, gold18: Double, silver: Double, trade: Double, receivable: Double, debts: Double,
                goldPrice: Double, silverPrice: Double, nisabBySilver: Boolean): Result {
        val goldPure = gold24 + gold21 * 21 / 24 + gold18 * 18 / 24
        val total = cash + goldPure * goldPrice + silver * silverPrice + trade + receivable - debts
        val nisab = if (nisabBySilver) SILVER_NISAB_G * silverPrice else GOLD_NISAB_G * goldPrice
        val due = nisab > 0 && total >= nisab
        return Result(total, nisab, due, if (due) total * RATE else 0.0)
    }
}
