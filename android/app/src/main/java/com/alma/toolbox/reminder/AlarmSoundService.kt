package com.alma.toolbox.reminder

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alma.toolbox.R
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores

/** Plays an alarm-style reminder in a loop until stopped or snoozed. */
class AlarmSoundService : Service() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentId = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Stores.init(this)
        if (intent?.action == ACTION_STOP) { stopAll(); return START_NOT_STICKY }
        val id = intent?.getLongExtra("id", 0L) ?: 0L
        val r = Stores.reminders.value.items.firstOrNull { it.id == id }
        if (r == null) { stopAll(); return START_NOT_STICKY }
        currentId = id

        val full = PendingIntent.getActivity(this, 1, Intent(this, AlarmActivity::class.java).putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, AlarmSoundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notify.CH_ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("⏰ ${r.title.ifBlank { "منبه" }}")
            .setContentText(r.note)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(0, "إيقاف", stop)
            .addAction(0, "غفوة ${r.snoozeMin} د", Notify.action(this, "snooze/$id?m=${r.snoozeMin}"))
            .build()
        ServiceCompat.startForeground(this, NOTIF, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)

        player?.release()
        val uri = when (r.sound) {
            null, "" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            "silent" -> null
            else -> Uri.parse(r.sound)
        }
        if (uri != null) player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AlarmSoundService, uri); isLooping = true; prepare(); start()
            }
        } catch (e: Exception) { null }
        if (r.vibrate) vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        try { startActivity(Intent(this, AlarmActivity::class.java).putExtra("id", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopAll() }, 3 * 60_000L) // auto stop after 3 minutes
        return START_NOT_STICKY
    }

    private fun vibrator(): Vibrator = if (Build.VERSION.SDK_INT >= 31)
        getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop(); release() }; player = null
        vibrator().cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    override fun onDestroy() { player?.runCatching { release() }; vibrator().cancel(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "com.alma.toolbox.STOP_ALARM"
        const val ACTION_STOPPED = "com.alma.toolbox.ALARM_STOPPED"
        const val NOTIF = 4242
    }
}
