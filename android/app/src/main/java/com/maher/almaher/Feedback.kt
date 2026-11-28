package com.maher.almaher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.maher.almaher.data.Settings
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Key click sound and vibration (both off by default). */
class Feedback(ctx: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val track: AudioTrack? by lazy {
        try {
            val sr = 44100
            val n = sr * 30 / 1000
            val data = ShortArray(n) { i ->
                val t = i.toDouble() / sr
                (sin(2 * PI * 1400 * t) * exp(-t * 180) * 0.4 * Short.MAX_VALUE).toInt().toShort()
            }
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(data, 0, n) }
        } catch (e: Exception) {
            null
        }
    }

    fun tap(st: Settings) {
        if (st.sound) {
            track?.let {
                try {
                    it.stop()
                    it.reloadStaticData()
                    it.play()
                } catch (_: Exception) {
                }
            }
        }
        if (st.vibrate) {
            try {
                vibrator?.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {
            }
        }
    }
}
