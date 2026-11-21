package com.alma.toolbox.roznama

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.alma.toolbox.ui.ToolScaffold

/**
 * The daily Hijri «roznama» page (built earlier as a standalone HTML file), shown offline.
 * Its diary/settings live in the WebView's localStorage under "rz-*" keys; a small bridge
 * mirrors them into SharedPreferences("roznama") so the full backup includes them.
 */
class RoznamaBridge(private val ctx: Context) {
    private val prefs get() = ctx.getSharedPreferences("roznama", 0)
    @JavascriptInterface fun save(json: String) { prefs.edit().putString("ls", json).apply() }
    @JavascriptInterface fun load(): String = prefs.getString("ls", "") ?: ""
    /** true once after a backup was restored: the saved copy must replace what the page has. */
    @JavascriptInterface fun takeRestore(): Boolean = prefs.getBoolean("restore", false).also { if (it) prefs.edit().putBoolean("restore", false).apply() }
}

private const val SYNC_JS = """
(function(){
  try {
    var hasLocal = false;
    for (var i = 0; i < localStorage.length; i++) if (localStorage.key(i).indexOf('rz-') === 0) { hasLocal = true; break; }
    if (AlmaBridge.takeRestore()) {
      for (var j = localStorage.length - 1; j >= 0; j--) { var kk = localStorage.key(j); if (kk.indexOf('rz-') === 0) localStorage.removeItem(kk); }
      hasLocal = false; sessionStorage.removeItem('rz-restored');
    }
    if (!hasLocal && !sessionStorage.getItem('rz-restored')) {
      var saved = AlmaBridge.load();
      if (saved) {
        var o = JSON.parse(saved), n = 0;
        for (var k in o) { localStorage.setItem(k, o[k]); n++; }
        sessionStorage.setItem('rz-restored', '1');
        if (n) { location.reload(); return; }
      }
    }
    var last = '';
    function push(){
      var o = {};
      for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); if (k.indexOf('rz-') === 0) o[k] = localStorage.getItem(k); }
      var s = JSON.stringify(o);
      if (s !== last) { last = s; AlmaBridge.save(s); }
    }
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
    DisposableEffect(Unit) { onDispose { holder[0]?.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null) } }
    ToolScaffold("الروزنامة اليومية 📅", onBack) { pad ->
        AndroidView(modifier = Modifier.padding(pad).fillMaxSize(), factory = { c ->
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
