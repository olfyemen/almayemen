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
)

@Serializable data class OccasionsData(val items: List<Occasion> = emptyList(), val notify: Boolean = true)

object Occasions {
    /** Next gregorian date this occasion falls on, from [today]. */
    fun next(o: Occasion, today: LocalDate = LocalDate.now()): LocalDate? = runCatching {
        if (!o.hijri) {
            val d = LocalDate.parse(o.date)
            if (!o.yearly) return@runCatching d
            var n = d.withYear(today.year)
            if (n.isBefore(today)) n = n.plusYears(1)
            n
        } else {
            val (y, m, dd) = o.date.split("-").map { it.toInt() }
            if (!o.yearly) return@runCatching Hijri.toGregorian(HDate(y, m, dd))
            val hy = Hijri.fromGregorian(today).y
            var n = Hijri.toGregorian(HDate(hy, m, dd.coerceAtMost(Hijri.monthLength(hy, m))))
            if (n.isBefore(today)) n = Hijri.toGregorian(HDate(hy + 1, m, dd.coerceAtMost(Hijri.monthLength(hy + 1, m))))
            n
        }
    }.getOrNull()

    fun daysLeft(o: Occasion, today: LocalDate = LocalDate.now()) = next(o, today)?.let { ChronoUnit.DAYS.between(today, it) }

    /** Years since the first date (ages, anniversaries). */
    fun years(o: Occasion, today: LocalDate = LocalDate.now()): Int? = runCatching {
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
