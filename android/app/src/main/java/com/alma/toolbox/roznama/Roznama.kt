package com.alma.toolbox.roznama

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alma.toolbox.ui.Good
import com.alma.toolbox.ui.Bad
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.alma.toolbox.ui.ToolScaffold

object RoznamaPrefs {
    fun last(ctx: Context) = ctx.getSharedPreferences("roznama", 0).getString("last", "") ?: ""
    /** Asks the page to fetch from its Google Sheet the next time it opens. */
    fun requestRefresh(ctx: Context) = ctx.getSharedPreferences("roznama", 0).edit().putBoolean("refresh", true).apply()
}

/**
 * The daily Hijri «roznama» page (built earlier as a standalone HTML file), shown offline.
 * Its diary/settings live in the WebView's localStorage under "rz-*" keys; a small bridge
 * mirrors them into SharedPreferences("roznama") so the full backup includes them.
 */
class RoznamaBridge(private val ctx: Context) {
    private val prefs get() = ctx.getSharedPreferences("roznama", 0)
    @JavascriptInterface fun save(json: String) { prefs.edit().putString("ls", json).apply() }
    @JavascriptInterface fun load(): String = prefs.getString("ls", "") ?: ""
    @JavascriptInterface fun takeRefresh(): Boolean = prefs.getBoolean("refresh", false).also { if (it) prefs.edit().putBoolean("refresh", false).apply() }
    @JavascriptInterface fun setLast(d: String) { prefs.edit().putString("last", d).apply() }
    /** true once after a backup was restored: the saved copy must replace what the page has. */
    @JavascriptInterface fun takeRestore(): Boolean = prefs.getBoolean("restore", false).also { if (it) prefs.edit().putBoolean("restore", false).apply() }
}

private const val SYNC_JS = """
(function(){
  try {
    var PFX = 'rozname';
    function restoreFrom(saved){
      var o = JSON.parse(saved);
      for (var j = localStorage.length - 1; j >= 0; j--) { var k = localStorage.key(j); if (k.indexOf(PFX) === 0) localStorage.removeItem(k); }
      for (var k2 in o) localStorage.setItem(k2, o[k2]);
    }
    // first open after install / data clear, or right after a backup restore: the app's copy wins
    var firstHere = !localStorage.getItem('alma_synced');
    var forced = AlmaBridge.takeRestore();
    var saved = AlmaBridge.load();
    if ((firstHere || forced) && saved && saved !== '{}') {
      restoreFrom(saved);
      localStorage.setItem('alma_synced', '1');
      location.reload();
      return;
    }
    localStorage.setItem('alma_synced', '1');
    if (window.rzApi) {
      if (AlmaBridge.takeRefresh()) window.rzApi.refresh();
      setInterval(function(){ AlmaBridge.setLast(window.rzApi.lastFetch()); }, 4000);
    }
    var last = '';
    function push(){
      var o = {};
      for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); if (k.indexOf(PFX) === 0) o[k] = localStorage.getItem(k); }
      var s = JSON.stringify(o);
      if (s !== last) { last = s; AlmaBridge.save(s); }
    }
    push();
    setInterval(push, 3000);
    document.addEventListener('visibilitychange', push);
    window.addEventListener('pagehide', push);
  } catch (e) {}
})();
"""

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun RoznamaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val holder = remember { arrayOfNulls<WebView>(1) }
    var last by remember { mutableStateOf(RoznamaPrefs.last(ctx)) }
    var linked by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun readStatus(then: (String, Boolean, String) -> Unit) {
        holder[0]?.evaluateJavascript("window.rzApi ? window.rzApi.status() : ''") { raw ->
            runCatching {
                // evaluateJavascript returns a JSON-encoded string literal
                val inner = com.alma.toolbox.core.AppJson.parseToJsonElement(raw).jsonPrimitive.content
                val o = com.alma.toolbox.core.AppJson.parseToJsonElement(inner).jsonObject
                then(o["last"]!!.jsonPrimitive.content, o["linked"]!!.jsonPrimitive.boolean, o["msg"]!!.jsonPrimitive.content)
            }
        }
    }
    fun refreshNow() {
        busy = true; msg = "… جاري التحديث"
        holder[0]?.evaluateJavascript("window.rzApi && window.rzApi.refresh()", null)
        scope.launch {
            repeat(40) {
                delay(500)
                var done = false
                readStatus { l, k, m -> last = l; linked = k; if (m.isNotBlank()) msg = m; done = m.startsWith("✓") || m.startsWith("✗") || m.startsWith("ما في") }
                if (done) { busy = false; return@launch }
            }
            busy = false
        }
    }
    DisposableEffect(Unit) { onDispose { holder[0]?.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null) } }
    // also follows an update started from «حول» (the page runs it as soon as it opens)
    LaunchedEffect(Unit) { repeat(12) { delay(1500); readStatus { l, k, m -> last = l; linked = k; if (m.isNotBlank()) msg = m } } }

    ToolScaffold("الروزنامة اليومية 📅", onBack, actions = {
        TextButton(onClick = { refreshNow() }, enabled = !busy) { Text(if (busy) "…" else "🔄 تحديث") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(
                        (if (linked) "🔗 مرتبطة بجدول Google" else "⚠ غير مرتبطة بجدول") + " · آخر تحديث: " + last.ifEmpty { "لم تتحدث بعد" },
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
                    )
                    Text(msg.ifEmpty { "تتحدث تلقائياً كل جمعة، أو اضغط «🔄 تحديث»" }, style = MaterialTheme.typography.bodySmall,
                        color = when { msg.startsWith("✓") -> Good; msg.startsWith("✗") -> Bad; else -> MaterialTheme.colorScheme.onPrimaryContainer })
                }
            }
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { c ->
                WebView(c).apply {
                    holder[0] = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(RoznamaBridge(c.applicationContext), "AlmaBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, url: String?) { v.evaluateJavascript(SYNC_JS, null) }
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            val u = r.url
                            if (u.scheme == "http" || u.scheme == "https") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u)) }; return true }
                            return false
                        }
                    }
                    loadUrl("file:///android_asset/roznama/index.html")
                }
            })
        }
    }
}
