package com.alma.toolbox.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import com.alma.toolbox.MainActivity

object Notify {
    const val CH_INFO = "info"
    const val CH_ONGOING = "ongoing"
    const val CH_ALARM = "alarm_screen"

    fun createBaseChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "إشعارات عامة", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "المؤقتات الجارية", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "المنبه (ملء الشاشة)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
    }

    fun soundTitle(ctx: Context, sound: String?): String {
        if (sound == "silent") return "بدون صوت"
        if (sound.isNullOrEmpty()) return "النغمة الافتراضية"
        return try { RingtoneManager.getRingtone(ctx, Uri.parse(sound))?.getTitle(ctx) ?: "نغمة" } catch (e: Exception) { "نغمة" }
    }

    /** Channels own their sound on Android 8+, so we keep one channel per (sound, vibrate) pair. */
    fun channelFor(ctx: Context, sound: String?, vibrate: Boolean): String {
        val key = (sound ?: "default") + vibrate
        val id = "snd_" + key.hashCode().toUInt().toString(16)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, "تنبيه: " + soundTitle(ctx, sound) + if (vibrate) " + اهتزاز" else "", NotificationManager.IMPORTANCE_HIGH)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            when {
                sound == "silent" -> ch.setSound(null, null)
                sound.isNullOrEmpty() -> ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
                else -> ch.setSound(Uri.parse(sound), attrs)
            }
            ch.enableVibration(vibrate)
            if (vibrate) ch.vibrationPattern = longArrayOf(0, 400, 200, 400)
            nm.createNotificationChannel(ch)
        }
        return id
    }

    fun openApp(ctx: Context, tool: String?, req: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (tool != null) i.putExtra(MainActivity.EXTRA_TOOL, tool)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun action(ctx: Context, path: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setData(Uri.parse("alma://action/$path"))
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun nm(ctx: Context): NotificationManager = ctx.getSystemService(NotificationManager::class.java)

    fun canPost(ctx: Context) = nm(ctx).areNotificationsEnabled()
}
