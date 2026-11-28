package com.alma.toolbox.data

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import java.time.LocalDate
import java.time.ZoneId

/** Data-saving modes. */
object DataMode {
    const val NORMAL = "normal"; const val MEDIUM = "medium"; const val ULTRA = "ultra"; const val BG = "bg"
    val all = listOf(
        NORMAL to "عادي",
        MEDIUM to "توفير متوسط",
        ULTRA to "توفير فائق",
        BG to "إيقاف الخلفية",
    )
    fun describe(m: String) = when (m) {
        MEDIUM -> "يوقف التطبيقات التي استهلكت أكثر من 100 ميجا اليوم، ما عدا المسموح دائماً."
        ULTRA -> "يوقف كل التطبيقات، ما عدا المسموح دائماً."
        BG -> "عند إطفاء الشاشة يوقف كل التطبيقات ما عدا المسموح دائماً. عند فتحها يعود كل شيء."
        else -> "لا يوقف شيئاً إلا ما تحجبه أنت يدوياً."
    }
}

/** Per-app data control. Blocking uses a local VPN that accepts the chosen apps' packets and drops them. */
object DataGuard {
    const val HEAVY_BYTES = 100L * 1024 * 1024

    data class AppUse(val pkg: String, val name: String, val uid: Int, val today: Long, val month: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dataguard", 0)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("on", v).apply()
    fun mode(ctx: Context) = prefs(ctx).getString("mode", DataMode.NORMAL) ?: DataMode.NORMAL
    fun setMode(ctx: Context, m: String) = prefs(ctx).edit().putString("mode", m).apply()
    fun userBlocked(ctx: Context): Set<String> = prefs(ctx).getStringSet("blocked", emptySet())?.toSet() ?: emptySet()
    /** WhatsApp and WhatsApp Business: allowed by default so they stay connected in every mode. */
    val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun whitelist(ctx: Context): Set<String> {
        val p = prefs(ctx)
        if (!p.getBoolean("wa_init", false)) {
            val w = (p.getStringSet("white", emptySet()) ?: emptySet()) + WHATSAPP
            p.edit().putStringSet("white", w).putBoolean("wa_init", true).apply()
            return w
        }
        return p.getStringSet("white", emptySet())?.toSet() ?: emptySet()
    }

    /** «إيقاف الواتساب»: cuts WhatsApp off completely so no messages arrive, whatever the mode. */
    fun waOff(ctx: Context) = prefs(ctx).getBoolean("wa_off", false)
    fun setWaOff(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("wa_off", v).apply()

    /** WhatsApp packages installed on this phone. */
    fun installedWhatsApp(ctx: Context): Set<String> = WHATSAPP.filter {
        runCatching { ctx.packageManager.getApplicationInfo(it, 0) }.isSuccess
    }.toSet()

    fun setBlocked(ctx: Context, pkg: String, v: Boolean) = prefs(ctx).edit().putStringSet("blocked",
        userBlocked(ctx).toMutableSet().also { if (v) it.add(pkg) else it.remove(pkg) }).apply()

    fun setWhite(ctx: Context, pkg: String, v: Boolean) = prefs(ctx).edit().putStringSet("white",
        whitelist(ctx).toMutableSet().also { if (v) it.add(pkg) else it.remove(pkg) }).apply()

    /**
     * Which packages to block now. Pure logic, so it can be tested.
     * [internet] = apps that can use the network at all; [heavy] = apps over the daily threshold.
     */
    fun blockedSet(mode: String, screenOn: Boolean, userBlocked: Set<String>, whitelist: Set<String>,
                   internet: Set<String>, heavy: Set<String>,
                   waOff: Boolean = false, whatsapp: Set<String> = WHATSAPP): Set<String> {
        val extra = when (mode) {
            DataMode.MEDIUM -> heavy
            DataMode.ULTRA -> internet
            DataMode.BG -> if (screenOn) emptySet() else internet
            else -> emptySet()
        }
        return userBlocked + (extra - whitelist) + (if (waOff) whatsapp else emptySet())
    }

    /** Apps the user installed that ask for internet access (never this app). */
    fun internetApps(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).asSequence()
            .filter { it.packageName != ctx.packageName }
            .filter { it.applicationInfo != null && (it.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
            .map { it.packageName to (it.applicationInfo?.let { ai -> pm.getApplicationLabel(ai).toString() } ?: it.packageName) }
            .sortedBy { it.second }.toList()
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ao = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
                   else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun subscriberId(ctx: Context): String? = runCatching {
        @Suppress("DEPRECATION") ctx.getSystemService(TelephonyManager::class.java).subscriberId
    }.getOrNull()

    /** Bytes used by one app since [from] (mobile + wifi). Needs usage access. */
    fun bytes(ctx: Context, uid: Int, from: Long, to: Long = System.currentTimeMillis()): Long {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        var total = 0L
        for (type in listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
            runCatching {
                nsm.queryDetailsForUid(type, if (type == ConnectivityManager.TYPE_MOBILE) subscriberId(ctx) else null, from, to, uid).use { st ->
                    val b = NetworkStats.Bucket()
                    while (st.hasNextBucket()) { st.getNextBucket(b); total += b.rxBytes + b.txBytes }
                }
            }
        }
        return total
    }

    fun todayStart(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun monthStart(): Long = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Usage of every internet app, busiest today first (the screen can re-sort by month). */
    fun usage(ctx: Context): List<AppUse> {
        val pm = ctx.packageManager
        return internetApps(ctx).map { (pkg, name) ->
            val uid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)
            AppUse(pkg, name, uid,
                today = if (uid >= 0 && hasUsageAccess(ctx)) bytes(ctx, uid, todayStart()) else 0L,
                month = if (uid >= 0 && hasUsageAccess(ctx)) bytes(ctx, uid, monthStart()) else 0L)
        }.sortedByDescending { it.today }
    }

    /** What the VPN should block right now, computed from the saved settings. */
    fun currentBlocked(ctx: Context, screenOn: Boolean): Set<String> {
        val mode = mode(ctx)
        val internet = if (mode == DataMode.ULTRA || mode == DataMode.BG) internetApps(ctx).map { it.first }.toSet() else emptySet()
        val heavy = if (mode == DataMode.MEDIUM && hasUsageAccess(ctx)) usage(ctx).filter { it.today >= HEAVY_BYTES }.map { it.pkg }.toSet() else emptySet()
        val wa = waOff(ctx)
        return blockedSet(mode, screenOn, userBlocked(ctx), whitelist(ctx) + ctx.packageName, internet, heavy,
            wa, if (wa) installedWhatsApp(ctx) else emptySet())
    }

    /** Starts or reloads the VPN service (only when enabled). */
    fun reload(ctx: Context) {
        if (!enabled(ctx)) return
        ctx.startService(Intent(ctx, DataGuardService::class.java))
    }

    fun stop(ctx: Context) {
        setEnabled(ctx, false); setWaOff(ctx, false)
        ctx.startService(Intent(ctx, DataGuardService::class.java).setAction("stop"))
    }

    /** Sort keys for the app list. */
    fun sorted(apps: List<AppUse>, byMonth: Boolean) =
        if (byMonth) apps.sortedByDescending { it.month } else apps.sortedByDescending { it.today }

    fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
