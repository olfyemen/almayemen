package com.alma.toolbox.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * Local VPN: only the packages to block are routed into it. Their packets are read and dropped,
 * so they get no network. Everything else bypasses the VPN untouched.
 */
class DataGuardService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var screenOn = true
    private var onMobile = true
    private val net = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: android.net.Network, c: android.net.NetworkCapabilities) {
            val m = c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            if (m != onMobile) { onMobile = m; if (DataGuard.capBlocking(this@DataGuardService)) apply() }
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            if (DataGuard.mode(ctx) != DataMode.BG) return
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(net) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { close(); stopSelf(); return START_NOT_STICKY }
        if (!DataGuard.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        apply()
        return START_STICKY
    }

    /** Re-establishes the tunnel for the current block list. */
    private fun apply() {
        close()
        val blocked = DataGuard.currentBlocked(this, screenOn, onMobile)
        if (blocked.isEmpty()) { stopSelf(); return } // nothing to block: no tunnel, no battery cost
        val b = Builder().setSession("حقيبة الما · عداد النت").addAddress("10.111.222.1", 32).addRoute("0.0.0.0", 0).setMtu(1500)
        blocked.forEach { pkg -> runCatching { b.addAllowedApplication(pkg) } }
        val fd = b.establish() ?: run { stopSelf(); return }
        tun = fd
        reader = Thread {
            // read and drop: blocked apps never get an answer
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32767)
            try { while (!Thread.currentThread().isInterrupted) { if (input.read(buf) < 0) break } } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
    }

    private fun close() {
        reader?.interrupt(); reader = null
        tun?.runCatching { close() }; tun = null
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(net) }
        close()
        super.onDestroy()
    }
}
