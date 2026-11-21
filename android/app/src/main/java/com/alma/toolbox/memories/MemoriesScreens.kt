package com.alma.toolbox.memories

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.alma.toolbox.core.Stores
import com.alma.toolbox.core.newId
import com.alma.toolbox.ledger.LedgerLock
import com.alma.toolbox.ledger.askBiometric
import com.alma.toolbox.ledger.bioAvailable
import com.alma.toolbox.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private val MonthNames = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
private fun monthLabel(d: LocalDate) = "${MonthNames[d.monthValue - 1]} ${d.year}"
private fun dayLabel(iso: String) = runCatching { val d = LocalDate.parse(iso); "${DayNames[d.dayOfWeek.value]} ${d.dayOfMonth} ${MonthNames[d.monthValue - 1]} ${d.year}" }.getOrDefault(iso)

@Composable
fun Thumb(name: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, name) { value = withContext(Dispatchers.IO) { MemFiles.thumb(ctx, name, 600) } }
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Text("🖼️")
    }
}

// ---------------- lock ----------------
@Composable
private fun MemGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val d by Stores.memories.state.collectAsStateWithLifecycle()
    if (d.pinHash.isEmpty() || MemLock.unlocked) { content(); return }
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val useBio = d.lockBio && bioAvailable(ctx)
    LaunchedEffect(Unit) { if (useBio) askBiometric(ctx, "فتح الذكريات") { MemLock.unlocked = true } }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🔐", fontSize = 56.sp)
        Text("ذكرياتي", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (error) "رمز خاطئ" else "أدخل الرمز", color = if (error) Bad else MaterialTheme.colorScheme.outline)
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(d.pinLength) { i -> Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)) }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (useBio) "👆" else "", "0", "⌫")).forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(6.dp)) {
                r.forEach { k ->
                    Box(Modifier.size(72.dp).clip(CircleShape).background(if (k.isEmpty()) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = k.isNotEmpty()) {
                            when (k) {
                                "👆" -> askBiometric(ctx, "فتح الذكريات") { MemLock.unlocked = true }
                                "⌫" -> pin = pin.dropLast(1)
                                else -> {
                                    error = false; pin += k
                                    if (pin.length == d.pinLength) { if (LedgerLock.hash(pin) == d.pinHash) MemLock.unlocked = true else { error = true; pin = "" } }
                                }
                            }
                        }, contentAlignment = Alignment.Center) { Text(k, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        TextButton(onClick = onBack) { Text("رجوع") }
    }
}

@Composable
private fun MemLockDialog(d: MemoriesData, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf("") }; var p1 by remember { mutableStateOf("") }; var p2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val has = d.pinHash.isNotEmpty()
    val num = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
    val pw = androidx.compose.ui.text.input.PasswordVisualTransformation()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (has) "رمز الذكريات" else "قفل الذكريات برمز") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (has) OutlinedTextField(cur, { cur = it.filter(Char::isDigit).take(8) }, label = { Text("الرمز الحالي") }, singleLine = true, keyboardOptions = num, visualTransformation = pw)
                OutlinedTextField(p1, { p1 = it.filter(Char::isDigit).take(8) }, label = { Text("رمز جديد (4–8 أرقام)") }, singleLine = true, keyboardOptions = num, visualTransformation = pw)
                OutlinedTextField(p2, { p2 = it.filter(Char::isDigit).take(8) }, label = { Text("تأكيد الرمز") }, singleLine = true, keyboardOptions = num, visualTransformation = pw)
                if (has && bioAvailable(ctx)) SwitchRow("الفتح بالبصمة أيضاً", d.lockBio) { v -> Stores.memories.update { it.copy(lockBio = v) } }
                if (msg.isNotEmpty()) Text(msg, color = Bad)
                Text("⚠ إن نسيت الرمز لن تفتح الذكريات إلا باسترجاع نسخة احتياطية.", style = MaterialTheme.typography.bodySmall)
                if (has) TextButton(onClick = { if (LedgerLock.hash(cur) != d.pinHash) msg = "الرمز الحالي خاطئ" else { Stores.memories.update { it.copy(pinHash = "") }; onDismiss() } }) { Text("إزالة القفل", color = Bad) }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    has && LedgerLock.hash(cur) != d.pinHash -> msg = "الرمز الحالي خاطئ"
                    p1.length < 4 -> msg = "4 أرقام على الأقل"
                    p1 != p2 -> msg = "الرمزان غير متطابقين"
                    else -> { Stores.memories.update { it.copy(pinHash = LedgerLock.hash(p1), pinLength = p1.length) }; MemLock.unlocked = true; onDismiss() }
                }
            }) { Text("حفظ") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

// ---------------- home ----------------
@Composable
fun MemoriesScreen(nav: NavHostController, onBack: () -> Unit) = MemGate(onBack) { MemoriesHome(nav, onBack) }

@Composable
private fun MemoryCard(m: Memory, onClick: () -> Unit) {
    val locked = m.locked()
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (locked) Warn.copy(alpha = .12f) else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            if (!locked && m.photos.isNotEmpty()) { Thumb(m.photos.first(), 72.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.mood.isNotEmpty()) Text(m.mood + " ", fontSize = 18.sp)
                    Text(if (locked) "🎁 كبسولة زمنية" else m.title.ifBlank { m.text.lineSequence().firstOrNull()?.take(40) ?: "ذكرى" }, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                    if (m.favorite) Text("⭐")
                }
                Text(dayLabel(m.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (locked) Text("تُفتح في ${dayLabel(m.openOn)} · بعد ${ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(m.openOn))} يوم", style = MaterialTheme.typography.bodySmall)
                else Text(m.text, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                if (!locked && (m.tags.isNotEmpty() || m.photos.size > 1)) Text((m.tags.joinToString(" ") { "#$it" } + if (m.photos.size > 1) "  🖼️ ${m.photos.size}" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MemoriesHome(nav: NavHostController, onBack: () -> Unit) {
    val d by Stores.memories.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    var lockDlg by remember { mutableStateOf(false) }
    var ym by remember { mutableStateOf(YearMonth.now()) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val sorted = d.items.sortedWith(compareByDescending<Memory> { it.date }.thenByDescending { it.created })
    val visible = sorted.filter { m ->
        (!favOnly || m.favorite) && (tag == null || tag in m.tags) &&
            (q.isBlank() || (!m.locked() && (m.title.contains(q.trim(), true) || m.text.contains(q.trim(), true) || m.tags.any { it.contains(q.trim(), true) })))
    }
    val allTags = d.items.filter { !it.locked() }.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(15)
    val readyCapsules = d.items.filter { it.openOn.isNotEmpty() && !it.locked() && it.openOn >= today.minusDays(7).toString() }

    ToolScaffold("الذكريات 📔", onBack, actions = {
        IconButton(onClick = { lockDlg = true }) { Text(if (d.pinHash.isEmpty()) "🔓" else "🔐", fontSize = 20.sp) }
        if (d.pinHash.isNotEmpty()) TextButton(onClick = { MemLock.lock() }) { Text("قفل") }
    }, fab = {
        ExtendedFloatingActionButton(onClick = { nav.navigate("memories/edit/0") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("ذكرى جديدة") })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("الخط الزمني", "التقويم", "في مثل هذا اليوم").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t, maxLines = 1) }) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab == 0) {
                    item {
                        Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth().clickable { nav.navigate("memories/edit/0?prompt=1") }) {
                            Column(Modifier.padding(14.dp)) {
                                Text("✍️ سؤال اليوم", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(promptOfDay(), fontSize = 17.sp)
                                Text("اضغط لتكتب إجابتك", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    readyCapsules.forEach { c ->
                        item(key = "cap" + c.id) {
                            Banner("🎁 فُتحت كبسولتك التي كتبتها في ${dayLabel(c.date)}", "افتحها", Good) { nav.navigate("memories/view/${c.id}") }
                        }
                    }
                    item {
                        Text("${d.items.count { !it.locked() }} ذكرى · ${d.items.sumOf { it.photos.size }} صورة" +
                            d.items.count { it.locked() }.let { if (it > 0) " · $it كبسولة مقفلة" else "" }, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(q, { q = it }, placeholder = { Text("بحث في الذكريات") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(favOnly, { favOnly = !favOnly }, label = { Text("⭐ المفضلة") })
                            allTags.forEach { t -> FilterChip(tag == t, { tag = if (tag == t) null else t }, label = { Text("#$t") }) }
                        }
                    }
                    if (visible.isEmpty()) item { EmptyState("📔", if (d.items.isEmpty()) "ابدأ أول ذكرى" else "لا نتائج", "اكتب لحظاتك وأرفق صورها، ويمكنك قفلها برمز") }
                    var lastMonth = ""
                    visible.forEach { m ->
                        val ml = runCatching { monthLabel(LocalDate.parse(m.date)) }.getOrDefault("")
                        if (ml != lastMonth) { lastMonth = ml; item(key = "h$ml") { Text(ml, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) } }
                        item(key = m.id) { MemoryCard(m) { nav.navigate("memories/view/${m.id}") } }
                    }
                } else if (tab == 1) {
                    item {
                        val byDay = d.items.groupBy { it.date }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { ym = ym.minusMonths(1) }) { Text("▶") }
                            Text("${MonthNames[ym.monthValue - 1]} ${ym.year}", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { ym = ym.plusMonths(1) }) { Text("◀") }
                        }
                        Row { WeekOrder.forEach { Text(DayShort[it]!!, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) } }
                        val first = ym.atDay(1)
                        val offset = WeekOrder.indexOf(first.dayOfWeek.value)
                        val cells = List(offset) { null } + (1..ym.lengthOfMonth()).map { ym.atDay(it) }
                        cells.chunked(7).forEach { week ->
                            Row {
                                week.forEach { date ->
                                    val has = date != null && byDay[date.toString()] != null
                                    val mood = date?.let { byDay[it.toString()]?.firstOrNull { m -> m.mood.isNotEmpty() && !m.locked() }?.mood }
                                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(10.dp))
                                        .background(when { date == day -> MaterialTheme.colorScheme.primary; has -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surface })
                                        .clickable(enabled = date != null) { day = date }, contentAlignment = Alignment.Center) {
                                        if (date != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("${date.dayOfMonth}", fontWeight = if (date == today) FontWeight.ExtraBold else FontWeight.Normal,
                                                color = if (date == day) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                            if (mood != null) Text(mood, fontSize = 11.sp)
                                        }
                                    }
                                }
                                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    val list = day?.let { dd -> sorted.filter { it.date == dd.toString() } } ?: emptyList()
                    if (day != null) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dayLabel(day.toString()), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            TextButton(onClick = { nav.navigate("memories/edit/0?date=$day") }) { Text("+ إضافة لهذا اليوم") }
                        }
                    }
                    items(list, key = { it.id }) { m -> MemoryCard(m) { nav.navigate("memories/view/${m.id}") } }
                } else {
                    val md = today.toString().substring(5)
                    val onThisDay = sorted.filter { it.date.length == 10 && it.date.substring(5) == md && it.date.substring(0, 4) != today.year.toString() && !it.locked() }
                    item { Text("ذكرياتك في ${today.dayOfMonth} ${MonthNames[today.monthValue - 1]} من السنوات السابقة", style = MaterialTheme.typography.bodySmall) }
                    if (onThisDay.isEmpty()) item { EmptyState("🗓️", "لا ذكريات في مثل هذا اليوم بعد", "ما تكتبه اليوم سيظهر لك هنا في السنوات القادمة إن شاء الله") }
                    items(onThisDay, key = { it.id }) { m ->
                        Column {
                            Text("قبل ${today.year - m.date.substring(0, 4).toInt()} سنة", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            MemoryCard(m) { nav.navigate("memories/view/${m.id}") }
                        }
                    }
                }
            }
        }
    }
    if (lockDlg) MemLockDialog(d) { lockDlg = false }
}

// ---------------- view ----------------
@Composable
fun MemoryViewScreen(nav: NavHostController, id: Long) = MemGate({ nav.popBackStack() }) {
    val ctx = LocalContext.current
    val d by Stores.memories.state.collectAsStateWithLifecycle()
    val m = d.items.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return@MemGate }
    var del by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf<String?>(null) }
    ToolScaffold(if (m.locked()) "🎁 كبسولة زمنية" else m.title.ifBlank { "ذكرى" }, { nav.popBackStack() }, actions = {
        if (!m.locked()) {
            IconButton(onClick = { Stores.memories.update { x -> x.copy(items = x.items.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }) } }) { Text(if (m.favorite) "⭐" else "☆", fontSize = 20.sp) }
            TextButton(onClick = { nav.navigate("memories/edit/$id") }) { Text("تعديل") }
        }
        TextButton(onClick = { del = true }) { Text("حذف", color = Bad) }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (m.locked()) {
                EmptyState("🎁", "هذه الكبسولة مقفلة", "كتبتها في ${dayLabel(m.date)}، وتُفتح في ${dayLabel(m.openOn)}.\nبقي ${ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(m.openOn))} يوم.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.mood.isNotEmpty()) Text(m.mood, fontSize = 34.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(dayLabel(m.date), fontWeight = FontWeight.Bold)
                    if (m.openOn.isNotEmpty()) Text("🎁 كبسولة فُتحت في ${dayLabel(m.openOn)}", style = MaterialTheme.typography.bodySmall, color = Good)
                }
            }
            if (m.prompt.isNotEmpty()) Text("✍️ ${m.prompt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (m.photos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.photos.forEach { p -> Thumb(p, if (m.photos.size == 1) 300.dp else 180.dp, Modifier.clickable { full = p }) }
            }
            SelectionContainer { Text(m.text, fontSize = 18.sp, lineHeight = 30.sp) }
            if (m.tags.isNotEmpty()) Text(m.tags.joinToString("  ") { "#$it" }, color = MaterialTheme.colorScheme.primary)
            OutlinedButton(onClick = {
                val t = (if (m.title.isNotBlank()) m.title + "\n" else "") + dayLabel(m.date) + "\n\n" + m.text
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "مشاركة الذكرى"))
            }) { Text("مشاركة النص") }
        }
    }
    full?.let { p ->
        androidx.compose.ui.window.Dialog({ full = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            val bmp by produceState<android.graphics.Bitmap?>(null, p) { value = withContext(Dispatchers.IO) { MemFiles.thumb(ctx, p, 2000) } }
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).clickable { full = null }, contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
            }
        }
    }
    if (del) ConfirmDialog("حذف الذكرى", "حذف هذه الذكرى وصورها نهائياً؟", onDismiss = { del = false }) {
        val after = Stores.memories.update { x -> x.copy(items = x.items.filter { it.id != id }) }
        MemFiles.cleanup(ctx, after); nav.popBackStack()
    }
}

// ---------------- edit ----------------
@Composable
fun MemoryEditScreen(nav: NavHostController, id: Long, withPrompt: Boolean, date: String?) = MemGate({ nav.popBackStack() }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember { Stores.memories.value.items.firstOrNull { it.id == id } }
    val base = remember { existing ?: Memory(newId(), date = date ?: LocalDate.now().toString(), prompt = if (withPrompt) promptOfDay() else "") }
    var m by remember { mutableStateOf(base) }
    var tagText by remember { mutableStateOf(base.tags.joinToString("، ")) }
    var capsule by remember { mutableStateOf(base.openOn.isNotEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) { busy = true; scope.launch { val names = withContext(Dispatchers.IO) { uris.mapNotNull { MemFiles.importPhoto(ctx, it) } }; m = m.copy(photos = m.photos + names); busy = false } }
    }
    fun save() {
        val tags = tagText.split(',', '،', '#', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val final = m.copy(tags = tags, openOn = if (capsule) m.openOn.ifEmpty { LocalDate.now().plusYears(1).toString() } else "", updated = System.currentTimeMillis())
        val after = Stores.memories.update { x -> x.copy(items = x.items.filter { it.id != final.id } + final) }
        MemFiles.cleanup(ctx, after)
        nav.popBackStack()
    }
    ToolScaffold(if (existing == null) "ذكرى جديدة" else "تعديل الذكرى", { nav.popBackStack() }, actions = {
        TextButton(onClick = { save() }, enabled = !busy && (m.text.isNotBlank() || m.photos.isNotEmpty())) { Text("حفظ", fontWeight = FontWeight.Bold) }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (m.prompt.isNotEmpty()) Text("✍️ ${m.prompt}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(m.title, { m = m.copy(title = it.take(100)) }, label = { Text("العنوان (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ValueRow("التاريخ", dayLabel(m.date)) { pickDate(ctx, runCatching { LocalDate.parse(m.date) }.getOrDefault(LocalDate.now())) { m = m.copy(date = it.toString()) } }
            Text("كيف كان شعورك؟", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Moods.forEach { e ->
                    Box(Modifier.size(42.dp).clip(CircleShape).background(if (m.mood == e) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { m = m.copy(mood = if (m.mood == e) "" else e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) }
                }
            }
            OutlinedTextField(m.text, { m = m.copy(text = it) }, label = { Text("اكتب ذكرياتك…") }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
            OutlinedTextField(tagText, { tagText = it }, label = { Text("وسوم (مثلاً: عائلة، سفر، رمضان)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) { Text(if (busy) "جارٍ الإضافة…" else "🖼️ إضافة صور") }
                Spacer(Modifier.width(8.dp)); Text("${m.photos.size} صورة", style = MaterialTheme.typography.bodySmall)
            }
            if (m.photos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.photos.forEach { p ->
                    Box {
                        Thumb(p, 96.dp)
                        Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(26.dp).clip(CircleShape).background(Bad).clickable { m = m.copy(photos = m.photos - p) }, contentAlignment = Alignment.Center) {
                            Text("✕", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            SwitchRow("⭐ من المفضلة", m.favorite) { m = m.copy(favorite = it) }
            SectionCard(title = "🎁 كبسولة زمنية (اختياري)") {
                SwitchRow("اجعلها رسالة لا تُفتح إلا في تاريخ لاحق", capsule) { capsule = it; if (it && m.openOn.isEmpty()) m = m.copy(openOn = LocalDate.now().plusYears(1).toString()) }
                if (capsule) {
                    ValueRow("تُفتح في", dayLabel(m.openOn.ifEmpty { LocalDate.now().plusYears(1).toString() })) {
                        pickDate(ctx, runCatching { LocalDate.parse(m.openOn) }.getOrDefault(LocalDate.now().plusYears(1))) { d -> if (d.isAfter(LocalDate.now())) m = m.copy(openOn = d.toString()) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("بعد شهر" to 1L, "بعد 6 أشهر" to 6L, "بعد سنة" to 12L, "بعد 5 سنوات" to 60L).forEach { (l, mo) ->
                            AssistChip(onClick = { m = m.copy(openOn = LocalDate.now().plusMonths(mo).toString()) }, label = { Text(l) })
                        }
                    }
                    Text("لن تظهر كلماتها ولا صورها حتى ذلك اليوم.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
