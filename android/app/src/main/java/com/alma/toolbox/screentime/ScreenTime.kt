package com.alma.toolbox.screentime

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.alma.toolbox.data.DataGuard
import java.time.LocalDate
import java.time.ZoneId

/** Screen-time counting, daily goal and the lock after the goal. */
object ScreenTime {
    // UsageEvents.Event constants (some are API 28+, kept as numbers so the pure logic is testable)
    const val RESUMED = 1; const val PAUSED = 2; const val STOPPED = 23
    const val SCREEN_ON = 15; const val SCREEN_OFF = 16; const val KG_SHOWN = 17; const val KG_HIDDEN = 18

    data class Ev(val t: Long, val type: Int, val pkg: String = "")
    data class Use(val total: Long, val apps: Map<String, Long>, val unlocks: Int)

    /**
     * Pure: screen-on time inside [from, to] from a stream of events (events before [from] only set the state).
     * [noMusic]: don't count time on the lock screen nor time when an audio app is in front.
     */
    fun compute(events: List<Ev>, from: Long, to: Long, noMusic: Boolean, music: Set<String>): Use {
        val ev = events.sortedBy { it.t }
        // initial state guesses: first screen event OFF => it was on; first keyguard HIDDEN => it was locked
        var screenOn = ev.firstOrNull { it.type == SCREEN_ON || it.type == SCREEN_OFF }?.type == SCREEN_OFF
        var locked = ev.firstOrNull { it.type == KG_SHOWN || it.type == KG_HIDDEN }?.type == KG_HIDDEN
        var fg: String? = null
        var prev = from
        var total = 0L
        val apps = HashMap<String, Long>()
        var unlocks = 0; var screenOns = 0
        val hasKeyguard = ev.any { it.type == KG_HIDDEN }
        fun flush(until: Long) {
            val a = maxOf(prev, from); val b = minOf(until, to)
            if (b > a && screenOn && !(noMusic && (locked || fg in music))) {
                total += b - a
                fg?.let { apps[it] = (apps[it] ?: 0L) + (b - a) }
            }
            prev = maxOf(prev, until)
        }
        for (e in ev) {
            if (e.t > to) break
            flush(e.t)
            val inWin = e.t >= from
            when (e.type) {
                SCREEN_ON -> { screenOn = true; if (inWin) screenOns++ }
                SCREEN_OFF -> { screenOn = false; fg = null }
                KG_SHOWN -> locked = true
                KG_HIDDEN -> { locked = false; if (inWin) unlocks++ }
                RESUMED -> fg = e.pkg
                PAUSED, STOPPED -> if (fg == e.pkg) fg = null
            }
        }
        flush(to)
        return Use(total, apps, if (hasKeyguard) unlocks else screenOns)
    }

    /** Pure: when to show the small countdown box. Every 15 minutes of use; every 10 in the last hour. */
    fun bubbleDue(used: Long, lastShownAt: Long, goal: Long): Boolean {
        if (goal <= 0) return false
        val left = goal - used
        val step = if (left <= 60 * 60_000L) 10 * 60_000L else 15 * 60_000L
        return used - lastShownAt >= step
    }

    // ------------- settings -------------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("screentime", 0)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("on", v).apply(); sync(ctx) }
    /** daily goal in minutes */
    fun goalMin(ctx: Context) = prefs(ctx).getInt("goal", 180)
    fun setGoalMin(ctx: Context, v: Int) = prefs(ctx).edit().putInt("goal", v).apply()
    fun noMusic(ctx: Context) = prefs(ctx).getBoolean("nomusic", true)
    fun setNoMusic(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("nomusic", v).apply()
    fun bubble(ctx: Context) = prefs(ctx).getBoolean("bubble", true)
    fun setBubble(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("bubble", v).apply()
    fun lock(ctx: Context) = prefs(ctx).getBoolean("lock", true)
    fun setLock(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("lock", v).apply()

    /** Apps never locked. WhatsApp is in by default. */
    fun exempt(ctx: Context): Set<String> {
        val p = prefs(ctx)
        if (!p.getBoolean("ex_init", false)) {
            p.edit().putStringSet("exempt", DataGuard.WHATSAPP).putBoolean("ex_init", true).apply()
            return DataGuard.WHATSAPP
        }
        return p.getStringSet("exempt", emptySet())?.toSet() ?: emptySet()
    }
    fun setExempt(ctx: Context, pkg: String, v: Boolean) = prefs(ctx).edit().putStringSet("exempt",
        exempt(ctx).toMutableSet().also { if (v) it.add(pkg) else it.remove(pkg) }).apply()

    /** Extra minutes granted today (after the goal ran out). */
    fun extraMin(ctx: Context): Int = if (prefs(ctx).getString("extra_day", "") == LocalDate.now().toString()) prefs(ctx).getInt("extra", 0) else 0
    fun addExtra(ctx: Context, min: Int) = prefs(ctx).edit().putString("extra_day", LocalDate.now().toString()).putInt("extra", extraMin(ctx) + min).apply()

    // ------------- pomodoro focus: block distracting apps -------------
    fun focusBlock(ctx: Context) = prefs(ctx).getBoolean("focus", false)
    fun setFocusBlock(ctx: Context, v: Boolean) { prefs(ctx).edit().putBoolean("focus", v).apply(); sync(ctx) }
    fun distracting(ctx: Context): Set<String> = prefs(ctx).getStringSet("distract", emptySet())?.toSet() ?: emptySet()
    fun setDistracting(ctx: Context, pkg: String, v: Boolean) = prefs(ctx).edit().putStringSet("distract",
        distracting(ctx).toMutableSet().also { if (v) it.add(pkg) else it.remove(pkg) }).apply()

    fun focusNow(ctx: Context): Boolean {
        com.alma.toolbox.core.Stores.init(ctx)
        val d = com.alma.toolbox.core.Stores.pomodoro.value
        return focusBlock(ctx) && d.running && d.phase == "work"
    }

    // ------------- permissions -------------
    fun hasUsage(ctx: Context) = DataGuard.hasUsageAccess(ctx)
    fun hasOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    // ------------- measuring -------------
    fun dayStart(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val musicCache = HashMap<String, Boolean>()
    fun musicApps(ctx: Context, pkgs: Collection<String>): Set<String> = pkgs.filter { p ->
        musicCache.getOrPut(p) {
            runCatching {
                val ai = ctx.packageManager.getApplicationInfo(p, 0)
                Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_AUDIO
            }.getOrDefault(false)
        }
    }.toSet()

    fun events(ctx: Context, from: Long, to: Long): List<Ev> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val out = ArrayList<Ev>()
        runCatching {
            val q = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            while (q.hasNextEvent()) {
                q.getNextEvent(e)
                when (e.eventType) { RESUMED, PAUSED, STOPPED, SCREEN_ON, SCREEN_OFF, KG_SHOWN, KG_HIDDEN -> out.add(Ev(e.timeStamp, e.eventType, e.packageName ?: "")) }
            }
        }
        return out
    }

    /** Usage of one day (today counts up to now). */
    fun day(ctx: Context, d: LocalDate): Use {
        if (!hasUsage(ctx)) return Use(0, emptyMap(), 0)
        val from = dayStart(d)
        val to = minOf(dayStart(d.plusDays(1)), System.currentTimeMillis())
        val ev = events(ctx, from - 6 * 3600_000L, to)
        val nm = noMusic(ctx)
        val u = compute(ev, from, to, nm, if (nm) musicApps(ctx, ev.map { it.pkg }.toSet()) else emptySet())
        // remember the totals: the system keeps detailed events only for some days
        prefs(ctx).edit().putLong("h_$d", u.total).apply()
        return u
    }

    fun todayUsed(ctx: Context): Long = day(ctx, LocalDate.now()).total

    /** Saved or recomputed total of a past day. */
    fun total(ctx: Context, d: LocalDate): Long {
        val p = prefs(ctx)
        if (d != LocalDate.now() && p.contains("h_$d")) return p.getLong("h_$d", 0)
        return if (d.isBefore(LocalDate.now().minusDays(9))) 0L else day(ctx, d).total
    }

    fun goalMs(ctx: Context) = (goalMin(ctx) + extraMin(ctx)) * 60_000L

    /** Last resumed app, or null. */
    fun foreground(ctx: Context): String? {
        val now = System.currentTimeMillis()
        var fg: String? = null
        events(ctx, now - 30 * 60_000L, now).sortedBy { it.t }.forEach { e ->
            when (e.type) { RESUMED -> fg = e.pkg; PAUSED, STOPPED -> if (fg == e.pkg) fg = null; SCREEN_OFF -> fg = null }
        }
        return fg
    }

    /** Never locked: this app, phone, launcher, settings, system UI and the user's list. */
    fun alwaysAllowed(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val dialer = runCatching { ctx.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        return setOfNotNull(ctx.packageName, home, dialer, "com.android.settings", "com.android.systemui",
            "com.google.android.dialer", "com.android.dialer", "com.android.phone", "com.android.emergency") + exempt(ctx)
    }

    /** Starts or stops the watcher service as the settings require. */
    fun sync(ctx: Context) {
        val need = (enabled(ctx) || focusNow(ctx)) && hasUsage(ctx)
        val i = Intent(ctx, ScreenTimeService::class.java)
        if (need) runCatching { ContextCompat.startForegroundService(ctx, i) }
        else runCatching { ctx.stopService(i) }
    }

    fun fmt(ms: Long): String {
        val m = (ms / 60_000L).toInt()
        return if (m >= 60) "${m / 60} س ${m % 60} د" else "$m د"
    }

    fun clock(ms: Long): String {
        val m = (maxOf(ms, 0) / 60_000L).toInt()
        return String.format(java.util.Locale.US, "%d:%02d", m / 60, m % 60)
    }
}
