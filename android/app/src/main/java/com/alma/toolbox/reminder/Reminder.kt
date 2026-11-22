package com.alma.toolbox.reminder

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.fmtTime
import kotlinx.serialization.Serializable
import java.time.*
import java.time.temporal.ChronoUnit

@Serializable
data class Reminder(
    val id: Long,
    val title: String = "",
    val note: String = "",
    val startAt: Long = System.currentTimeMillis() + 3600_000,
    /** none | minute | hour | day | week | month | year | weekdays */
    val repeat: String = "none",
    val every: Int = 1,
    val weekdays: Set<Int> = emptySet(),
    /** for minute/hour repeats: only between these times */
    val limitHours: Boolean = false,
    val fromMin: Int = 8 * 60,
    val toMin: Int = 22 * 60,
    /** never | until | count */
    val endType: String = "never",
    val until: Long = 0,
    val count: Int = 10,
    val fired: Int = 0,
    val sound: String? = null,
    val vibrate: Boolean = true,
    val alarm: Boolean = false,
    val preMin: Int = 0,
    val snoozeMin: Int = 10,
    val enabled: Boolean = true,
    val nextAt: Long = 0,
    /** re-alert until «تم» is pressed: how many extra times (0 = off) and every how many minutes */
    val nagTimes: Int = 0,
    val nagEvery: Int = 5,
)

@Serializable
data class ReminderData(val items: List<Reminder> = emptyList())

val RepeatNames = linkedMapOf(
    "none" to "مرة واحدة", "minute" to "كل دقائق", "hour" to "كل ساعات", "day" to "يومياً",
    "weekdays" to "أيام محددة", "week" to "أسبوعياً", "month" to "شهرياً", "year" to "سنوياً",
)

fun Reminder.repeatLabel(): String = when (repeat) {
    "none" -> "مرة واحدة"
    "minute" -> "كل $every دقيقة"
    "hour" -> if (every == 1) "كل ساعة" else "كل $every ساعات"
    "day" -> if (every == 1) "يومياً" else "كل $every أيام"
    "week" -> if (every == 1) "أسبوعياً" else "كل $every أسابيع"
    "month" -> if (every == 1) "شهرياً" else "كل $every أشهر"
    "year" -> "سنوياً"
    "weekdays" -> com.alma.toolbox.ui.daysLabel(weekdays)
    else -> repeat
}

private fun ldt(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

object ReminderScheduler {
    fun occurrenceAfter(r: Reminder, after: LocalDateTime): LocalDateTime? {
        val start = ldt(r.startAt)
        val ev = r.every.coerceAtLeast(1).toLong()
        var t: LocalDateTime? = when (r.repeat) {
            "none" -> if (start.isAfter(after)) start else null
            "weekdays" -> {
                if (r.weekdays.isEmpty()) null else {
                    val base = if (after.isBefore(start)) start.toLocalDate() else after.toLocalDate()
                    (0L..8L).map { base.plusDays(it).atTime(start.toLocalTime()) }
                        .firstOrNull { it.dayOfWeek.value in r.weekdays && it.isAfter(after) && !it.isBefore(start) }
                }
            }
            else -> {
                if (start.isAfter(after)) start else {
                    val unit = when (r.repeat) {
                        "minute" -> ChronoUnit.MINUTES; "hour" -> ChronoUnit.HOURS; "day" -> ChronoUnit.DAYS
                        "week" -> ChronoUnit.WEEKS; "month" -> ChronoUnit.MONTHS; else -> ChronoUnit.YEARS
                    }
                    val step = if (r.repeat == "year") 1 else ev
                    var k = unit.between(start, after) / step
                    var c = start.plus(k * step, unit)
                    while (!c.isAfter(after)) { k++; c = start.plus(k * step, unit) }
                    c
                }
            }
        }
        // restrict minute/hour repeats to a daily window
        if (t != null && r.limitHours && (r.repeat == "minute" || r.repeat == "hour")) {
            var guard = 0
            while (t != null && guard++ < 3000) {
                val m = t.hour * 60 + t.minute
                val inside = if (r.fromMin <= r.toMin) m in r.fromMin..r.toMin else (m >= r.fromMin || m <= r.toMin)
                if (inside) break
                t = occurrenceAfter(r.copy(limitHours = false), t)
            }
        }
        if (t != null && r.endType == "until" && r.until > 0 && ms(t) > r.until) return null
        if (r.endType == "count" && r.fired >= r.count) return null
        return t
    }

    private fun mainUri(id: Long) = "alma://reminder/$id"
    private fun preUri(id: Long) = "alma://reminderpre/$id"
    private fun snoozeUri(id: Long) = "alma://snooze/$id"

    fun schedule(ctx: Context, r0: Reminder): Reminder {
        val next = if (r0.enabled) occurrenceAfter(r0, LocalDateTime.now()) else null
        val r = r0.copy(nextAt = next?.let { ms(it) } ?: 0)
        if (next == null) {
            Alarms.cancel(ctx, mainUri(r.id)); Alarms.cancel(ctx, preUri(r.id))
        } else {
            Alarms.set(ctx, mainUri(r.id), r.nextAt, alarmClock = r.alarm)
            val pre = r.nextAt - r.preMin * 60_000L
            if (r.preMin > 0 && pre > System.currentTimeMillis()) Alarms.set(ctx, preUri(r.id), pre) else Alarms.cancel(ctx, preUri(r.id))
        }
        return r
    }

    fun save(ctx: Context, r: Reminder) {
        val s = schedule(ctx, r)
        Stores.reminders.update { d -> d.copy(items = d.items.filter { it.id != r.id } + s) }
    }

    fun scheduleAll(ctx: Context) {
        Stores.reminders.update { d -> d.copy(items = d.items.map { schedule(ctx, it) }) }
    }

    fun delete(ctx: Context, id: Long) {
        stopNag(ctx, id)
        Alarms.cancel(ctx, mainUri(id)); Alarms.cancel(ctx, preUri(id)); Alarms.cancel(ctx, snoozeUri(id))
        Stores.reminders.update { d -> d.copy(items = d.items.filter { it.id != id }) }
        Notify.nm(ctx).cancel(notifId(id))
    }

    fun notifId(id: Long) = (id % 100000).toInt() + 200000

    fun fire(ctx: Context, id: Long, pre: Boolean, snooze: Boolean) {
        val r = Stores.reminders.value.items.firstOrNull { it.id == id } ?: return
        if (pre) {
            val n = NotificationCompat.Builder(ctx, Notify.channelFor(ctx, r.sound, r.vibrate))
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("⏳ بعد ${r.preMin} دقيقة: ${r.title}")
                .setContentText(r.note.ifBlank { "تذكير مسبق" })
                .setAutoCancel(true).setContentIntent(Notify.openApp(ctx, Tool.REMINDER.id))
                .build()
            if (Notify.canPost(ctx)) Notify.nm(ctx).notify(notifId(id) + 50000, n)
            return
        }
        if (!r.enabled && !snooze) return
        if (!snooze) {
            val updated = schedule(ctx, r.copy(fired = r.fired + 1))
            Stores.reminders.update { d -> d.copy(items = d.items.map { if (it.id == id) updated else it }) }
        }
        show(ctx, r)
        if (!snooze) startNag(ctx, r)
    }

    fun show(ctx: Context, r: Reminder) {
        if (r.alarm) {
            val i = Intent(ctx, AlarmSoundService::class.java).putExtra("id", r.id)
            try { ContextCompat.startForegroundService(ctx, i); return } catch (_: Exception) { /* fall back to a notification */ }
        }
        val now = LocalTime.now()
        val n = NotificationCompat.Builder(ctx, Notify.channelFor(ctx, r.sound, r.vibrate))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🔔 ${r.title.ifBlank { "تذكير" }}")
            .setContentText(r.note.ifBlank { fmtTime(now.hour * 60 + now.minute) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.note.ifBlank { r.repeatLabel() }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(Notify.openApp(ctx, Tool.REMINDER.id))
            .addAction(0, "تم ✓", Notify.action(ctx, "done/${r.id}"))
            .addAction(0, "غفوة ${r.snoozeMin} د", Notify.action(ctx, "snooze/${r.id}?m=${r.snoozeMin}"))
            .apply { if (r.repeat != "none") addAction(0, "إيقاف التكرار", Notify.action(ctx, "disable/${r.id}")) }
            .build()
        if (Notify.canPost(ctx)) Notify.nm(ctx).notify(notifId(r.id), n)
    }

    private fun nagPrefs(ctx: Context) = ctx.getSharedPreferences("reminder_nag", 0)

    /** Starts the «until confirmed» re-alerts for a reminder that just fired. */
    fun startNag(ctx: Context, r: Reminder) {
        if (r.nagTimes <= 0 || r.alarm) return
        nagPrefs(ctx).edit().putInt("left_${r.id}", r.nagTimes).apply()
        Alarms.set(ctx, "alma://nag/${r.id}", System.currentTimeMillis() + r.nagEvery * 60_000L)
    }

    fun stopNag(ctx: Context, id: Long) { nagPrefs(ctx).edit().remove("left_$id").apply(); Alarms.cancel(ctx, "alma://nag/$id") }

    fun nag(ctx: Context, id: Long) {
        val left = nagPrefs(ctx).getInt("left_$id", 0)
        val r = Stores.reminders.value.items.firstOrNull { it.id == id }
        if (left <= 0 || r == null) { stopNag(ctx, id); return }
        show(ctx, r)
        nagPrefs(ctx).edit().putInt("left_$id", left - 1).apply()
        if (left - 1 > 0) Alarms.set(ctx, "alma://nag/$id", System.currentTimeMillis() + r.nagEvery * 60_000L) else stopNag(ctx, id)
    }

    fun snooze(ctx: Context, id: Long, minutes: Int) {
        Notify.nm(ctx).cancel(notifId(id))
        try { ctx.startService(Intent(ctx, AlarmSoundService::class.java).setAction(AlarmSoundService.ACTION_STOP)) } catch (_: Exception) {}
        val r = Stores.reminders.value.items.firstOrNull { it.id == id } ?: return
        Alarms.set(ctx, snoozeUri(id), System.currentTimeMillis() + minutes * 60_000L, alarmClock = r.alarm)
    }

    fun done(ctx: Context, id: Long) { Notify.nm(ctx).cancel(notifId(id)); stopNag(ctx, id) }

    fun disable(ctx: Context, id: Long) {
        Notify.nm(ctx).cancel(notifId(id)); stopNag(ctx, id)
        val r = Stores.reminders.value.items.firstOrNull { it.id == id } ?: return
        save(ctx, r.copy(enabled = false))
    }
}
