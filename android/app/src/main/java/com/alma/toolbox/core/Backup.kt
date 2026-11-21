package com.alma.toolbox.core

import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.alma.toolbox.R
import com.alma.toolbox.transfer.Downloads
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Serializable
data class BackupFile(
    val app: String = "alma-toolbox",
    val version: Int = 1,
    val created: String = LocalDateTime.now().toString(),
    val stores: Map<String, JsonElement> = emptyMap(),
    val prefs: Map<String, Map<String, String>> = emptyMap(),
)

/** One file with everything the app keeps: accounts, alerts, rooms, settings… */
object Backup {
    private val prefFiles = listOf("home", "hijri", "adhkar", "zakat", "roznama")

    private fun stores(): Map<String, JsonStore<*>> = mapOf(
        "periodic" to Stores.periodic, "silent" to Stores.silent, "reminders" to Stores.reminders,
        "pomodoro" to Stores.pomodoro, "ledger" to Stores.ledger, "transfer" to Stores.transfer,
        "adhan" to Stores.adhan, "notes" to Stores.notes, "occasions" to Stores.occasions,
    )

    fun export(ctx: Context): String {
        val prefs = prefFiles.associateWith { name ->
            ctx.getSharedPreferences(name, 0).all.mapValues { (_, v) -> (if (v is Set<*>) v.joinToString("\u0001") else v.toString()) }
        }
        val b = BackupFile(stores = stores().mapValues { (_, s) -> AppJson.parseToJsonElement(s.exportText()) }, prefs = prefs)
        return AppJson.encodeToString(BackupFile.serializer(), b)
    }

    /** Returns the number of sections restored. Throws on a file that isn't ours. */
    fun import(ctx: Context, text: String): Int {
        val b = AppJson.decodeFromString(BackupFile.serializer(), text)
        require(b.app == "alma-toolbox") { "هذا الملف ليس نسخة من حقيبة الما" }
        var n = 0
        stores().forEach { (k, s) -> b.stores[k]?.let { runCatching { s.importText(it.toString()) }.onSuccess { n++ } } }
        b.prefs.forEach { (name, m) ->
            val e = ctx.getSharedPreferences(name, 0).edit().clear()
            m.forEach { (k, v) -> v.toIntOrNull()?.let { e.putInt(k, it) } ?: v.toBooleanStrictOrNull()?.let { e.putBoolean(k, it) } ?: e.putString(k, v) }
            e.apply()
        }
        if (b.prefs.containsKey("roznama")) ctx.getSharedPreferences("roznama", 0).edit().putBoolean("restore", true).apply()
        Alarms.rescheduleAll(ctx)
        return n
    }

    fun writeTo(ctx: Context, uri: Uri) = ctx.contentResolver.openOutputStream(uri)!!.use { it.write(export(ctx).toByteArray()) }

    // ---------- optional weekly auto backup ----------
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("backup", 0)
    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("auto", false)
    fun lastAuto(ctx: Context) = prefs(ctx).getString("last", "") ?: ""
    fun setAuto(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("auto", on).apply(); schedule(ctx); if (on) runAuto(ctx) }

    fun schedule(ctx: Context) {
        if (!autoEnabled(ctx)) { Alarms.cancel(ctx, "alma://backup/0"); return }
        val at = LocalDate.now().plusDays(7).atTime(3, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        Alarms.set(ctx, "alma://backup/0", at)
    }

    fun runAuto(ctx: Context) {
        runCatching {
            val t = Downloads.open(ctx, "نسخ احتياطية", "حقيبة-الما-${LocalDate.now()}.json")
            try { t.out.use { it.write(export(ctx).toByteArray()) } } finally { t.done() }
            prefs(ctx).edit().putString("last", LocalDate.now().toString()).apply()
        }.onFailure {
            val n = NotificationCompat.Builder(ctx, Notify.CH_INFO).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("تعذر النسخ الاحتياطي التلقائي").setContentText(it.message ?: "").build()
            if (Notify.canPost(ctx)) Notify.nm(ctx).notify(8801, n)
        }
        schedule(ctx)
    }
}
