package com.alma.toolbox.core

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Westminster («Big Ben») chimes, synthesised: quarter melodies by minute and,
 * on the hour, one strike of the hour bell per hour (1–12).
 */
object Chime {
    private const val RATE = 22050
    private const val GS4 = 415.30; private const val FS4 = 369.99; private const val E4 = 329.63; private const val B3 = 246.94
    private const val HOUR_BELL = 164.81 // E3, Big Ben is tuned to E

    private val phrases = listOf(
        listOf(GS4, FS4, E4, B3), listOf(E4, GS4, FS4, B3), listOf(E4, FS4, GS4, E4),
        listOf(GS4, E4, FS4, B3), listOf(B3, FS4, GS4, E4),
    )

    /** Which phrases play at this minute (Westminster quarters). */
    private fun phrasesFor(minute: Int): List<Int> = when (minute) {
        0 -> listOf(1, 2, 3, 4)
        in 1..22 -> listOf(0)
        in 23..37 -> listOf(1, 2)
        in 38..52 -> listOf(3, 4, 0)
        else -> listOf(1, 2, 3, 4)
    }

    private fun bell(buf: FloatArray, at: Int, f: Double, amp: Double, ring: Double) {
        // bell partials: hum, prime, minor third, fifth, octave, upper partials
        val ratios = doubleArrayOf(0.5, 1.0, 1.19, 1.5, 2.0, 2.52, 3.0)
        val amps = doubleArrayOf(0.35, 1.0, 0.45, 0.3, 0.28, 0.14, 0.08)
        val len = (ring * RATE).toInt()
        for (i in 0 until len) {
            val idx = at + i; if (idx >= buf.size) break
            val t = i.toDouble() / RATE
            val attack = if (t < 0.004) t / 0.004 else 1.0
            var v = 0.0
            for (k in ratios.indices) v += amps[k] * sin(2 * PI * f * ratios[k] * t) * exp(-t * (0.9 + k * 0.7) / ring * 2.2)
            buf[idx] += (v * amp * attack).toFloat()
        }
    }

    /** strikes = 0 for quarter chimes only. */
    fun render(minute: Int, strikes: Int): ShortArray {
        val note = 0.62; val gap = 0.75; val strikeGap = 2.3
        val ph = phrasesFor(minute)
        val melodyLen = ph.size * (4 * note + gap)
        val total = melodyLen + (if (strikes > 0) 1.0 + strikes * strikeGap + 3.5 else 3.0)
        val buf = FloatArray((total * RATE).toInt())
        var t = 0.2
        ph.forEach { p ->
            phrases[p].forEachIndexed { i, f -> bell(buf, (t * RATE).toInt(), f, 0.5, if (i == 3) 3.0 else 2.2); t += note }
            t += gap
        }
        if (strikes > 0) {
            t += 1.0
            repeat(strikes) { bell(buf, (t * RATE).toInt(), HOUR_BELL, 0.9, 4.0); t += strikeGap }
        }
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        return ShortArray(buf.size) { (buf[it] / peak * 0.9f * Short.MAX_VALUE).toInt().toShort() }
    }

    fun strikesFor(hour24: Int, minute: Int) = if (minute == 0) (if (hour24 % 12 == 0) 12 else hour24 % 12) else 0

    /** Blocking playback. */
    fun play(pcm: ShortArray, usage: Int = AudioAttributes.USAGE_NOTIFICATION_EVENT) {
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        try { tr.write(pcm, 0, pcm.size); tr.play(); Thread.sleep(pcm.size * 1000L / RATE + 200) } finally { tr.release() }
    }

    fun start(ctx: Context, title: String, hour: Int, minute: Int) {
        val i = Intent(ctx, ChimeService::class.java).putExtra("title", title).putExtra("h", hour).putExtra("m", minute)
        runCatching { ContextCompat.startForegroundService(ctx, i) }
    }
}

/** Short-lived service so the whole chime (up to ~45 s) can finish. */
class ChimeService : Service() {
    @Volatile private var thread: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val h = intent?.getIntExtra("h", 0) ?: 0
        val m = intent?.getIntExtra("m", 0) ?: 0
        val title = intent?.getStringExtra("title") ?: "ساعة بيج بن"
        val n = NotificationCompat.Builder(this, Notify.CH_INFO)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🕰️ $title")
            .setContentText("الساعة " + com.alma.toolbox.ui.fmtTime(h * 60 + m))
            .setContentIntent(Notify.openApp(this, Tool.PERIODIC.id))
            .addAction(0, "إيقاف", android.app.PendingIntent.getService(this, 3, Intent(this, ChimeService::class.java).setAction("stop"), android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        ServiceCompat.startForeground(this, 4343, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        if (intent?.action == "stop") { thread?.interrupt(); finish(); return START_NOT_STICKY }
        // respect silent / vibrate mode (and the scheduled-silent tool)
        val am = getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) { finish(); return START_NOT_STICKY }
        thread?.interrupt()
        thread = Thread {
            try { Chime.play(Chime.render(m, Chime.strikesFor(h, m))) } catch (_: InterruptedException) {} catch (_: Exception) {}
            finish()
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }
}
