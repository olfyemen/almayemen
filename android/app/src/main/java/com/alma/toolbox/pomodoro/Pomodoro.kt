package com.alma.toolbox.pomodoro

import android.content.Context
import androidx.core.app.NotificationCompat
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class PomoData(
    val work: Int = 25, val short: Int = 5, val long: Int = 15, val cycles: Int = 4,
    val autoBreak: Boolean = true, val autoWork: Boolean = false,
    val sound: String? = null, val vibrate: Boolean = true,
    val phase: String = "work", val running: Boolean = false,
    val endAt: Long = 0, val remaining: Long = 25 * 60_000L, val cycle: Int = 0,
    val today: String = "", val todayCount: Int = 0, val todayMinutes: Int = 0, val totalCount: Int = 0,
    val task: String = "",
)

fun PomoData.duration(p: String = phase) = when (p) { "short" -> short; "long" -> long; else -> work } * 60_000L
fun phaseName(p: String) = when (p) { "short" -> "استراحة قصيرة"; "long" -> "استراحة طويلة"; else -> "تركيز" }
fun PomoData.left(now: Long = System.currentTimeMillis()) = if (running) (endAt - now).coerceAtLeast(0) else remaining

object PomoEngine {
    private const val URI = "alma://pomodoro/0"
    private const val ONGOING = 9901
    private const val DONE = 9902

    private fun ongoing(ctx: Context, d: PomoData) {
        val b = NotificationCompat.Builder(ctx, Notify.CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🍅 ${phaseName(d.phase)}" + if (d.task.isNotBlank()) " · ${d.task}" else "")
            .setOngoing(d.running).setSilent(true).setOnlyAlertOnce(true)
            .setContentIntent(Notify.openApp(ctx, Tool.POMODORO.id))
            .addAction(0, if (d.running) "إيقاف مؤقت" else "استئناف", Notify.action(ctx, "pomopause/0"))
            .addAction(0, "إنهاء", Notify.action(ctx, "pomostop/0"))
        if (d.running) b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(d.endAt).setShowWhen(true)
        else b.setContentText("متوقف مؤقتاً · متبقٍ ${d.remaining / 60000} د")
        if (Notify.canPost(ctx)) Notify.nm(ctx).notify(ONGOING, b.build())
    }

    fun start(ctx: Context) {
        val d = Stores.pomodoro.update { val rem = if (it.remaining <= 0) it.duration() else it.remaining
            it.copy(running = true, endAt = System.currentTimeMillis() + rem, remaining = rem) }
        Alarms.set(ctx, URI, d.endAt, alarmClock = false)
        ongoing(ctx, d)
    }

    fun pause(ctx: Context) {
        val d = Stores.pomodoro.update { if (!it.running) it else it.copy(running = false, remaining = it.left()) }
        Alarms.cancel(ctx, URI); ongoing(ctx, d)
    }

    fun toggle(ctx: Context) = if (Stores.pomodoro.value.running) pause(ctx) else start(ctx)

    fun reset(ctx: Context) {
        Stores.pomodoro.update { it.copy(running = false, phase = "work", remaining = it.duration("work"), cycle = 0) }
        Alarms.cancel(ctx, URI); Notify.nm(ctx).cancel(ONGOING)
    }

    private fun nextPhase(d: PomoData, countIt: Boolean): PomoData {
        val todayStr = LocalDate.now().toString()
        var x = if (d.today != todayStr) d.copy(today = todayStr, todayCount = 0, todayMinutes = 0) else d
        return if (x.phase == "work") {
            if (countIt) x = x.copy(todayCount = x.todayCount + 1, todayMinutes = x.todayMinutes + x.work, totalCount = x.totalCount + 1)
            val c = x.cycle + 1
            val p = if (c >= x.cycles) "long" else "short"
            x.copy(phase = p, cycle = if (p == "long") 0 else c, remaining = x.duration(p), running = false)
        } else x.copy(phase = "work", remaining = x.duration("work"), running = false)
    }

    fun skip(ctx: Context) {
        val was = Stores.pomodoro.value.running
        Stores.pomodoro.update { nextPhase(it, countIt = false) }
        Alarms.cancel(ctx, URI)
        if (was) start(ctx) else Notify.nm(ctx).cancel(ONGOING)
    }

    fun phaseEnded(ctx: Context) {
        val before = Stores.pomodoro.value
        if (!before.running) return
        val d = Stores.pomodoro.update { nextPhase(it, countIt = true) }
        val msg = if (before.phase == "work") "أحسنت! انتهت جلسة التركيز ✓ — وقت ${phaseName(d.phase)}" else "انتهت الاستراحة — عُد للتركيز 💪"
        val n = NotificationCompat.Builder(ctx, Notify.channelFor(ctx, d.sound, d.vibrate))
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("🍅 بومودورو").setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(Notify.openApp(ctx, Tool.POMODORO.id)).build()
        if (Notify.canPost(ctx)) Notify.nm(ctx).notify(DONE, n)
        val auto = if (d.phase == "work") d.autoWork else d.autoBreak
        if (auto) start(ctx) else Notify.nm(ctx).cancel(ONGOING)
    }

    fun restore(ctx: Context) {
        val d = Stores.pomodoro.value
        if (!d.running) return
        if (d.endAt <= System.currentTimeMillis()) phaseEnded(ctx) else { Alarms.set(ctx, URI, d.endAt); ongoing(ctx, d) }
    }
}
