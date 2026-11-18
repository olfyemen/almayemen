package com.alma.toolbox.prayer

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import com.alma.toolbox.silent.PrayerCalc
import com.alma.toolbox.ui.fmtTime
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Prayer indexes follow PrayerCalc: 0 fajr, 1 sunrise, 2 dhuhr, 3 asr, 4 maghrib, 5 isha. */
val AdhanPrayers = listOf(0, 2, 3, 4, 5)

@Serializable
data class AdhanData(
    val enabled: Set<Int> = emptySet(),
    /** madinah | general | sanaa | custom | beep */
    val sound: String = "madinah",
    val soundUri: String = "",
    val soundName: String = "",
    val fajrSound: String = "madinah",
    val fajrUri: String = "",
    val fajrName: String = "",
    val preMin: Int = 0,
    val respectSilent: Boolean = true,
    val volume: Int = 80,
)

data class AdhanChoice(val key: String, val title: String, val needsFile: Boolean)

val AdhanChoices = listOf(
    AdhanChoice("madinah", "🕌 المسجد النبوي – المدينة المنورة", false),
    AdhanChoice("general", "📢 أذان عام", false),
    AdhanChoice("sanaa", "🕌 الجامع الكبير – صنعاء (اختر ملفه)", true),
    AdhanChoice("custom", "📁 ملف آخر من الهاتف", true),
    AdhanChoice("beep", "🔔 تنبيه قصير فقط", false),
)

object AdhanScheduler {
    private const val URI = "alma://adhan/0"
    private const val PRE = "alma://adhanpre/0"

    /** Next enabled prayer after [now]: (prayer index, time). */
    fun next(d: AdhanData, now: LocalDateTime = LocalDateTime.now()): Pair<Int, LocalDateTime>? {
        if (d.enabled.isEmpty()) return null
        val ps = Stores.silent.value.prayer
        for (off in 0L..2L) {
            val date = now.toLocalDate().plusDays(off)
            val t = PrayerCalc.times(date, ps)
            for (p in AdhanPrayers.filter { it in d.enabled }.sortedBy { t[it] }) {
                val at = date.atStartOfDay().plusSeconds((t[p] * 3600).toLong()).withSecond(0)
                if (at.isAfter(now)) return p to at
            }
        }
        return null
    }

    fun schedule(ctx: Context, after: LocalDateTime = LocalDateTime.now()) {
        val d = Stores.adhan.value
        val n = next(d, after)
        if (n == null) { Alarms.cancel(ctx, URI); Alarms.cancel(ctx, PRE); return }
        val ms = n.second.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        Alarms.set(ctx, URI, ms, alarmClock = true)
        val pre = ms - d.preMin * 60_000L
        if (d.preMin > 0 && pre > System.currentTimeMillis()) Alarms.set(ctx, PRE, pre) else Alarms.cancel(ctx, PRE)
    }

    fun fire(ctx: Context, pre: Boolean) {
        val d = Stores.adhan.value
        val n = next(d, LocalDateTime.now().minusMinutes(if (pre) 0 else 2)) // the prayer that is due now
        if (pre) {
            n?.let { (p, at) ->
                val nn = NotificationCompat.Builder(ctx, Notify.CH_INFO).setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("🕌 اقترب أذان ${PrayerCalc.names[p]}").setContentText("بعد ${d.preMin} دقيقة · ${fmtTime(at.hour * 60 + at.minute)}")
                    .setAutoCancel(true).setContentIntent(Notify.openApp(ctx, Tool.PRAYER.id, 21)).build()
                if (Notify.canPost(ctx)) Notify.nm(ctx).notify(7301, nn)
            }
        } else if (n != null) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AdhanService::class.java).putExtra("p", n.first)) }
        }
        // look a minute ahead so the prayer that is firing now isn't picked again
        if (!pre) schedule(ctx, LocalDateTime.now().plusMinutes(1))
    }
}

/** Plays the adhan once; stopped from the notification or when the recording ends. */
class AdhanService : Service() {
    private var player: MediaPlayer? = null
    override fun onBind(intent: Intent?): IBinder? = null

    private fun source(ctx: Context, key: String, uri: String): Pair<String?, String?> = when (key) {
        "madinah" -> "adhan/madinah.mp3" to null
        "general" -> "adhan/general.mp3" to null
        "sanaa", "custom" -> if (uri.isNotEmpty()) null to uri else "adhan/madinah.mp3" to null
        else -> null to null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Stores.init(this)
        if (intent?.action == "stop") { stopAll(); return START_NOT_STICKY }
        val p = intent?.getIntExtra("p", 2) ?: 2
        val d = Stores.adhan.value
        val stop = PendingIntent.getService(this, 31, Intent(this, AdhanService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notify.CH_ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🕌 حان الآن موعد أذان ${PrayerCalc.names[p]}")
            .setContentText("اضغط لقراءة أذكار الأذان")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(Notify.openApp(this, "adhkar/hisn/15", 22))
            .addAction(0, "إيقاف الأذان", stop)
            .build()
        ServiceCompat.startForeground(this, 7300, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)

        val am = getSystemService(AudioManager::class.java)
        val silentByUser = am.ringerMode != AudioManager.RINGER_MODE_NORMAL && !Stores.silent.value.engaged
        val key = if (p == 0) d.fajrSound else d.sound
        val uri = if (p == 0) d.fajrUri else d.soundUri
        val (asset, file) = source(this, key, uri)
        if ((d.respectSilent && silentByUser) || (asset == null && file == null)) {
            if (key == "beep" && !(d.respectSilent && silentByUser)) runCatching { android.media.RingtoneManager.getRingtone(this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))?.play() }
            finishKeepNotice(); return START_NOT_STICKY
        }
        player?.release()
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                if (asset != null) assets.openFd(asset).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                else setDataSource(this@AdhanService, Uri.parse(file))
                val v = d.volume / 100f; setVolume(v, v)
                setOnCompletionListener { finishKeepNotice() }
                prepare(); start()
            }
        } catch (e: Exception) { finishKeepNotice(); null }
        return START_NOT_STICKY
    }

    private fun finishKeepNotice() {
        player?.runCatching { release() }; player = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun stopAll() {
        player?.runCatching { stop(); release() }; player = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { player?.runCatching { release() }; super.onDestroy() }

    companion object {
        /** Preview inside the app. */
        private var preview: MediaPlayer? = null
        fun previewStop() { preview?.runCatching { stop(); release() }; preview = null }
        fun preview(ctx: Context, key: String, uri: String) {
            previewStop()
            val src = when (key) { "madinah" -> "adhan/madinah.mp3"; "general" -> "adhan/general.mp3"; else -> null }
            preview = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    if (src != null) ctx.assets.openFd(src).use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                    else if (uri.isNotEmpty()) setDataSource(ctx, Uri.parse(uri)) else error("no file")
                    prepare(); start()
                }
            }.getOrNull()
        }
    }
}

fun todayTimes(): DoubleArray = PrayerCalc.times(LocalDate.now(), Stores.silent.value.prayer)
