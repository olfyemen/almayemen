package com.alma.toolbox.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.alma.toolbox.BuildConfigVersion
import com.alma.toolbox.CreditsCard
import com.alma.toolbox.core.AppJson
import com.alma.toolbox.transfer.NetUtil
import com.alma.toolbox.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val REPO = "olfyemen/almayemen"

private fun http(url: String, method: String = "GET", body: String? = null): Pair<Int, String> {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 10000; readTimeout = 20000; instanceFollowRedirects = true
        setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "AlmaToolbox")
    }
    try {
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val b = body.toByteArray(); c.setFixedLengthStreamingMode(b.size); c.outputStream.use { it.write(b) }
        }
        val code = c.responseCode
        val txt = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() } ?: ""
        return code to txt
    } finally { c.disconnect() }
}

fun installedVersionCode(ctx: Context): Long {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
}

// ---------------- updates (GitHub releases) ----------------
data class Release(val name: String, val notes: String, val code: Long, val url: String, val size: Long)

object Updater {
    /** Latest release whose asset is named alma-<versionCode>.apk. */
    fun latest(): Release? {
        val (code, txt) = http("https://api.github.com/repos/$REPO/releases/latest")
        if (code == 404) return null
        if (code >= 400) error("تعذر الاتصال بـ GitHub ($code)")
        return parse(txt)
    }

    fun parse(txt: String): Release? {
        val o = AppJson.parseToJsonElement(txt).jsonObject
        val asset = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { Regex("alma-\\d+\\.apk").matches(it["name"]?.jsonPrimitive?.content ?: "") } ?: return null
        val vc = Regex("\\d+").find(asset["name"]!!.jsonPrimitive.content)!!.value.toLong()
        return Release(o["name"]?.jsonPrimitive?.contentOrNull ?: o["tag_name"]!!.jsonPrimitive.content, o["body"]?.jsonPrimitive?.contentOrNull ?: "",
            vc, asset["browser_download_url"]!!.jsonPrimitive.content, asset["size"]?.jsonPrimitive?.long ?: 0)
    }

    fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "alma-${r.code}.apk")
        val c = (URL(r.url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 15000; readTimeout = 60000 }
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            c.inputStream.use { ins -> f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; if (total > 0) onProgress(done.toFloat() / total) }
            } }
        } finally { c.disconnect() }
        return f
    }

    /** Opens Android's installer. Returns false when the user must first allow installs from this app. */
    fun install(ctx: Context, f: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)))
            return false
        }
        val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

// ---------------- feedback (Google Sheet via Apps Script) ----------------
object Feedback {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("feedback", 0)

    /** The Apps Script URL lives in the repo (android/app-config.json on main) so it can change without a new app version. */
    fun url(ctx: Context): String {
        runCatching {
            val (code, txt) = http("https://raw.githubusercontent.com/$REPO/main/android/app-config.json")
            if (code == 200) AppJson.parseToJsonElement(txt).jsonObject["feedbackUrl"]?.jsonPrimitive?.contentOrNull?.let { prefs(ctx).edit().putString("url", it).apply() }
        }
        return prefs(ctx).getString("url", "") ?: ""
    }

    fun payload(ctx: Context, type: String, message: String, name: String, contact: String) = buildJsonObject {
        put("type", type); put("message", message); put("name", name); put("contact", contact)
        put("version", BuildConfigVersion.NAME + " (" + installedVersionCode(ctx) + ")")
        put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    }.toString()

    /** Sends now; on failure the message is queued and retried next time. Returns true when delivered. */
    fun send(ctx: Context, json: String): Boolean {
        val u = url(ctx)
        val ok = u.isNotEmpty() && runCatching { http(u, "POST", json).first in 200..399 }.getOrDefault(false)
        if (!ok) prefs(ctx).edit().putStringSet("queue", (prefs(ctx).getStringSet("queue", emptySet()) ?: emptySet()) + json).apply()
        return ok
    }

    fun queued(ctx: Context) = prefs(ctx).getStringSet("queue", emptySet())?.size ?: 0

    fun flush(ctx: Context): Int {
        val q = prefs(ctx).getStringSet("queue", emptySet()) ?: return 0
        if (q.isEmpty()) return 0
        val u = url(ctx); if (u.isEmpty()) return 0
        val sent = q.filter { runCatching { http(u, "POST", it).first in 200..399 }.getOrDefault(false) }
        prefs(ctx).edit().putStringSet("queue", q - sent.toSet()).apply()
        return sent.size
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, openRoznama: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // update state
    var checking by remember { mutableStateOf(false) }
    var rel by remember { mutableStateOf<Release?>(null) }
    var upMsg by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    val current = remember { installedVersionCode(ctx) }
    // feedback state
    var type by remember { mutableStateOf("اقتراح") }
    var msg by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(ctx.getSharedPreferences("feedback", 0).getString("name", "") ?: "") }
    var contact by remember { mutableStateOf(ctx.getSharedPreferences("feedback", 0).getString("contact", "") ?: "") }
    var sending by remember { mutableStateOf(false) }
    var queued by remember { mutableIntStateOf(Feedback.queued(ctx)) }
    LaunchedEffect(Unit) { if (queued > 0) { withContext(Dispatchers.IO) { Feedback.flush(ctx) }; queued = Feedback.queued(ctx) } }

    ToolScaffold("حول التطبيق والتواصل", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🧰", fontSize = 48.sp)
                Text("حقيبة الما", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("الإصدار ${BuildConfigVersion.NAME} (${current})", style = MaterialTheme.typography.bodySmall)
            }
            CreditsCard()

            SectionCard(title = "🔄 التحديث") {
                Text("التحديث اختياري: افحص وحدّث متى شئت، وتبقى كل بياناتك.", style = MaterialTheme.typography.bodySmall)
                val r = rel
                when {
                    progress != null -> { LinearProgressIndicator(progress = { progress!! }, modifier = Modifier.fillMaxWidth()); Text("جارٍ التنزيل… ${((progress ?: 0f) * 100).toInt()}%") }
                    r != null && r.code > current -> {
                        Text("✨ يتوفر ${r.name}", fontWeight = FontWeight.Bold, color = Good)
                        if (r.notes.isNotBlank()) Text(r.notes.take(1200), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            apk?.let { if (Updater.install(ctx, it)) return@Button else { upMsg = "اسمح لحقيبة الما بتثبيت التطبيقات ثم اضغط «تثبيت» مجدداً"; return@Button } }
                            progress = 0f
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { runCatching { Updater.download(ctx, r) { p -> progress = p } } }
                                progress = null
                                f.onSuccess { apk = it; if (!Updater.install(ctx, it)) upMsg = "اسمح لحقيبة الما بتثبيت التطبيقات ثم اضغط «تثبيت» مجدداً" }
                                    .onFailure { upMsg = "تعذر التنزيل: ${it.message}" }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (apk != null) "تثبيت" else "تنزيل وتثبيت (${NetUtil.fmtSize(r.size)})") }
                    }
                    else -> OutlinedButton(onClick = {
                        checking = true; upMsg = ""
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { runCatching { Updater.latest() } }
                            checking = false
                            res.onSuccess { rel = it; if (it == null || it.code <= current) upMsg = "لديك أحدث إصدار ✓" }.onFailure { upMsg = it.message ?: "تعذر الفحص، تأكد من الإنترنت" }
                        }
                    }, enabled = !checking, modifier = Modifier.fillMaxWidth()) { Text(if (checking) "جارٍ الفحص…" else "البحث عن تحديث") }
                }
                if (upMsg.isNotEmpty()) Text(upMsg, style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(title = "📅 تحديث الروزنامة") {
                val last = remember { com.alma.toolbox.roznama.RoznamaPrefs.last(ctx) }
                Text("تتحدث الروزنامة من جدول Google تلقائياً يوم الجمعة (أو أول فتح بعد جمعة فائتة)، ولا تتصل بالإنترنت في غير ذلك.", style = MaterialTheme.typography.bodySmall)
                Text("آخر تحديث: " + last.ifEmpty { "لم تتحدث بعد" }, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { com.alma.toolbox.roznama.RoznamaPrefs.requestRefresh(ctx); openRoznama() }, modifier = Modifier.fillMaxWidth()) { Text("تحديث الروزنامة الآن") }
            }

            SectionCard(title = "ℹ️ معلومات التطبيق") {
                var sizes by remember { mutableStateOf(appSizes(ctx)) }
                ValueRow("الإصدار", "${BuildConfigVersion.NAME} (${current})")
                ValueRow("عدد الأدوات", "${com.alma.toolbox.Tool.entries.size}")
                ValueRow("بياناتك", NetUtil.fmtSize(sizes.first))
                ValueRow("ملفات مؤقتة", NetUtil.fmtSize(sizes.second))
                ValueRow("أندرويد", "${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
                if (sizes.second > 0) TextButton(onClick = { clearCache(ctx); sizes = appSizes(ctx); Toast.makeText(ctx, "تم التنظيف ✓", Toast.LENGTH_SHORT).show() }) { Text("🧹 تنظيف الملفات المؤقتة") }
            }

            SectionCard(title = "✉️ رسالة للمبرمج") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("اقتراح", "مشكلة", "شكر", "سؤال").forEach { t -> FilterChip(type == t, { type = t }, label = { Text(t) }) }
                }
                OutlinedTextField(msg, { msg = it.take(5000) }, label = { Text("رسالتك") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("اسمك (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(contact, { contact = it.take(120) }, label = { Text("رقمك أو بريدك للرد (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("يُرفق تلقائياً إصدار التطبيق ونوع الجهاز فقط.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Button(onClick = {
                    sending = true
                    ctx.getSharedPreferences("feedback", 0).edit().putString("name", name).putString("contact", contact).apply()
                    val json = Feedback.payload(ctx, type, msg.trim(), name.trim(), contact.trim())
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Feedback.send(ctx, json) }
                        sending = false; queued = Feedback.queued(ctx)
                        if (ok) { msg = ""; Toast.makeText(ctx, "وصلت رسالتك، شكراً لك 🌹", Toast.LENGTH_LONG).show() }
                        else Toast.makeText(ctx, "لا يوجد اتصال الآن، حُفظت الرسالة وستُرسل لاحقاً", Toast.LENGTH_LONG).show()
                    }
                }, enabled = msg.isNotBlank() && !sending, modifier = Modifier.fillMaxWidth()) { Text(if (sending) "جارٍ الإرسال…" else "إرسال") }
                if (queued > 0) Text("$queued رسالة بانتظار الإرسال", style = MaterialTheme.typography.bodySmall, color = Warn)
            }

            SectionCard(title = "حقوق المحتوى") {
                Text("رسوم الشعار: Twemoji (CC-BY 4.0).\nأذان الجامع الكبير بصنعاء: تسجيل من المصمم. أذان المسجد النبوي: ejaz215 (CC BY 3.0)، وأذان عام (CC0)، من ويكيميديا كومنز.\nحصن المسلم: الشيخ سعيد القحطاني، النص من hisnmuslim.com.\nنص الصحيفة السجادية: ويكي مصدر (CC BY-SA).",
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$REPO"))) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("صفحة المشروع على GitHub") }
        }
    }
}

private fun dirSize(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0)

/** (data, cache) bytes — data = what the user keeps, cache = safe to delete. */
fun appSizes(ctx: Context): Pair<Long, Long> = dirSize(ctx.filesDir) + dirSize(File(ctx.filesDir.parentFile, "shared_prefs")) to dirSize(ctx.cacheDir)

/** Removes exported/shared copies, downloaded updates and other temporary files (never user data). */
fun clearCache(ctx: Context) { ctx.cacheDir.listFiles()?.filter { it.name != "alma-room" }?.forEach { it.deleteRecursively() } }
