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
    private val prefFiles = listOf("home", "hijri", "adhkar", "zakat", "roznama", "ibtihalat", "almaher_calc", "screentime", "dataguard")

    private fun stores(): Map<String, JsonStore<*>> = mapOf(
        "periodic" to Stores.periodic, "silent" to Stores.silent, "reminders" to Stores.reminders,
        "pomodoro" to Stores.pomodoro, "ledger" to Stores.ledger, "transfer" to Stores.transfer,
        "adhan" to Stores.adhan, "notes" to Stores.notes, "memories" to Stores.memories, "occasions" to Stores.occasions,
    )

    fun export(ctx: Context): String {
        val prefs = prefFiles.associateWith { name ->
            ctx.getSharedPreferences(name, 0).all.mapValues { (_, v) -> encodePref(v) }
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
            m.forEach { (k, v) -> putPref(e, k, v) }
            e.apply()
        }
        if (b.prefs.containsKey("roznama")) ctx.getSharedPreferences("roznama", 0).edit().putBoolean("restore", true).apply()
        Alarms.rescheduleAll(ctx)
        return n
    }

    /** Typed text for one preference value; old backups (no prefix) are still read by guessing. */
    fun encodePref(v: Any?): String = when (v) {
        is Set<*> -> "\u0002S:" + v.joinToString("\u0001")
        is Long -> "\u0002L:$v"
        is Float -> "\u0002F:$v"
        is String -> if (v.toIntOrNull() != null || v.toBooleanStrictOrNull() != null || v.startsWith("\u0002")) "\u0002s:$v" else v
        else -> v.toString()
    }

    fun putPref(e: android.content.SharedPreferences.Editor, k: String, v: String) {
        when {
            v.startsWith("\u0002S:") -> e.putStringSet(k, v.substring(3).split("\u0001").filter { it.isNotEmpty() }.toSet())
            v.startsWith("\u0002L:") -> e.putLong(k, v.substring(3).toLong())
            v.startsWith("\u0002F:") -> e.putFloat(k, v.substring(3).toFloat())
            v.startsWith("\u0002s:") -> e.putString(k, v.substring(3))
            else -> v.toIntOrNull()?.let { e.putInt(k, it) } ?: v.toBooleanStrictOrNull()?.let { e.putBoolean(k, it) } ?: e.putString(k, v)
        }
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

    // ---------- Google Drive (or any cloud app): one linked file, overwritten every week ----------
    fun driveUri(ctx: Context): Uri? = prefs(ctx).getString("drive", null)?.let { Uri.parse(it) }
    fun lastDrive(ctx: Context) = prefs(ctx).getString("drive_last", "") ?: ""

    /** Link the file the user created in Drive's picker and write the first copy now. */
    fun linkDrive(ctx: Context, uri: Uri): Boolean {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs(ctx).edit().putString("drive", uri.toString()).apply()
        val ok = writeDrive(ctx)
        if (ok && !autoEnabled(ctx)) setAuto(ctx, true)
        return ok
    }

    fun unlinkDrive(ctx: Context) {
        driveUri(ctx)?.let { u -> runCatching { ctx.contentResolver.releasePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        prefs(ctx).edit().remove("drive").remove("drive_last").apply()
    }

    fun writeDrive(ctx: Context): Boolean {
        val u = driveUri(ctx) ?: return false
        return runCatching {
            ctx.contentResolver.openOutputStream(u, "wt")!!.use { it.write(export(ctx).toByteArray()) }
            prefs(ctx).edit().putString("drive_last", LocalDate.now().toString()).apply()
        }.isSuccess
    }

    fun runAuto(ctx: Context) {
        runCatching {
            val t = Downloads.open(ctx, "نسخ احتياطية", "حقيبة-الما-${LocalDate.now()}.json")
            try { t.out.use { it.write(export(ctx).toByteArray()) } } finally { t.done() }
            prefs(ctx).edit().putString("last", LocalDate.now().toString()).apply()
            if (driveUri(ctx) != null && !writeDrive(ctx)) error("تعذر الكتابة في ملف Google Drive المربوط، افتح النسخ الاحتياطي وأعد الربط")
        }.onFailure {
            val n = NotificationCompat.Builder(ctx, Notify.CH_INFO).setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("تعذر النسخ الاحتياطي التلقائي").setContentText(it.message ?: "").build()
            if (Notify.canPost(ctx)) Notify.nm(ctx).notify(8801, n)
        }
        schedule(ctx)
    }
}
