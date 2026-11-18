package com.alma.toolbox

import com.alma.toolbox.core.AppJson
import com.alma.toolbox.transfer.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class TransferServerTest {
    private lateinit var server: AlmaServer
    private lateinit var tmp: File
    private val port = 18765
    private val base = "http://127.0.0.1:$port"

    @Before fun up() {
        tmp = kotlin.io.path.createTempDirectory("alma-t").toFile()
        val saved = File(tmp, "downloads")
        server = AlmaServer(port, File(tmp, "rooms"), { File("src/main/assets/$it").inputStream() }) { sub, path ->
            val f = File(saved, "$sub/${NetUtil.safePath(path)}").apply { parentFile!!.mkdirs() }
            Downloads.Target(f.path, f.outputStream()) {}
        }
        server.info = { "dev1" to "هاتف الاختبار" }
        server.startServer()
        server.openRoom("r1", "غرفة الاختبار", "123")
    }

    @After fun down() { server.closeRoom(); server.stop(); tmp.deleteRecursively() }

    private fun call(method: String, path: String, body: ByteArray? = null): Pair<Int, ByteArray> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) { c.doOutput = true; c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect(); return code to bytes
    }
    private fun json(method: String, path: String, body: String) = call(method, path, body.toByteArray()).let { it.first to it.second.decodeToString() }

    @Test fun roomFlow() {
        assertEquals(403, json("POST", "/api/room/r1/join", """{"name":"علي","pass":"999"}""").first)
        val (code, res) = json("POST", "/api/room/r1/join", """{"name":"علي","pass":"123","clientId":"c1"}""")
        assertEquals(200, code)
        val token = AppJson.decodeFromString(JoinRes.serializer(), res).token
        assertEquals(401, call("GET", "/api/room/r1/events?since=0&t=bad").first)

        json("POST", "/api/room/r1/msg?t=$token", """{"text":"السلام عليكم"}""")
        val data = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        assertEquals(200, call("POST", "/api/room/r1/upload?t=$token&group=g1&path=" + Http.enc("مجلدي/صور/a.bin"), data).first)
        call("POST", "/api/room/r1/upload?t=$token&group=g1&path=" + Http.enc("مجلدي/b.txt"), "hello".toByteArray())
        call("POST", "/api/room/r1/upload?t=$token&group=g2&path=" + Http.enc("../../etc/passwd"), "x".toByteArray())

        val ev = AppJson.decodeFromString(Events.serializer(), call("GET", "/api/room/r1/events?since=0&t=$token").second.decodeToString())
        val files = ev.events.filter { it.kind == "file" }
        assertEquals(listOf("مجلدي/صور/a.bin", "مجلدي/b.txt", "etc/passwd"), files.map { it.path }) // traversal stripped
        assertTrue(ev.events.any { it.text == "السلام عليكم" && it.from == "علي" })
        assertEquals(listOf("علي"), ev.members)

        val dl = call("GET", "/api/room/r1/file/${files[0].fid}?t=$token").second
        assertArrayEquals(data, dl)

        val zip = ZipInputStream(call("GET", "/api/room/r1/zip/g1?t=$token").second.inputStream())
        val names = generateSequence { zip.nextEntry }.map { it.name to zip.readBytes().size }.toList()
        assertEquals(listOf("مجلدي/صور/a.bin" to data.size, "مجلدي/b.txt" to 5), names)

        // long-poll returns immediately when something new arrives
        val t0 = System.currentTimeMillis()
        Thread { Thread.sleep(300); server.room!!.post(Msg(from = "الهاتف", text = "رد")) }.start()
        val ev2 = AppJson.decodeFromString(Events.serializer(), call("GET", "/api/room/r1/events?since=${ev.next}&t=$token").second.decodeToString())
        assertEquals("رد", ev2.events.single().text)
        assertTrue(System.currentTimeMillis() - t0 < 5000)

        val roomDir = server.room!!.dir
        server.closeRoom()
        assertFalse("temp files removed on close", roomDir.exists())
        assertEquals(404, call("GET", "/api/room/r1/events?since=0&t=$token").first)
    }

    @Test fun directSendFlow() {
        val offerJson = AppJson.encodeToString(OfferReq.serializer(), OfferReq("p2", "سامي", listOf(OfferItem("رحلة/1.jpg", 4), OfferItem("رحلة/2.jpg", 3))))
        var seen: AlmaServer.Offer? = null
        server.onOffer = { seen = it }
        val st = AppJson.decodeFromString(OfferState.serializer(), json("POST", "/api/offer", offerJson).second)
        assertEquals("pending", st.state); assertNotNull(seen)
        assertEquals(403, call("POST", "/api/offer/${st.id}/file?path=x", "abcd".toByteArray()).first) // not accepted yet
        seen!!.state = "accepted"
        call("POST", "/api/offer/${st.id}/file?path=" + Http.enc("رحلة/1.jpg"), "abcd".toByteArray())
        call("POST", "/api/offer/${st.id}/file?path=" + Http.enc("رحلة/2.jpg"), "xyz".toByteArray())
        call("POST", "/api/offer/${st.id}/done", "{}".toByteArray())
        assertEquals("abcd", File(tmp, "downloads/سامي/رحلة/1.jpg").readText())
        assertEquals("xyz", File(tmp, "downloads/سامي/رحلة/2.jpg").readText())
        assertEquals(7L, seen!!.received); assertEquals("done", seen!!.state)
    }

    @Test fun appClientCalls() {
        val err = runCatching { Http.post("$base/api/room/r1/join", """{"name":"x","pass":"0"}""", JoinRes.serializer()) }.exceptionOrNull()
        assertTrue(err is ApiError); assertEquals("كلمة السر غير صحيحة", err!!.message)
        val j = Http.post("$base/api/room/r1/join", AppJson.encodeToString(JoinReq.serializer(), JoinReq("منى", "123", "c9")), JoinRes.serializer())
        assertEquals("غرفة الاختبار", j.room.n); assertTrue(j.room.l)
        val info = Http.get("$base/api/info", Beacon.serializer())
        assertEquals("هاتف الاختبار", info.n); assertEquals("r1", info.r.single().id)
        val ev = Http.get("$base/api/room/r1/events?since=0&t=${j.token}", Events.serializer())
        assertTrue(ev.events.any { it.text == "انضم منى" })
    }

    /** Drives the real web page in Chromium. Run with ALMA_E2E=<path to e2e.js>. */
    @Test fun browserClient() {
        val script = System.getenv("ALMA_E2E"); assumeTrue(script != null)
        Thread { Thread.sleep(4000); server.room?.post(Msg(from = "صاحب الغرفة", fromId = "host", text = "أهلاً بالجميع من التطبيق")) }.start()
        val p = ProcessBuilder("node", script, "$base/?r=r1", tmp.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); val code = p.waitFor()
        println(out)
        assertEquals(0, code)
        val paths = server.room!!.files.values.map { it.path }.sorted()
        println("server got: $paths")
        assertTrue(paths.contains("ملاحظات.txt"))
        assertTrue(paths.containsAll(listOf("test-folder/a.txt", "test-folder/sub/b.txt")))
    }
}
