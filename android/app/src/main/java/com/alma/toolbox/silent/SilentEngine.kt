package com.alma.toolbox.silent

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.fmtTime
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

const val JUMUAH = 9

@Serializable
data class SilentRule(
    val id: Long,
    val name: String = "",
    val kind: String = "time", // time | prayer
    val days: Set<Int> = (1..7).toSet(),
    val startMin: Int = 19 * 60,
    val endMin: Int = 22 * 60,
    /** 0 fajr, 2 dhuhr, 3 asr, 4 maghrib, 5 isha, 9 jumuah */
    val prayers: Set<Int> = setOf(0, 2, 3, 4, 5),
    val beforeMin: Int = 0,
    val afterMin: Int = 20,
    val vibrate: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class SilentData(
    val enabled: Boolean = true,
    val rules: List<SilentRule> = emptyList(),
    val prayer: PrayerSettings = PrayerSettings(),
    val engaged: Boolean = false,
    val prevMode: Int = AudioManager.RINGER_MODE_NORMAL,
    val appliedMode: Int = -1,
)

data class SilentInterval(val start: LocalDateTime, val end: LocalDateTime, val vibrate: Boolean, val label: String)

fun prayerLabel(p: Int) = if (p == JUMUAH) "صلاة الجمعة" else "صلاة " + PrayerCalc.names[p]

object SilentEngine {
    private const val NOTIF_ID = 77

    fun intervals(d: SilentData, from: LocalDate, days: Int): List<SilentInterval> {
        val out = ArrayList<SilentInterval>()
        for (i in 0 until days) {
            val date = from.plusDays(i.toLong())
            val dow = date.dayOfWeek.value
            val times by lazy { PrayerCalc.times(date, d.prayer) }
            d.rules.filter { it.enabled }.forEach { r ->
                if (r.kind == "time") {
                    if (dow in r.days) {
                        val s = date.atStartOfDay().plusMinutes(r.startMin.toLong())
                        var e = date.atStartOfDay().plusMinutes(r.endMin.toLong())
                        if (!e.isAfter(s)) e = e.plusDays(1)
                        out += SilentInterval(s, e, r.vibrate, r.name.ifBlank { "وقت مجدول" })
                    }
                } else {
                    r.prayers.forEach { p ->
                        val idx = when {
                            p == JUMUAH -> if (date.dayOfWeek == DayOfWeek.FRIDAY) 2 else -1
                            p == 2 && date.dayOfWeek == DayOfWeek.FRIDAY && JUMUAH in r.prayers -> -1
                            dow in r.days -> p
                            else -> -1
                        }
                        if (idx >= 0) {
                            val at = date.atStartOfDay().plusSeconds((times[idx] * 3600).toLong())
                            out += SilentInterval(at.minusMinutes(r.beforeMin.toLong()), at.plusMinutes(r.afterMin.toLong()), r.vibrate, prayerLabel(p))
                        }
                    }
                }
            }
        }
        return out.sortedBy { it.start }
    }

    fun hasPolicyAccess(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    private fun setRinger(ctx: Context, mode: Int): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        return try { am.ringerMode = mode; true } catch (e: SecurityException) {
            if (mode == AudioManager.RINGER_MODE_SILENT) try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; true } catch (_: Exception) { false } else false
        }
    }

    /** Evaluate rules now, switch the ringer and schedule the next boundary. */
    @Synchronized
    fun apply(ctx: Context) {
        val d = Stores.silent.value
        val now = LocalDateTime.now()
        val list = if (d.enabled) intervals(d, now.toLocalDate().minusDays(1), 9) else emptyList()
        val active = list.filter { !it.start.isAfter(now) && it.end.isAfter(now) }
        val am = ctx.getSystemService(AudioManager::class.java)

        if (active.isNotEmpty()) {
            val mode = if (active.all { it.vibrate }) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_SILENT
            val prev = if (d.engaged) d.prevMode else am.ringerMode
            if (am.ringerMode != mode) setRinger(ctx, mode)
            Stores.silent.update { it.copy(engaged = true, prevMode = prev, appliedMode = am.ringerMode) }
            val until = active.maxOf { it.end }
            val n = NotificationCompat.Builder(ctx, Notify.CH_INFO)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (mode == AudioManager.RINGER_MODE_SILENT) "🔕 الصامت مفعّل" else "📳 الاهتزاز مفعّل")
                .setContentText("${active.joinToString("، ") { it.label }} · حتى ${fmtTime(until.hour * 60 + until.minute)}")
                .setOngoing(true).setSilent(true)
                .setContentIntent(Notify.openApp(ctx, Tool.SILENT.id))
                .build()
            if (Notify.canPost(ctx)) Notify.nm(ctx).notify(NOTIF_ID, n)
        } else if (d.engaged) {
            // only restore if the user did not change the mode by hand meanwhile
            if (am.ringerMode == d.appliedMode) setRinger(ctx, d.prevMode)
            Stores.silent.update { it.copy(engaged = false, appliedMode = -1) }
            Notify.nm(ctx).cancel(NOTIF_ID)
        }

        val next = list.flatMap { listOf(it.start, it.end) }.filter { it.isAfter(now) }.minOrNull()
            ?: now.plusHours(12) // periodic re-check keeps prayer times fresh
        Alarms.set(ctx, "alma://silent/0", next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000)
    }
}
