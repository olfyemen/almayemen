package com.alma.toolbox.core

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

/** Built-in alert tones (res/raw, generated for this app). Stored as android.resource URIs. */
object Tones {
    data class Tone(val key: String, val title: String)

    val all = listOf(
        Tone("bell", "🔔 جرس"), Tone("chime", "🎐 رنين"), Tone("drop", "💧 قطرات"),
        Tone("pulse", "📟 نبضات"), Tone("whistle", "🎵 صفير"), Tone("soft", "🌙 هادئة"),
    )

    fun uri(ctx: Context, key: String) = "android.resource://${ctx.packageName}/raw/tone_$key"

    fun keyOf(uri: String?): String? = all.firstOrNull { uri?.endsWith("/raw/tone_${it.key}") == true }?.key

    fun titleOf(uri: String?): String? = keyOf(uri)?.let { k -> all.first { it.key == k }.title }

    private var player: MediaPlayer? = null

    fun play(ctx: Context, uri: String) {
        stop()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
                setDataSource(ctx, Uri.parse(uri)); prepare(); start()
                setOnCompletionListener { it.release(); if (player === it) player = null }
            }
        }.getOrNull()
    }

    fun stop() { player?.runCatching { stop(); release() }; player = null }
}
