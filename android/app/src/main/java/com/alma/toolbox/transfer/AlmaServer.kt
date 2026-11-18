package com.alma.toolbox.transfer

import android.content.Context
import com.alma.toolbox.core.AppJson
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A room hosted on this phone. All state is in memory; files live in a temp folder. */
class HostedRoom(val id: String, val name: String, val pass: String, val dir: File) {
    data class Member(val name: String, val clientId: String, var lastSeen: Long)
    data class FileEntry(val fid: String, val name: String, val path: String, val size: Long, val file: File, val group: String, val order: Long = System.nanoTime())

    val members = ConcurrentHashMap<String, Member>()
    val files = ConcurrentHashMap<String, FileEntry>()
    private val events = ArrayList<Msg>()
    private val lock = Object()
    private var seq = 0L
    @Volatile var closed = false

    fun brief() = RoomBrief(id, name, pass.isNotEmpty(), activeMembers().size)
    fun activeMembers() = members.values.filter { System.currentTimeMillis() - it.lastSeen < 60_000 }.map { it.name }.distinct()

    fun post(m: Msg): Msg = synchronized(lock) {
        val e = m.copy(seq = ++seq, id = if (m.id.isEmpty()) UUID.randomUUID().toString().take(10) else m.id)
        events.add(e); if (events.size > 3000) events.removeAt(0)
        lock.notifyAll(); e
    }

    fun since(s: Long, waitMs: Long): Events {
        synchronized(lock) {
            val end = System.currentTimeMillis() + waitMs
            while (!closed && (events.lastOrNull()?.seq ?: 0) <= s) {
                val left = end - System.currentTimeMillis(); if (left <= 0) break
                lock.wait(left)
            }
            return Events(events.filter { it.seq > s }, seq, activeMembers(), closed)
        }
    }

    fun close() { synchronized(lock) { closed = true; lock.notifyAll() }; dir.deleteRecursively() }
}

/**
 * [tempRoot] holds hosted-room files, [asset] serves the web page, [save] stores
 * direct-send files (Downloads on a phone; a plain folder in tests).
 */
class AlmaServer(
    port: Int,
    private val tempRoot: File,
    private val asset: (String) -> InputStream,
    private val save: (sub: String, path: String) -> Downloads.Target,
) : NanoHTTPD(port) {
    constructor(ctx: Context, port: Int) : this(port, File(ctx.cacheDir, "alma-room"), { ctx.assets.open(it) }, { sub, path -> Downloads.open(ctx, sub, path) })

    var room: HostedRoom? = null
        private set

    /** incoming direct-send offers */
    class Offer(val id: String, val req: OfferReq) { @Volatile var state = "pending"; @Volatile var received = 0L; val total = req.items.sumOf { it.size } }
    val offers = ConcurrentHashMap<String, Offer>()
    var onOffer: (Offer) -> Unit = {}
    var onOfferProgress: (Offer) -> Unit = {}
    var info: () -> Pair<String, String> = { "" to "" } // id, name

    fun openRoom(id: String, name: String, pass: String): HostedRoom {
        room?.close()
        val dir = File(tempRoot, id).apply { deleteRecursively(); mkdirs() }
        return HostedRoom(id, name, pass, dir).also { room = it; it.post(Msg(kind = "sys", text = "تم فتح الغرفة «$name»")) }
    }

    fun closeRoom() { room?.let { it.post(Msg(kind = "sys", text = "أُغلقت الغرفة")); it.close() }; room = null }

    private fun json(obj: String, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj).also { cors(it) }
    // errors close the connection so an unread request body can't confuse keep-alive
    private fun err(code: Response.Status, msg: String) =
        json("""{"error":${AppJson.encodeToString(kotlinx.serialization.serializer<String>(), msg)}}""", code).also { it.closeConnection(true) }
    private fun cors(r: Response) { r.addHeader("Access-Control-Allow-Origin", "*"); r.addHeader("Cache-Control", "no-store") }
    private inline fun <reified T> body(s: IHTTPSession): T {
        val len = s.headers["content-length"]?.toLongOrNull() ?: 0
        val bytes = ByteArray(len.toInt()); var off = 0
        while (off < bytes.size) { val n = s.inputStream.read(bytes, off, bytes.size - off); if (n < 0) break; off += n }
        return AppJson.decodeFromString(kotlinx.serialization.serializer<T>(), bytes.decodeToString())
    }
    private fun q(s: IHTTPSession, k: String) = s.parameters[k]?.firstOrNull() ?: ""

    /** Copies exactly `len` bytes of the request body. */
    private fun copyBody(s: IHTTPSession, out: java.io.OutputStream, len: Long, onBytes: (Long) -> Unit) {
        val buf = ByteArray(256 * 1024); var left = len
        while (left > 0) {
            val n = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break
            out.write(buf, 0, n); left -= n; onBytes(n.toLong())
        }
        if (left > 0) error("انقطع الاتصال")
    }

    override fun serve(s: IHTTPSession): Response = try { route(s) } catch (e: Exception) { err(Response.Status.INTERNAL_ERROR, e.message ?: "خطأ") }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri.trimEnd('/').ifEmpty { "/" }
        val parts = uri.trim('/').split('/')
        if (s.method == Method.OPTIONS) return newFixedLengthResponse("").also { cors(it); it.addHeader("Access-Control-Allow-Headers", "*") }
        if (uri == "/" || uri == "/index.html") return newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", asset("web/index.html"))
        if (uri == "/api/info") {
            val (id, name) = info()
            return json(AppJson.encodeToString(Beacon.serializer(), Beacon(id = id, n = name, p = listeningPort, r = listOfNotNull(room?.brief()))))
        }
        // ---------- rooms ----------
        if (parts.size >= 4 && parts[0] == "api" && parts[1] == "room") {
            val r = room?.takeIf { it.id == parts[2] && !it.closed } ?: return err(Response.Status.NOT_FOUND, "الغرفة غير موجودة أو أُغلقت")
            val action = parts[3]
            if (action == "join") {
                val j = body<JoinReq>(s)
                if (r.pass.isNotEmpty() && j.pass != r.pass) return err(Response.Status.FORBIDDEN, "كلمة السر غير صحيحة")
                val token = UUID.randomUUID().toString()
                val name = j.name.trim().take(30).ifEmpty { "ضيف" }
                r.members[token] = HostedRoom.Member(name, j.clientId, System.currentTimeMillis())
                r.post(Msg(kind = "sys", text = "انضم $name"))
                return json(AppJson.encodeToString(JoinRes.serializer(), JoinRes(token, r.brief())))
            }
            val m = r.members[q(s, "t")] ?: return err(Response.Status.UNAUTHORIZED, "انتهت الجلسة، ادخل الغرفة مجدداً")
            m.lastSeen = System.currentTimeMillis()
            return when (action) {
                "events" -> json(AppJson.encodeToString(Events.serializer(), r.since(q(s, "since").toLongOrNull() ?: 0, 25_000)))
                "msg" -> { val t = body<TextReq>(s).text.trim().take(4000); if (t.isNotEmpty()) r.post(Msg(from = m.name, fromId = m.clientId, text = t)); json("{}") }
                "leave" -> { r.members.remove(q(s, "t")); r.post(Msg(kind = "sys", text = "غادر ${m.name}")); json("{}") }
                "upload" -> {
                    val size = s.headers["content-length"]?.toLongOrNull() ?: return err(Response.Status.BAD_REQUEST, "الحجم مطلوب")
                    val path = NetUtil.safePath(q(s, "path").ifEmpty { q(s, "name") })
                    val fid = UUID.randomUUID().toString().take(12)
                    val f = File(r.dir, fid)
                    f.outputStream().buffered(256 * 1024).use { copyBody(s, it, size) {} }
                    r.files[fid] = HostedRoom.FileEntry(fid, path.substringAfterLast('/'), path, size, f, q(s, "group"))
                    r.post(Msg(from = m.name, fromId = m.clientId, kind = "file", name = path.substringAfterLast('/'), path = path, size = size, fid = fid, group = q(s, "group")))
                    json("{}")
                }
                "file" -> {
                    val fe = r.files[parts.getOrNull(4)] ?: return err(Response.Status.NOT_FOUND, "الملف لم يعد متاحاً")
                    newFixedLengthResponse(Response.Status.OK, "application/octet-stream", FileInputStream(fe.file), fe.size).also {
                        cors(it); it.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(fe.name, "UTF-8").replace("+", "%20"))
                    }
                }
                "zip" -> {
                    val g = parts.getOrNull(4) ?: ""
                    val list = r.files.values.filter { it.group == g }.sortedBy { it.order }
                    if (list.isEmpty()) return err(Response.Status.NOT_FOUND, "لا توجد ملفات")
                    val pin = PipedInputStream(512 * 1024); val pout = PipedOutputStream(pin)
                    Thread {
                        runCatching { ZipOutputStream(pout).use { z -> list.forEach { fe -> z.putNextEntry(ZipEntry(fe.path)); fe.file.inputStream().use { it.copyTo(z, 256 * 1024) }; z.closeEntry() } } }
                    }.start()
                    val top = list.first().path.substringBefore('/')
                    newChunkedResponse(Response.Status.OK, "application/zip", pin).also {
                        cors(it); it.addHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode("$top.zip", "UTF-8").replace("+", "%20"))
                    }
                }
                else -> err(Response.Status.NOT_FOUND, "غير موجود")
            }
        }
        // ---------- direct send ----------
        if (uri == "/api/offer" && s.method == Method.POST) {
            val o = Offer(UUID.randomUUID().toString().take(10), body(s))
            offers[o.id] = o; onOffer(o)
            return json(AppJson.encodeToString(OfferState.serializer(), OfferState(o.id, o.state)))
        }
        if (parts.size >= 3 && parts[0] == "api" && parts[1] == "offer") {
            val o = offers[parts[2]] ?: return err(Response.Status.NOT_FOUND, "الطلب غير موجود")
            if (parts.size == 3) return json(AppJson.encodeToString(OfferState.serializer(), OfferState(o.id, o.state)))
            if (o.state != "accepted") return err(Response.Status.FORBIDDEN, "لم يُقبل الإرسال")
            when (parts[3]) {
                "file" -> {
                    val size = s.headers["content-length"]?.toLongOrNull() ?: 0
                    val t = save(o.req.fromName, q(s, "path"))
                    try { t.out.buffered(256 * 1024).use { copyBody(s, it, size) { n -> o.received += n; onOfferProgress(o) } } } finally { t.done() }
                }
                "done" -> { o.state = "done"; onOfferProgress(o) }
            }
            return json("{}")
        }
        return err(Response.Status.NOT_FOUND, "غير موجود")
    }

    fun startServer() = start(120_000, false)
}
