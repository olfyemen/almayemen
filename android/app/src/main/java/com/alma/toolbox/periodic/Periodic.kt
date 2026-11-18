package com.alma.toolbox.periodic

import android.content.Context
import androidx.core.app.NotificationCompat
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.fmtTime
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

@Serializable
data class PeriodicAlert(
    val id: Long,
    val name: String = "تنبيه دوري",
    val days: Set<Int> = (1..7).toSet(),
    val startMin: Int = 8 * 60,
    val endMin: Int = 22 * 60,
    val intervalMin: Int = 60,
    val sound: String? = null,
    val vibrate: Boolean = true,
    val enabled: Boolean = true,
    val showTime: Boolean = true,
    /** Westminster («Big Ben») chimes instead of a ringtone; strikes = the hour. */
    val chime: Boolean = false,
)

@Serializable
data class PeriodicData(val alerts: List<PeriodicAlert> = emptyList())

object PeriodicScheduler {
    fun nextFire(a: PeriodicAlert, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        if (!a.enabled || a.days.isEmpty() || a.intervalMin <= 0) return null
        for (off in -1L..8L) {
            val d = now.toLocalDate().plusDays(off)
            if (d.dayOfWeek.value !in a.days) continue
            val start = d.atStartOfDay().plusMinutes(a.startMin.toLong())
            var endM = a.endMin
            if (endM <= a.startMin) endM += 1440 // window crosses midnight
            val end = d.atStartOfDay().plusMinutes(endM.toLong())
            var t = start
            if (!t.isAfter(now)) {
                val k = Duration.between(start, now).toMinutes() / a.intervalMin + 1
                t = start.plusMinutes(k * a.intervalMin)
            }
            if (!t.isAfter(end) && t.isAfter(now)) return t
        }
        return null
    }

    private fun uri(id: Long) = "alma://periodic/$id"

    fun schedule(ctx: Context, a: PeriodicAlert) {
        val n = nextFire(a)
        if (n == null) Alarms.cancel(ctx, uri(a.id))
        else Alarms.set(ctx, uri(a.id), n.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    fun scheduleAll(ctx: Context) = Stores.periodic.value.alerts.forEach { schedule(ctx, it) }

    fun cancel(ctx: Context, id: Long) = Alarms.cancel(ctx, uri(id))

    fun fire(ctx: Context, id: Long) {
        val a = Stores.periodic.value.alerts.firstOrNull { it.id == id } ?: return
        if (!a.enabled) return
        val now = LocalDateTime.now()
        val nowMin = now.hour * 60 + now.minute
        if (a.chime) {
            // round to the nearest minute so 7:59:58 still counts as 8:00
            val r = now.plusSeconds(30)
            com.alma.toolbox.core.Chime.start(ctx, a.name, r.hour, r.minute)
            schedule(ctx, a); return
        }
        val ch = Notify.channelFor(ctx, a.sound, a.vibrate)
        val next = nextFire(a, now.plusSeconds(30))
        val text = buildString {
            if (a.showTime) append("الساعة الآن ${fmtTime(nowMin)}")
            if (next != null) { if (isNotEmpty()) append(" · "); append("التالي ${fmtTime(next.hour * 60 + next.minute)}") }
        }
        val n = NotificationCompat.Builder(ctx, ch)
            .setSmallIcon(com.alma.toolbox.R.drawable.ic_notify)
            .setContentTitle("⏰ ${a.name}")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(Notify.openApp(ctx, Tool.PERIODIC.id))
            .build()
        if (Notify.canPost(ctx)) Notify.nm(ctx).notify((id % 100000).toInt() + 1000, n)
        schedule(ctx, a)
    }
}
