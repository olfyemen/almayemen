package com.alma.toolbox.transfer

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.AppJson
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class Peer(val id: String, val name: String, val ip: String, val port: Int, val rooms: List<RoomBrief>, val seen: Long) {
    val base get() = "http://$ip:$port"
}

/** A file to send: from a content Uri, with its relative path (folders keep their structure). */
data class SendItem(val uri: Uri, val path: String, val size: Long)

data class Job(val id: String, val title: String, val incoming: Boolean, val done: Long, val total: Long, val state: String = "run", val error: String = "")

data class Hotspot(val ssid: String, val pass: String)

class ApiError(msg: String) : Exception(msg)

object Http {
    private fun conn(url: String, method: String = "GET", timeout: Int = 15000) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 6000; readTimeout = timeout; useCaches = false
    }

    private fun HttpURLConnection.result(): String {
        val code = responseCode
        val txt = (if (code >= 400) errorStream else inputStream)?.use { it.readBytes().decodeToString() } ?: ""
        if (code >= 400) throw ApiError(runCatching { AppJson.parseToJsonElement(txt).let { (it as kotlinx.serialization.json.JsonObject)["error"].toString().trim('"') } }.getOrDefault("خطأ $code"))
        return txt
    }

    fun <T> get(url: String, ser: KSerializer<T>, timeout: Int = 15000): T = conn(url, timeout = timeout).let { c -> try { AppJson.decodeFromString(ser, c.result()) } finally { c.disconnect() } }

    fun <T> post(url: String, body: String, ser: KSerializer<T>): T = conn(url, "POST").let { c ->
        try {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val b = body.toByteArray(); c.setFixedLengthStreamingMode(b.size); c.outputStream.use { it.write(b) }
            AppJson.decodeFromString(ser, c.result())
        } finally { c.disconnect() }
    }

    fun upload(ctx: Context, url: String, item: SendItem, onBytes: (Long) -> Unit) {
        val c = conn(url, "POST", 120_000)
        try {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/octet-stream"); c.setFixedLengthStreamingMode(item.size)
            c.outputStream.use { out ->
                ctx.contentResolver.openInputStream(item.uri)!!.use { ins ->
                    val buf = ByteArray(256 * 1024); var left = item.size
                    while (left > 0) { val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; out.write(buf, 0, n); left -= n; onBytes(n.toLong()) }
                }
            }
            c.result()
        } finally { c.disconnect() }
    }

    /** Streams a room file into Downloads; returns the saved uri. */
    fun download(ctx: Context, url: String, sub: String, path: String, onBytes: (Long) -> Unit): String {
        val c = conn(url, timeout = 120_000)
        try {
            if (c.responseCode >= 400) c.result()
            val t = Downloads.open(ctx, sub, path)
            try {
                c.inputStream.use { ins -> t.out.buffered(256 * 1024).use { out -> val buf = ByteArray(256 * 1024); while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); onBytes(n.toLong()) } } }
            } finally { t.done() }
            return t.uri
        } finally { c.disconnect() }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Live room connection (used for rooms hosted elsewhere AND for our own room via localhost). */
class RoomClient(private val ctx: Context, val base: String, val roomId: String, val roomName: String, val mine: Boolean) {
    val messages = MutableStateFlow<List<Msg>>(Stores.transfer.value.rooms.firstOrNull { it.id == roomId }?.messages ?: emptyList())
    val members = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("connecting") // connecting | ok | error | closed
    val error = MutableStateFlow("")
    private var token = ""
    @Volatile private var running = false
    private val myId get() = Stores.transfer.value.myId

    fun join(name: String, pass: String) {
        val r = Http.post("$base/api/room/$roomId/join", AppJson.encodeToString(JoinReq.serializer(), JoinReq(name, pass, myId)), JoinRes.serializer())
        token = r.token
        status.value = "ok"
        running = true
        Thread { loop() }.start()
    }

    private fun loop() {
        // only fetch what was posted after we joined; older history is kept locally
        var since = 0L
        while (running) {
            try {
                val ev = Http.get("$base/api/room/$roomId/events?since=$since&t=$token", Events.serializer(), 40_000)
                since = ev.next; members.value = ev.members; status.value = "ok"
                val known = messages.value.map { it.id }.toHashSet()
                val fresh = ev.events.filter { it.id !in known }
                if (fresh.isNotEmpty()) {
                    messages.update { it + fresh }
                    persist()
                    fresh.filter { it.kind == "file" && it.fromId != myId }.forEach { autoSave(it) }
                    fresh.filter { it.kind == "text" && it.fromId != myId }.lastOrNull()?.let { TransferHub.notifyMessage(ctx, roomName, it) }
                }
                if (ev.closed) { status.value = "closed"; running = false }
            } catch (e: Exception) {
                if (!running) break
                status.value = "error"; error.value = e.message ?: "انقطع الاتصال"
                if (e is ApiError) { running = false; break }
                Thread.sleep(2000)
            }
        }
    }

    private fun autoSave(m: Msg) = TransferHub.runJob(ctx, "⬇ ${m.name}", true, m.size) { prog ->
        val uri = Http.download(ctx, "$base/api/room/$roomId/file/${m.fid}?t=$token", roomName, m.path, prog)
        messages.update { l -> l.map { if (it.id == m.id) it.copy(savedUri = uri.toString()) else it } }
        persist()
    }

    fun send(text: String) = Thread { runCatching { Http.post("$base/api/room/$roomId/msg?t=$token", AppJson.encodeToString(TextReq.serializer(), TextReq(text)), serializer<Map<String, String>>()) }
        .onFailure { error.value = it.message ?: "تعذر الإرسال" } }.start()

    fun upload(items: List<SendItem>) {
        val group = UUID.randomUUID().toString().take(8)
        TransferHub.runJob(ctx, "⬆ " + (if (items.size == 1) items[0].path else "${items.size} ملفات"), false, items.sumOf { it.size }) { prog ->
            items.forEach { it2 -> Http.upload(ctx, "$base/api/room/$roomId/upload?t=$token&group=$group&path=${Http.enc(it2.path)}", it2, prog) }
        }
    }

    fun fileUrl(m: Msg) = "$base/api/room/$roomId/file/${m.fid}?t=$token"

    fun leave() {
        running = false
        Thread { runCatching { Http.post("$base/api/room/$roomId/leave?t=$token", "{}", serializer<Map<String, String>>()) } }.start()
    }

    private fun persist() {
        val msgs = messages.value.filter { it.kind != "sys" }.takeLast(2000)
        Stores.transfer.update { d ->
            val old = d.rooms.firstOrNull { it.id == roomId }
            val r = (old ?: SavedRoom(roomId, roomName, mine)).copy(name = roomName, lastHost = base, messages = msgs, updated = System.currentTimeMillis())
            d.copy(rooms = listOf(r) + d.rooms.filter { it.id != roomId })
        }
    }
}

object TransferHub {
    var server: AlmaServer? = null
    var port = HTTP_PORT
    val peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val offers = MutableStateFlow<List<AlmaServer.Offer>>(emptyList())
    val jobs = MutableStateFlow<List<Job>>(emptyList())
    val hotspot = MutableStateFlow<Hotspot?>(null)
    val hotspotError = MutableStateFlow("")
    val client = MutableStateFlow<RoomClient?>(null)
    @Volatile var uiVisible = false
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start(ctx: Context) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, TransferService::class.java))
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, TransferService::class.java))
    }

    fun url(): String? = NetUtil.mainIp()?.let { "http://$it:$port" }

    // ---------- jobs ----------
    fun runJob(ctx: Context, title: String, incoming: Boolean, total: Long, work: (prog: (Long) -> Unit) -> Unit) {
        val id = UUID.randomUUID().toString()
        jobs.update { it + Job(id, title, incoming, 0, total) }
        Thread {
            var done = 0L; var last = 0L
            try {
                work { n -> done += n; val now = System.currentTimeMillis(); if (now - last > 200) { last = now; jobs.update { l -> l.map { if (it.id == id) it.copy(done = done) else it } } } }
                jobs.update { l -> l.map { if (it.id == id) it.copy(done = total, state = "done") else it } }
            } catch (e: Exception) {
                jobs.update { l -> l.map { if (it.id == id) it.copy(state = "error", error = e.message ?: "فشل") else it } }
            }
        }.start()
    }

    fun clearFinished() = jobs.update { l -> l.filter { it.state == "run" } }

    // ---------- direct send ----------
    fun sendTo(ctx: Context, peer: Peer, items: List<SendItem>, onState: (String) -> Unit) {
        val d = Stores.transfer.value
        val title = "⬆ إلى ${peer.name}: " + if (items.size == 1) items[0].path else "${items.size} ملفات"
        runJob(ctx, title, false, items.sumOf { it.size }) { prog ->
            val o = Http.post("${peer.base}/api/offer", AppJson.encodeToString(OfferReq.serializer(), OfferReq(d.myId, d.myName, items.map { OfferItem(it.path, it.size) })), OfferState.serializer())
            onState("بانتظار موافقة ${peer.name}…")
            var st = o.state; val until = System.currentTimeMillis() + 90_000
            while (st == "pending" && System.currentTimeMillis() < until) { Thread.sleep(1000); st = Http.get("${peer.base}/api/offer/${o.id}", OfferState.serializer()).state }
            if (st != "accepted") { onState(if (st == "rejected") "رفض ${peer.name} الاستلام" else "لم يرد ${peer.name}"); throw ApiError(if (st == "rejected") "مرفوض" else "لا رد") }
            onState("يُرسل…")
            items.forEach { Http.upload(ctx, "${peer.base}/api/offer/${o.id}/file?path=${Http.enc(it.path)}", it, prog) }
            runCatching { Http.post("${peer.base}/api/offer/${o.id}/done", "{}", serializer<Map<String, String>>()) }
            onState("تم الإرسال ✓")
        }
    }

    fun answerOffer(ctx: Context, id: String, accept: Boolean) {
        val o = server?.offers?.get(id) ?: return
        o.state = if (accept) "accepted" else "rejected"
        offers.update { l -> l.filter { it.id != id } }
        Notify.nm(ctx).cancel(id.hashCode())
        if (accept) {
            val jid = "offer-" + o.id
            jobs.update { it + Job(jid, "⬇ من ${o.req.fromName}: " + (if (o.req.items.size == 1) o.req.items[0].path else "${o.req.items.size} ملفات"), true, 0, o.total) }
        }
    }

    // ---------- rooms ----------
    fun hostRoom(ctx: Context, id: String, name: String, pass: String): RoomClient {
        TransferHub.start(ctx)
        var waited = 0
        while (server == null && waited < 5000) { Thread.sleep(100); waited += 100 }
        val s = server ?: error("تعذر تشغيل خادم النقل")
        s.openRoom(id, name, pass)
        Stores.transfer.update { d ->
            val old = d.rooms.firstOrNull { it.id == id }
            d.copy(rooms = listOf((old ?: SavedRoom(id, name, true)).copy(name = name, mine = true, password = pass, updated = System.currentTimeMillis())) + d.rooms.filter { it.id != id })
        }
        return connect(ctx, "http://127.0.0.1:$port", id, name, pass, mine = true)
    }

    fun connect(ctx: Context, base: String, id: String, name: String, pass: String, mine: Boolean = false): RoomClient {
        client.value?.leave()
        val c = RoomClient(ctx.applicationContext, base, id, name, mine)
        c.join(Stores.transfer.value.myName, pass) // throws on wrong password
        client.value = c
        return c
    }

    fun leaveRoom() {
        val c = client.value ?: return
        c.leave(); client.value = null
        if (c.mine) server?.closeRoom()
    }

    // ---------- notifications ----------
    fun notifyMessage(ctx: Context, room: String, m: Msg) {
        if (uiVisible || !Notify.canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, Notify.channelFor(ctx, null, true))
            .setSmallIcon(R.drawable.ic_notify).setContentTitle("💬 $room · ${m.from}").setContentText(m.text)
            .setAutoCancel(true).setContentIntent(Notify.openApp(ctx, Tool.TRANSFER.id, 11)).build()
        Notify.nm(ctx).notify(5151, n)
    }

    // ---------- hotspot ----------
    @Suppress("DEPRECATION")
    fun startHotspot(ctx: Context) {
        hotspotError.value = ""
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    hotspot.value = if (Build.VERSION.SDK_INT >= 30) {
                        val c = r.softApConfiguration
                        Hotspot((if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString() else c.ssid)?.trim('"') ?: "", c.passphrase ?: "")
                    } else Hotspot(r.wifiConfiguration?.SSID?.trim('"') ?: "", r.wifiConfiguration?.preSharedKey?.trim('"') ?: "")
                }
                override fun onStopped() { hotspot.value = null }
                override fun onFailed(reason: Int) {
                    hotspotError.value = when (reason) {
                        ERROR_TETHERING_DISALLOWED -> "الهوتسبوت ممنوع على هذا الجهاز"
                        ERROR_INCOMPATIBLE_MODE -> "أطفئ هوتسبوت الهاتف العادي أولاً ثم حاول"
                        ERROR_NO_CHANNEL -> "لا توجد قناة واي فاي متاحة"
                        else -> "تعذر تشغيل الهوتسبوت ($reason)"
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) { hotspotError.value = "يحتاج إذن الموقع والأجهزة القريبة، وتشغيل الموقع (GPS)" }
        catch (e: Exception) { hotspotError.value = e.message ?: "تعذر تشغيل الهوتسبوت" }
    }

    fun stopHotspot() { reservation?.close(); reservation = null; hotspot.value = null }

    /** Temporary room files are only valid while the app runs. */
    fun cleanTemp(ctx: Context) = File(ctx.cacheDir, "alma-room").deleteRecursively()

    // ---------- picking ----------
    fun itemsFromUris(ctx: Context, uris: List<Uri>): List<SendItem> = uris.mapNotNull { u ->
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) SendItem(u, NetUtil.safePath(c.getString(0) ?: "ملف"), c.getLong(1)) else null
        }
    }

    fun itemsFromTree(ctx: Context, tree: Uri): List<SendItem> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val out = ArrayList<SendItem>()
        fun walk(d: DocumentFile, prefix: String) {
            d.listFiles().forEach { f ->
                val p = "$prefix/${f.name ?: "ملف"}"
                if (f.isDirectory) walk(f, p) else out += SendItem(f.uri, NetUtil.safePath(p), f.length())
            }
        }
        walk(root, root.name ?: "مجلد")
        return out
    }
}

/** Keeps the server, discovery and room alive while sharing (also with the screen off). */
class TransferService : Service() {
    @Volatile private var running = false
    private var mlock: WifiManager.MulticastLock? = null
    private var wlock: WifiManager.WifiLock? = null
    private var sock: DatagramSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Stores.init(this)
        ServiceCompat.startForeground(this, 6161, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        startServer()
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        mlock = wm.createMulticastLock("alma").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wlock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "alma").apply { setReferenceCounted(false); acquire() }
        running = true
        Thread { discoveryLoop() }.start()
    }

    private fun notification() = NotificationCompat.Builder(this, Notify.CH_ONGOING)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("📲 نقل الملفات يعمل")
        .setContentText(TransferHub.url()?.let { "جاهز للاستقبال · $it" } ?: "جاهز للاستقبال")
        .setOngoing(true).setSilent(true)
        .setContentIntent(Notify.openApp(this, Tool.TRANSFER.id, 12))
        .addAction(0, "إيقاف", PendingIntent.getService(this, 13, Intent(this, TransferService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun startServer() {
        if (TransferHub.server != null) return
        for (p in HTTP_PORT until HTTP_PORT + 10) {
            val s = AlmaServer(applicationContext, p)
            if (runCatching { s.startServer() }.isSuccess) {
                TransferHub.port = p; TransferHub.server = s
                s.info = { Stores.transfer.value.myId to Stores.transfer.value.myName }
                s.onOffer = { o -> if (Stores.transfer.value.autoAccept) { TransferHub.answerOffer(this, o.id, true) } else { TransferHub.offers.update { it + o }; offerNotification(o) } }
                s.onOfferProgress = { o ->
                    TransferHub.jobs.update { l -> l.map { if (it.id == "offer-" + o.id) it.copy(done = o.received, state = if (o.state == "done") "done" else "run") else it } }
                }
                return
            }
        }
    }

    private fun offerNotification(o: AlmaServer.Offer) {
        if (!Notify.canPost(this)) return
        fun act(a: String) = PendingIntent.getService(this, (o.id + a).hashCode(), Intent(this, TransferService::class.java).setAction(a).putExtra("id", o.id), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notify.channelFor(this, null, true))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("📥 ${o.req.fromName} يريد إرسال ملفات")
            .setContentText("${o.req.items.size} ملف · ${NetUtil.fmtSize(o.total)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(Notify.openApp(this, Tool.TRANSFER.id, 14))
            .addAction(0, "قبول", act("accept")).addAction(0, "رفض", act("reject"))
            .build()
        Notify.nm(this).notify(o.id.hashCode(), n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> stopSelf()
            "accept" -> TransferHub.answerOffer(this, intent.getStringExtra("id") ?: "", true)
            "reject" -> TransferHub.answerOffer(this, intent.getStringExtra("id") ?: "", false)
        }
        return START_NOT_STICKY
    }

    private fun discoveryLoop() {
        val s = runCatching { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(DISCOVERY_PORT)) } }.getOrNull()
        sock = s
        var lastSend = 0L
        val buf = ByteArray(8192)
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastSend > 2000) {
                lastSend = now
                val d = Stores.transfer.value
                val b = AppJson.encodeToString(Beacon.serializer(), Beacon(id = d.myId, n = d.myName, p = TransferHub.port, r = listOfNotNull(TransferHub.server?.room?.brief()))).toByteArray()
                val targets = (NetUtil.interfaces().mapNotNull { it.broadcast } + "255.255.255.255").distinct()
                val out = s ?: runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
                targets.forEach { t -> runCatching { out?.send(DatagramPacket(b, b.size, InetAddress.getByName(t), DISCOVERY_PORT)) } }
                if (out !== s) out?.close()
                TransferHub.peers.update { m -> m.filterValues { now - it.seen < 9000 } }
            }
            if (s == null) { Thread.sleep(500); continue }
            try {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                val bcn = AppJson.decodeFromString(Beacon.serializer(), String(p.data, 0, p.length))
                if (bcn.a == "alma1" && bcn.id != Stores.transfer.value.myId) {
                    val peer = Peer(bcn.id, bcn.n, p.address.hostAddress ?: continue, bcn.p, bcn.r, System.currentTimeMillis())
                    TransferHub.peers.update { it + (peer.id to peer) }
                }
            } catch (_: java.net.SocketTimeoutException) {} catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running = false
        sock?.close()
        TransferHub.leaveRoom()
        TransferHub.server?.closeRoom(); TransferHub.server?.stop(); TransferHub.server = null
        TransferHub.stopHotspot()
        TransferHub.cleanTemp(this)
        TransferHub.peers.value = emptyMap()
        mlock?.release(); wlock?.release()
        super.onDestroy()
    }
}
