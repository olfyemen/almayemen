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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
@Serializable data class WeekDua(val dow: Int, val day: String, val text: String)
@Serializable data class SahifaBook(val week: List<WeekDua> = emptyList(), val famous: List<SahifaDua> = emptyList())
/** A supplication / litany from the Shadhili order or Ibn Arabi. */
@Serializable data class Ibtihal(val id: Int, val title: String, val author: String, val about: String = "", val text: String, val source: String = "")

object AdhkarData {
    @Volatile private var hisn: List<HisnChapter>? = null
    @Volatile private var sahifa: SahifaBook? = null
    @Volatile private var ibt: List<Ibtihal>? = null

    fun hisn(ctx: Context) = hisn ?: ctx.assets.open("adhkar/hisn.json").use {
        AppJson.decodeFromString(ListSerializer(HisnChapter.serializer()), it.readBytes().decodeToString())
    }.also { hisn = it }

    fun sahifa(ctx: Context) = sahifa ?: ctx.assets.open("adhkar/sahifa.json").use {
        AppJson.decodeFromString(SahifaBook.serializer(), it.readBytes().decodeToString())
    }.also { sahifa = it }

    /** Texts the user pasted from a trusted book (kept in prefs so the full backup includes them). */
    fun userIbtihalat(ctx: Context): List<Ibtihal> = runCatching {
        AppJson.decodeFromString(ListSerializer(Ibtihal.serializer()), ctx.getSharedPreferences("ibtihalat", 0).getString("list", "[]")!!)
    }.getOrDefault(emptyList())

    fun saveUserIbtihalat(ctx: Context, l: List<Ibtihal>) =
        ctx.getSharedPreferences("ibtihalat", 0).edit().putString("list", AppJson.encodeToString(ListSerializer(Ibtihal.serializer()), l)).apply()

    fun allIbtihalat(ctx: Context) = ibtihalat(ctx) + userIbtihalat(ctx)

    fun ibtihalat(ctx: Context) = ibt ?: runCatching { ctx.assets.open("adhkar/ibtihalat.json").use {
        AppJson.decodeFromString(ListSerializer(Ibtihal.serializer()), it.readBytes().decodeToString())
    } }.getOrDefault(emptyList()).also { ibt = it }

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
            TabRow(tab.coerceIn(0, 2), containerColor = MaterialTheme.colorScheme.background) {
                listOf("الصحيفة السجادية", "حصن المسلم", "أدعية وابتهالات").forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i; AdhkarData.setTab(ctx, i) }, text = { Text(t, maxLines = 1) })
                }
            }
            when (tab.coerceIn(0, 2)) {
                0 -> SahifaTab(nav)
                1 -> HisnIndex(nav)
                else -> IbtihalatTab(nav)
            }
        }
    }
}

@Composable
private fun SearchBox(q: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(q, onChange, placeholder = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
}

@Composable
fun SahifaTab(nav: NavHostController) {
    val ctx = LocalContext.current
    val book = remember { AdhkarData.sahifa(ctx) }
    val today = java.time.LocalDate.now().dayOfWeek.value
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        book.week.firstOrNull { it.dow == today }?.let { w ->
            item {
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().clickable { nav.navigate("adhkar/week/${w.dow}") }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("🌙 دعاء يوم ${w.day}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(w.text.take(110) + "…", style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        Text("اضغط للقراءة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item { Text("أدعية أيام الأسبوع", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
        val order = WeekOrder.mapNotNull { d -> book.week.firstOrNull { it.dow == d } }
        items(order, key = { "w" + it.dow }) { w ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { nav.navigate("adhkar/week/${w.dow}") }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("دعاء يوم ${w.day}", Modifier.weight(1f), fontWeight = if (w.dow == today) FontWeight.Bold else FontWeight.Normal)
                    if (w.dow == today) Text("اليوم", color = Good, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("الابتهالات المشهورة من الصحيفة", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
        items(book.famous, key = { "f" + it.n }) { d ->
            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { nav.navigate("adhkar/sahifa/${d.n}") }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Soft), contentAlignment = Alignment.Center) { Text("${d.n}", fontWeight = FontWeight.Bold, color = Accent) }
                    Spacer(Modifier.width(10.dp))
                    Text(d.title, Modifier.weight(1f))
                }
            }
        }
        item { Text("نص أدعية الصحيفة من ويكي مصدر (CC BY-SA)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun TextReader(nav: NavHostController, title: String, heading: String, text: String, footer: String = "") {
    val ctx = LocalContext.current
    var size by remember { mutableIntStateOf(AdhkarData.fontSize(ctx)) }
    ToolScaffold(title, { nav.popBackStack() }, actions = { FontBar(size) { size = it; AdhkarData.setFontSize(ctx, it) } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(18.dp)) {
            item { Text(heading, fontWeight = FontWeight.Bold, fontSize = (size + 2).sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            item { Spacer(Modifier.height(12.dp)) }
            items(text.split("\n").filter { it.isNotBlank() }) { p ->
                SelectionContainer { Text(p.trim(), fontSize = size.sp, lineHeight = (size * 1.9).sp, textAlign = TextAlign.Justify, modifier = Modifier.padding(bottom = 10.dp)) }
            }
            if (footer.isNotEmpty()) item { Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
fun WeekDuaReader(nav: NavHostController, dow: Int) {
    val ctx = LocalContext.current
    val w = remember(dow) { AdhkarData.sahifa(ctx).week.firstOrNull { it.dow == dow } } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    TextReader(nav, "دعاء يوم ${w.day}", "دعاء الإمام زين العابدين في يوم ${w.day}", w.text)
}

@Composable
fun IbtihalReader(nav: NavHostController, id: Int) {
    val ctx = LocalContext.current
    val b = remember(id) { AdhkarData.allIbtihalat(ctx).firstOrNull { it.id == id } } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    TextReader(nav, b.title, if (b.author.isNotBlank()) b.title + "\n" + b.author else b.title, b.text, b.source)
}

@Composable
private fun IbtihalatTab(nav: NavHostController) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val list = remember(version) { AdhkarData.allIbtihalat(ctx) }
    val userIds = remember(version) { AdhkarData.userIbtihalat(ctx).map { it.id }.toSet() }
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Ibtihal?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("أدعية وابتهالات الشيخ محيي الدين ابن عربي والطريقة الشاذلية", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("➕ إضافة دعاء أو حزب من كتابك") } }
        if (list.isEmpty()) item {
            EmptyState("📿", "لا توجد نصوص بعد", "لم أجد نصاً موثوقاً متاحاً لحزب البحر. انسخه من كتاب تثق به (مثل كتب أوراد الشاذلية) والصقه بزر الإضافة.")
        }
        list.groupBy { it.author.ifBlank { "أخرى" } }.forEach { (author, items) ->
            item(key = "h$author") { Text(author, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            items(items, key = { "i" + it.id }) { b ->
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { nav.navigate("adhkar/ibt/${b.id}") }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, fontWeight = FontWeight.Bold)
                            if (b.about.isNotEmpty()) Text(b.about, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        if (b.id in userIds) TextButton(onClick = { del = b }) { Text("حذف", color = Bad) }
                    }
                }
            }
        }
    }
    if (adding) {
        var title by remember { mutableStateOf("") }
        var author by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var source by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("إضافة دعاء") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(title, { title = it.take(80) }, label = { Text("الاسم (مثلاً: حزب البحر)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("الطريقة الشاذلية", "ابن عربي").forEach { a -> FilterChip(author == a, { author = a }, label = { Text(a) }) }
                    }
                    OutlinedTextField(text, { text = it }, label = { Text("النص") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
                    OutlinedTextField(source, { source = it.take(120) }, label = { Text("المصدر (اسم الكتاب)") }, singleLine = true)
                    Text("انسخ النص من مصدر تثق به وراجعه، فهو يُعرض كما تكتبه.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val l = AdhkarData.userIbtihalat(ctx)
                    val id = (list.maxOfOrNull { it.id } ?: 1000).coerceAtLeast(1000) + 1
                    AdhkarData.saveUserIbtihalat(ctx, l + Ibtihal(id, title.trim(), author, text = text.trim(), source = if (source.isNotBlank()) "المصدر: " + source.trim() else ""))
                    version++; adding = false
                }, enabled = title.isNotBlank() && text.isNotBlank()) { Text("حفظ") }
            }, dismissButton = { TextButton(onClick = { adding = false }) { Text("إلغاء") } })
    }
    del?.let { b ->
        ConfirmDialog("حذف", "حذف «${b.title}»؟", onDismiss = { del = null }) {
            AdhkarData.saveUserIbtihalat(ctx, AdhkarData.userIbtihalat(ctx).filter { it.id != b.id }); version++
        }
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
    val d = remember(n) { AdhkarData.sahifa(ctx).famous.firstOrNull { it.n == n } } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    TextReader(nav, "الدعاء ${d.n}", d.title, d.text)
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
