package com.alma.toolbox.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

const val DISCOVERY_PORT = 45454
const val HTTP_PORT = 8765
const val SAVE_ROOT = "حقيبة الما"

// ---------------- wire models ----------------
@Serializable data class RoomBrief(val id: String, val n: String, val l: Boolean = false, val m: Int = 0)

/** UDP discovery packet */
@Serializable data class Beacon(val a: String = "alma1", val id: String, val n: String, val p: Int, val r: List<RoomBrief> = emptyList())

@Serializable
data class Msg(
    val seq: Long = 0,
    val id: String = "",
    val from: String = "",
    val fromId: String = "",
    val time: Long = System.currentTimeMillis(),
    /** text | file | sys */
    val kind: String = "text",
    val text: String = "",
    val name: String = "",
    val path: String = "",
    val size: Long = 0,
    val fid: String = "",
    val group: String = "",
    /** local only: where this device saved the file */
    val savedUri: String = "",
)

@Serializable data class Events(val events: List<Msg>, val next: Long, val members: List<String> = emptyList(), val closed: Boolean = false)
@Serializable data class JoinReq(val name: String, val pass: String = "", val clientId: String = "")
@Serializable data class JoinRes(val token: String, val room: RoomBrief)
@Serializable data class OfferItem(val path: String, val size: Long)
@Serializable data class OfferReq(val fromId: String, val fromName: String, val items: List<OfferItem>)
@Serializable data class OfferState(val id: String, val state: String) // pending | accepted | rejected | done
@Serializable data class TextReq(val text: String)

// ---------------- persisted ----------------
@Serializable
data class SavedRoom(
    val id: String,
    val name: String,
    val mine: Boolean = false,
    val password: String = "",
    val lastHost: String = "",
    val messages: List<Msg> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
)

@Serializable
data class TransferData(
    val myId: String = java.util.UUID.randomUUID().toString().take(12),
    val myName: String = (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).take(30),
    val autoAccept: Boolean = false,
    val rooms: List<SavedRoom> = emptyList(),
)

// ---------------- helpers ----------------
object NetUtil {
    data class Iface(val name: String, val ip: String, val broadcast: String?)

    fun interfaces(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { ni ->
            ni.interfaceAddresses.filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
                .map { Iface(ni.name, it.address.hostAddress!!, it.broadcast?.hostAddress) }
        }
    }.getOrDefault(emptyList())

    /** Best address to show to others: hotspot / wifi first. */
    fun mainIp(): String? {
        val l = interfaces()
        return (l.firstOrNull { it.name.startsWith("ap") || it.name.startsWith("swlan") || it.name.contains("softap") }
            ?: l.firstOrNull { it.name.startsWith("wlan") } ?: l.firstOrNull())?.ip
    }

    /** Keeps only safe path segments (no "..", no absolute paths). */
    fun safePath(p: String): String = p.replace('\\', '/').split('/')
        .map { seg -> seg.trim().replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_") }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/").take(400).ifEmpty { "ملف" }

    /** Wrapped in a left-to-right isolate so "30 MB" doesn't flip inside Arabic text. */
    fun fmtSize(b: Long): String = "\u2066" + fmtSizeRaw(b) + "\u2069"

    private fun fmtSizeRaw(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}

/** Writes a received file into Downloads/حقيبة الما/<sub>/<path>. */
object Downloads {
    class Target(val uri: String, val out: OutputStream, private val finish: () -> Unit) { fun done() = finish() }

    fun open(ctx: Context, sub: String, path: String): Target {
        val rel = NetUtil.safePath(path)
        val dir = (listOf(SAVE_ROOT, NetUtil.safePath(sub)) + rel.split('/').dropLast(1)).joinToString("/")
        val name = rel.substringAfterLast('/')
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + dir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("لا يمكن إنشاء الملف")
            val out = cr.openOutputStream(uri) ?: error("لا يمكن الكتابة")
            return Target(uri.toString(), out) { cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
        } else {
            @Suppress("DEPRECATION")
            val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), dir).apply { mkdirs() }
            var f = File(base, name); var i = 1
            while (f.exists()) { f = File(base, name.substringBeforeLast('.', name) + " ($i)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else "")); i++ }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            return Target(uri.toString(), f.outputStream()) {}
        }
    }
}
