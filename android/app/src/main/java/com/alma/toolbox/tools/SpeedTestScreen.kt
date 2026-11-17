package com.alma.toolbox.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alma.toolbox.ui.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private const val BASE = "https://speed.cloudflare.com"

fun networkType(ctx: Context): String {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "غير متصل"
    return when {
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "واي فاي"
        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الجوال"
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "إيثرنت"
        c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "متصل"
    }
}

private fun conn(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 8000; readTimeout = 10000; useCaches = false; setRequestProperty("Cache-Control", "no-cache")
}

private suspend fun ping(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
    val l = (1..6).mapNotNull {
        runCatching {
            val t = System.nanoTime(); val c = conn("$BASE/__down?bytes=0"); c.inputStream.use { it.readBytes() }; c.disconnect()
            (System.nanoTime() - t) / 1e6
        }.getOrNull()
    }.drop(1).sorted()
    if (l.isEmpty()) null else l[l.size / 2] to (l.last() - l.first())
}

/** Runs [threads] workers for [seconds]; [onRate] gets live Mbps. */
private suspend fun measure(upload: Boolean, seconds: Int, threads: Int, onRate: (Double) -> Unit): Double = coroutineScope {
    val bytes = AtomicLong(0)
    val start = System.nanoTime()
    val end = start + seconds * 1_000_000_000L
    val chunk = ByteArray(1 shl 20)
    val workers = (1..threads).map {
        launch(Dispatchers.IO) {
            while (isActive && System.nanoTime() < end) {
                runCatching {
                    if (!upload) {
                        val c = conn("$BASE/__down?bytes=25000000")
                        c.inputStream.use { ins -> val buf = ByteArray(64 * 1024); while (isActive && System.nanoTime() < end) { val n = ins.read(buf); if (n < 0) break; bytes.addAndGet(n.toLong()) } }
                        c.disconnect()
                    } else {
                        val c = conn("$BASE/__up").apply { doOutput = true; requestMethod = "POST"; setFixedLengthStreamingMode(4 * chunk.size); setRequestProperty("Content-Type", "application/octet-stream") }
                        c.outputStream.use { o -> repeat(4) { if (System.nanoTime() < end) { o.write(chunk); bytes.addAndGet(chunk.size.toLong()) } } }
                        runCatching { c.inputStream.use { it.readBytes() } }; c.disconnect()
                    }
                }.onFailure { delay(300) }
            }
        }
    }
    val ticker = launch { while (isActive) { delay(250); val s = (System.nanoTime() - start) / 1e9; if (s > 0.3) onRate(bytes.get() * 8 / s / 1e6) } }
    workers.joinAll(); ticker.cancel()
    bytes.get() * 8 / ((System.nanoTime() - start) / 1e9) / 1e6
}

data class SpeedResult(val down: Double, val up: Double, val ping: Double, val jitter: Double, val net: String, val at: String)

@Composable
fun SpeedTestScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf("") }
    var live by remember { mutableDoubleStateOf(0.0) }
    var down by remember { mutableStateOf<Double?>(null) }
    var up by remember { mutableStateOf<Double?>(null) }
    var pg by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val history = remember { mutableStateListOf<SpeedResult>() }
    val hist = remember { History(120) }
    var tick by remember { mutableIntStateOf(0) }
    val net = remember(phase) { networkType(ctx) }

    fun run() {
        job = scope.launch {
            down = null; up = null; pg = null; hist.clear()
            phase = "ping"; pg = ping()
            if (pg == null) { phase = "error"; return@launch }
            phase = "down"; down = measure(false, 10, 4) { live = it; hist.add(it.toFloat()); tick++ }
            phase = "up"; live = 0.0; up = measure(true, 8, 3) { live = it; hist.add(it.toFloat()); tick++ }
            phase = "done"
            history.add(0, SpeedResult(down!!, up!!, pg!!.first, pg!!.second, networkType(ctx), LocalDateTime.now().let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) }))
        }
    }
    val running = job?.isActive == true
    fun f(v: Double?) = v?.let { String.format(Locale.US, if (it < 10) "%.2f" else "%.1f", it) } ?: "—"

    ToolScaffold("سرعة الإنترنت 🚀", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("الشبكة: $net", style = MaterialTheme.typography.bodyMedium)
            val shown = if (running && (phase == "down" || phase == "up")) live else down ?: 0.0
            Gauge(Math.log10(shown + 1).toFloat(), 0f, 3f, "${f(if (running || down != null) shown else null)}\nميجابت/ث", if (phase == "up") Accent2 else Accent, Modifier.size(230.dp))
            Text(when (phase) { "ping" -> "قياس الاستجابة…"; "down" -> "⬇ قياس التنزيل…"; "up" -> "⬆ قياس الرفع…"; "done" -> "اكتمل ✓"; "error" -> "تعذر الاتصال بخادم القياس"; else -> "اضغط ابدأ" },
                fontWeight = FontWeight.Bold, color = if (phase == "error") Bad else MaterialTheme.colorScheme.onBackground)
            @Suppress("UNUSED_EXPRESSION") tick
            if (hist.size > 2) LineChart(listOf(hist.toList()), fixedMin = 0f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Res("⬇ تنزيل", f(down), "Mbps"); Res("⬆ رفع", f(up), "Mbps"); Res("⏱ استجابة", pg?.let { "${it.first.toInt()}" } ?: "—", "ms"); Res("〰 تذبذب", pg?.let { "${it.second.toInt()}" } ?: "—", "ms")
            }
            Button(onClick = { if (running) { job?.cancel(); phase = "" } else run() }, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text(if (running) "إيقاف" else "ابدأ القياس", fontSize = 20.sp) }
            if (net == "بيانات الجوال") Text("⚠ القياس يستهلك نحو 50–200 ميجابايت من باقة البيانات.", color = Warn, style = MaterialTheme.typography.bodySmall)
            if (history.isNotEmpty()) SectionCard(title = "نتائج هذه الجلسة") {
                history.forEach { r -> Text("${r.at} · ${r.net} · ⬇ ${f(r.down)} · ⬆ ${f(r.up)} · ${r.ping.toInt()}ms", style = MaterialTheme.typography.bodySmall) }
            }
            Text("خادم القياس: Cloudflare (أقرب مركز بيانات).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Res(l: String, v: String, u: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(l, style = MaterialTheme.typography.bodySmall); Text(v, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp); Text(u, style = MaterialTheme.typography.bodySmall)
    }
}
