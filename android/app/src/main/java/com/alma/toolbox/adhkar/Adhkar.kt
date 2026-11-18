package com.alma.toolbox.adhkar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.alma.toolbox.core.AppJson
import com.alma.toolbox.ui.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalTime

@Serializable data class Dhikr(val t: String, val r: Int = 1)
@Serializable data class HisnChapter(val id: Int, val title: String, val items: List<Dhikr>)
@Serializable data class SahifaDua(val n: Int, val title: String, val text: String)

object AdhkarData {
    @Volatile private var hisn: List<HisnChapter>? = null
    @Volatile private var sahifa: List<SahifaDua>? = null

    fun hisn(ctx: Context) = hisn ?: ctx.assets.open("adhkar/hisn.json").use {
        AppJson.decodeFromString(ListSerializer(HisnChapter.serializer()), it.readBytes().decodeToString())
    }.also { hisn = it }

    fun sahifa(ctx: Context) = sahifa ?: ctx.assets.open("adhkar/sahifa.json").use {
        AppJson.decodeFromString(ListSerializer(SahifaDua.serializer()), it.readBytes().decodeToString())
    }.also { sahifa = it }

    /** Arabic search ignoring tashkeel and alef/yaa/taa-marbuta variants. */
    fun norm(s: String) = s.replace(Regex("[\\u064B-\\u0652\\u0670\\u0640]"), "")
        .replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي').replace('ة', 'ه')

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("adhkar", 0)
    fun fontSize(ctx: Context) = prefs(ctx).getInt("font", 22)
    fun setFontSize(ctx: Context, v: Int) = prefs(ctx).edit().putInt("font", v).apply()
    fun tab(ctx: Context) = prefs(ctx).getInt("tab", 0)
    fun setTab(ctx: Context, v: Int) = prefs(ctx).edit().putInt("tab", v).apply()
}

/** Chapters shown as quick buttons (ids in hisnmuslim.com numbering). */
private val Quick = listOf(27 to "☀️ الصباح والمساء", 28 to "🌙 النوم", 1 to "⏰ الاستيقاظ", 25 to "🕌 بعد السلام من الصلاة")

@Composable
fun AdhkarScreen(nav: NavHostController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(AdhkarData.tab(ctx)) }
    ToolScaffold("الأذكار والأدعية 📿", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("الصحيفة السجادية", "فهرس الصحيفة", "حصن المسلم").forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i; AdhkarData.setTab(ctx, i) }, text = { Text(t, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> SahifaDaily(nav)
                1 -> SahifaIndex(nav)
                else -> HisnIndex(nav)
            }
        }
    }
}

/** The user's daily Sahifa page (opens on today's dua) shown offline in a WebView. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SahifaDaily(nav: NavHostController) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.scheme == "alma") {
                        val n = u.lastPathSegment?.toIntOrNull() ?: 0
                        nav.navigate(if (n in 1..54) "adhkar/sahifa/$n" else "adhkar/sahifaList")
                        return true
                    }
                    if (u.scheme == "http" || u.scheme == "https") { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, u)) }; return true }
                    return false
                }
            }
            loadUrl("file:///android_asset/sahifa/index.html")
        }
    })
}

@Composable
private fun SearchBox(q: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(q, onChange, placeholder = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
}

@Composable
fun SahifaIndex(nav: NavHostController) {
    val ctx = LocalContext.current
    val all = remember { AdhkarData.sahifa(ctx) }
    var q by remember { mutableStateOf("") }
    val nq = AdhkarData.norm(q.trim())
    val list = if (nq.isEmpty()) all else all.filter { AdhkarData.norm(it.title).contains(nq) || AdhkarData.norm(it.text).contains(nq) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SearchBox(q, "بحث في عناوين ونصوص الأدعية") { q = it } }
        items(list, key = { it.n }) { d ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { nav.navigate("adhkar/sahifa/${d.n}") }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Soft), contentAlignment = Alignment.Center) { Text("${d.n}", fontWeight = FontWeight.Bold, color = Accent) }
                    Spacer(Modifier.width(10.dp))
                    Text(d.title, Modifier.weight(1f))
                }
            }
        }
        item { Text("النص من ويكي مصدر (CC BY-SA)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun FontBar(size: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange((size - 2).coerceAtLeast(14)) }) { Text("أ−", fontSize = 16.sp) }
        TextButton(onClick = { onChange((size + 2).coerceAtMost(40)) }) { Text("أ+", fontSize = 20.sp) }
    }
}

@Composable
fun SahifaReader(nav: NavHostController, n: Int) {
    val ctx = LocalContext.current
    val d = remember(n) { AdhkarData.sahifa(ctx).firstOrNull { it.n == n } } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    var size by remember { mutableIntStateOf(AdhkarData.fontSize(ctx)) }
    ToolScaffold("الدعاء ${d.n}", { nav.popBackStack() }, actions = { FontBar(size) { size = it; AdhkarData.setFontSize(ctx, it) } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
            item { Text(d.title, fontWeight = FontWeight.Bold, fontSize = (size + 2).sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            item { Spacer(Modifier.height(12.dp)) }
            items(d.text.split("\n").filter { it.isNotBlank() }) { p ->
                SelectionContainer { Text(p.trim(), fontSize = size.sp, lineHeight = (size * 1.9).sp, textAlign = TextAlign.Justify, modifier = Modifier.padding(bottom = 10.dp)) }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (n > 1) TextButton(onClick = { nav.navigate("adhkar/sahifa/${n - 1}") { popUpTo("adhkar/sahifa/$n") { inclusive = true } } }) { Text("▶ السابق") } else Spacer(Modifier)
                    if (n < 54) TextButton(onClick = { nav.navigate("adhkar/sahifa/${n + 1}") { popUpTo("adhkar/sahifa/$n") { inclusive = true } } }) { Text("التالي ◀") }
                }
            }
        }
    }
}

@Composable
private fun HisnIndex(nav: NavHostController) {
    val ctx = LocalContext.current
    val all = remember { AdhkarData.hisn(ctx) }
    var q by remember { mutableStateOf("") }
    val nq = AdhkarData.norm(q.trim())
    val list = if (nq.isEmpty()) all else all.filter { c -> AdhkarData.norm(c.title).contains(nq) || c.items.any { AdhkarData.norm(it.t).contains(nq) } }
    val hour = LocalTime.now().hour
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Button(onClick = { nav.navigate("adhkar/hisn/27") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (hour in 3..13) "☀️ ابدأ أذكار الصباح" else "🌙 ابدأ أذكار المساء", fontSize = 18.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Quick.drop(1).forEach { (id, l) -> FilledTonalButton(onClick = { nav.navigate("adhkar/hisn/$id") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(l, fontSize = 13.sp, maxLines = 1) } }
            }
        }
        item { SearchBox(q, "بحث في ${all.size} باباً") { q = it } }
        items(list, key = { it.id }) { c ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { nav.navigate("adhkar/hisn/${c.id}") }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, Modifier.weight(1f))
                    Text("${c.items.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        item { Text("حصن المسلم للشيخ سعيد بن علي بن وهف القحطاني · النص من hisnmuslim.com", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
}

@Composable
fun HisnReader(nav: NavHostController, id: Int) {
    val ctx = LocalContext.current
    val c = remember(id) { AdhkarData.hisn(ctx).firstOrNull { it.id == id } } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    var size by remember { mutableIntStateOf(AdhkarData.fontSize(ctx)) }
    val left = remember(id) { mutableStateListOf(*c.items.map { it.r.coerceAtLeast(1) }.toTypedArray()) }
    val done = left.count { it == 0 }
    ToolScaffold(c.title, { nav.popBackStack() }, actions = { FontBar(size) { size = it; AdhkarData.setFontSize(ctx, it) } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                LinearProgressIndicator(progress = { done.toFloat() / c.items.size }, modifier = Modifier.fillMaxWidth())
                Text("أنجزت $done من ${c.items.size}" + if (done == c.items.size) " · تقبّل الله ✓" else "", style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(c.items) { i, d ->
                val finished = left[i] == 0
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (finished) Good.copy(alpha = .10f) else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        SelectionContainer { Text(d.t, fontSize = size.sp, lineHeight = (size * 1.8).sp, textAlign = TextAlign.Justify) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (d.r > 1) Text("التكرار: ${d.r}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                            Button(onClick = { if (left[i] > 0) left[i] = left[i] - 1 else left[i] = d.r.coerceAtLeast(1) },
                                colors = ButtonDefaults.buttonColors(containerColor = if (finished) Good else MaterialTheme.colorScheme.primary)) {
                                Text(if (finished) "✓ تم" else if (d.r > 1) "${left[i]}" else "قرأته", fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
