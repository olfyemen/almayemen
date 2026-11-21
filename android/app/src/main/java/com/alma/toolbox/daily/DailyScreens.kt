package com.alma.toolbox.daily

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alma.toolbox.core.Stores
import com.alma.toolbox.core.newId
import com.alma.toolbox.ledger.Ledger
import com.alma.toolbox.reminder.Reminder
import com.alma.toolbox.reminder.ReminderScheduler
import com.alma.toolbox.reminder.fmtDateTime
import com.alma.toolbox.tools.HijriMonths
import com.alma.toolbox.tools.Hijri
import com.alma.toolbox.transfer.Downloads
import com.alma.toolbox.ui.*
import kotlinx.coroutines.delay
import java.io.File
import java.time.*
import java.util.Locale

// =================== Zakat ===================
@Composable
fun ZakatScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("zakat", 0) }
    val keys = listOf("cash", "g24", "g21", "g18", "silver", "trade", "recv", "debts", "gprice", "sprice")
    val v = remember { mutableStateMapOf<String, String>().apply { keys.forEach { put(it, sp.getString(it, "") ?: "") } } }
    var bySilver by remember { mutableStateOf(sp.getBoolean("bySilver", false)) }
    var cur by remember { mutableStateOf(sp.getString("cur", "ريال") ?: "ريال") }
    fun n(k: String) = Ledger.parseAmount(v[k] ?: "") ?: 0.0
    LaunchedEffect(v.toMap(), bySilver, cur) { sp.edit().apply { keys.forEach { putString(it, v[it]) }; putBoolean("bySilver", bySilver); putString("cur", cur) }.apply() }
    val r = Zakat.compute(n("cash"), n("g24"), n("g21"), n("g18"), n("silver"), n("trade"), n("recv"), n("debts"), n("gprice"), n("sprice"), bySilver)

    @Composable fun field(k: String, label: String, suffix: String = cur) =
        OutlinedTextField(v[k] ?: "", { v[k] = it }, label = { Text(label) }, suffix = { Text(suffix) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())

    ToolScaffold("حاسبة الزكاة 🧮", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (r.due) Good.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (r.nisab <= 0) "أدخل سعر جرام ${if (bySilver) "الفضة" else "الذهب"} لحساب النصاب" else if (r.due) "تجب الزكاة" else "لم يبلغ المال النصاب", fontWeight = FontWeight.Bold)
                    Text("${fmtNum(Math.round(r.zakat * 100) / 100.0)} $cur", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = if (r.due) Good else MaterialTheme.colorScheme.outline)
                    Text("المال الزكوي: ${fmtNum(Math.round(r.total).toDouble())} · النصاب: ${fmtNum(Math.round(r.nisab).toDouble())}", style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionCard(title = "الأسعار والنصاب") {
                OutlinedTextField(cur, { cur = it.take(15) }, label = { Text("العملة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                field("gprice", "سعر جرام الذهب عيار 24")
                field("sprice", "سعر جرام الفضة")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!bySilver, { bySilver = false }, label = { Text("نصاب الذهب 85 جم") })
                    FilterChip(bySilver, { bySilver = true }, label = { Text("نصاب الفضة 595 جم") })
                }
            }
            SectionCard(title = "الأموال (مرّ عليها حول هجري)") {
                field("cash", "النقد والأرصدة")
                field("g24", "ذهب عيار 24", "جم"); field("g21", "ذهب عيار 21", "جم"); field("g18", "ذهب عيار 18", "جم")
                field("silver", "فضة", "جم")
                field("trade", "عروض التجارة (قيمة البضاعة)")
                field("recv", "ديون لك مرجوّة السداد")
                field("debts", "ديون عليك حالّة (تُطرح)")
                TextButton(onClick = { keys.filter { it != "gprice" && it != "sprice" }.forEach { v[it] = "" } }) { Text("مسح المبالغ") }
            }
            Text("الحساب تقريبي للمساعدة: 2.5% من المال إذا بلغ النصاب وحال عليه الحول. يقبل الجمع مثل 500+300. لاختلاف المذاهب في مسائل كالحُلي المستعمل يُرجع لأهل العلم.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// =================== Notes & tasks ===================
@Composable
fun NotesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d by Stores.notes.state.collectAsStateWithLifecycle()
    var filter by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Note?>(null) }
    var q by remember { mutableStateOf("") }
    fun save(n: Note) {
        Stores.notes.update { x -> x.copy(notes = x.notes.filter { it.id != n.id } + n) }
        if (n.remindAt > System.currentTimeMillis() && !n.done) ReminderScheduler.save(ctx, Reminder(n.id, title = "📝 " + n.text.take(60), startAt = n.remindAt))
        else ReminderScheduler.delete(ctx, n.id)
    }
    val list = d.notes.filter { (filter == 0 || (filter == 1) == !it.task) && (q.isBlank() || it.text.contains(q.trim(), true)) }
        .sortedWith(compareBy({ !it.pinned }, { it.done }, { -it.created }))
    ToolScaffold("الملاحظات والمهام 📝", onBack, fab = {
        ExtendedFloatingActionButton(onClick = { edit = Note(newId(), "", task = filter == 2) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("جديد") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("الكل", "ملاحظات", "مهام").forEachIndexed { i, l -> FilterChip(filter == i, { filter = i }, label = { Text(l) }) }
                }
                OutlinedTextField(q, { q = it }, placeholder = { Text("بحث") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(16.dp))
            }
            if (list.isEmpty()) item { EmptyState("📝", "لا توجد ملاحظات", "اكتب فكرة أو مهمة، ويمكن إضافة تذكير لها") }
            items(list, key = { it.id }) { n ->
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { edit = n }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        if (n.task) Checkbox(n.done, { save(n.copy(done = it)) })
                        Column(Modifier.weight(1f)) {
                            Text(n.text, maxLines = 6, textDecoration = if (n.done) TextDecoration.LineThrough else null,
                                color = if (n.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                            if (n.remindAt > 0) Text("⏰ " + fmtDateTime(n.remindAt), style = MaterialTheme.typography.bodySmall,
                                color = if (n.remindAt > System.currentTimeMillis()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        }
                        if (n.pinned) Text("📌")
                    }
                }
            }
        }
    }
    edit?.let { e ->
        var text by remember(e.id) { mutableStateOf(e.text) }
        var task by remember(e.id) { mutableStateOf(e.task) }
        var pinned by remember(e.id) { mutableStateOf(e.pinned) }
        var at by remember(e.id) { mutableLongStateOf(e.remindAt) }
        AlertDialog(onDismissRequest = { edit = null },
            title = { Text(if (e.text.isEmpty()) "جديد" else "تعديل") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = { Text("اكتب هنا…") })
                    SwitchRow("مهمة (مع خانة إنجاز)", task) { task = it }
                    SwitchRow("تثبيت في الأعلى 📌", pinned) { pinned = it }
                    val t = if (at > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()) else LocalDateTime.now().plusHours(1).withMinute(0)
                    ValueRow("تذكير", if (at > 0) fmtDateTime(at) else "بدون") {
                        pickDate(ctx, t.toLocalDate()) { day -> pickTime(ctx, t.hour * 60 + t.minute) { m -> at = day.atTime(m / 60, m % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } }
                    }
                    if (at > 0) TextButton(onClick = { at = 0 }) { Text("إلغاء التذكير") }
                    if (e.text.isNotEmpty()) TextButton(onClick = {
                        Stores.notes.update { x -> x.copy(notes = x.notes.filter { it.id != e.id }) }; ReminderScheduler.delete(ctx, e.id); edit = null
                    }) { Text("حذف", color = Bad) }
                }
            },
            confirmButton = { Button(onClick = { save(e.copy(text = text.trim(), task = task, pinned = pinned, remindAt = at)); edit = null }, enabled = text.isNotBlank()) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("إلغاء") } })
    }
}

// =================== Occasions ===================
private val OccEmojis = listOf("🎂", "💍", "🎓", "🕋", "🌙", "👶", "🕯️", "⭐", "📅")

@Composable
fun OccasionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d by Stores.occasions.state.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<Occasion?>(null) }
    fun persist(f: (OccasionsData) -> OccasionsData) { Stores.occasions.update(f); Occasions.schedule(ctx) }
    val sorted = d.items.sortedBy { Occasions.daysLeft(it) ?: Long.MAX_VALUE }
    ToolScaffold("المناسبات 🎂", onBack, fab = {
        ExtendedFloatingActionButton(onClick = { edit = Occasion(newId(), "", date = LocalDate.now().toString()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("مناسبة") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionCard { SwitchRow("تنبيه بالمناسبات (الساعة 9 صباحاً)", d.notify) { v -> persist { it.copy(notify = v) } } } }
            if (sorted.isEmpty()) item { EmptyState("🎂", "لا توجد مناسبات", "أعياد ميلاد، ذكرى زواج، مواعيد سنوية… بالتاريخ الميلادي أو الهجري") }
            items(sorted, key = { it.id }) { o ->
                val left = Occasions.daysLeft(o); val next = Occasions.next(o); val yrs = Occasions.years(o)
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (left == 0L) Good.copy(alpha = .15f) else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { edit = o }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(o.emoji, fontSize = 30.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.name, fontWeight = FontWeight.Bold)
                            Text((next?.let { "${DayNames[it.dayOfWeek.value]} ${it.format(dateFmt)}" } ?: "—") + (if (o.hijri) " · هجري" else "") + (yrs?.let { " · يُتم $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when (left) { null -> "—"; 0L -> "اليوم 🎉"; else -> "$left" }, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                            if (left != null && left > 0) Text("يوم", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    edit?.let { e ->
        var name by remember(e.id) { mutableStateOf(e.name) }
        var emoji by remember(e.id) { mutableStateOf(e.emoji) }
        var hijri by remember(e.id) { mutableStateOf(e.hijri) }
        var yearly by remember(e.id) { mutableStateOf(e.yearly) }
        var notifyDays by remember(e.id) { mutableIntStateOf(e.notifyDays) }
        var g by remember(e.id) { mutableStateOf(if (e.hijri) LocalDate.now() else runCatching { LocalDate.parse(e.date) }.getOrDefault(LocalDate.now())) }
        val h0 = remember(e.id) { if (e.hijri) e.date.split("-").map { it.toInt() } else Hijri.fromGregorian(LocalDate.now()).let { listOf(it.y, it.m, it.d) } }
        var hy by remember(e.id) { mutableStateOf(h0[0].toString()) }
        var hm by remember(e.id) { mutableIntStateOf(h0[1]) }
        var hd by remember(e.id) { mutableStateOf(h0[2].toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("مناسبة") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم (مثلاً: عيد ميلاد سارة)") }, singleLine = true)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OccEmojis.forEach { x -> Box(Modifier.size(38.dp).clip(CircleShape).background(if (x == emoji) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant).clickable { emoji = x }, contentAlignment = Alignment.Center) { Text(x) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(!hijri, { hijri = false }, label = { Text("ميلادي") }); FilterChip(hijri, { hijri = true }, label = { Text("هجري") })
                    }
                    if (!hijri) ValueRow("التاريخ", g.format(dateFmt)) { pickDate(ctx, g) { g = it } }
                    else Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(hd, { hd = it.filter(Char::isDigit).take(2) }, label = { Text("يوم") }, singleLine = true, modifier = Modifier.width(70.dp))
                        var open by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { open = true }) { Text(HijriMonths[hm]) }
                            DropdownMenu(open, { open = false }) { HijriMonths.forEachIndexed { i, mn -> DropdownMenuItem(text = { Text(mn) }, onClick = { hm = i; open = false }) } }
                        }
                        OutlinedTextField(hy, { hy = it.filter(Char::isDigit).take(4) }, label = { Text("سنة") }, singleLine = true, modifier = Modifier.width(84.dp))
                    }
                    SwitchRow("تتكرر كل سنة", yearly) { yearly = it }
                    Stepper("ذكّرني قبل", notifyDays, 0..30, 1, "يوم", listOf(0, 1, 3, 7)) { notifyDays = it }
                    if (e.name.isNotEmpty()) TextButton(onClick = { persist { x -> x.copy(items = x.items.filter { it.id != e.id }) }; edit = null }) { Text("حذف", color = Bad) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val date = if (hijri) "${hy.toIntOrNull() ?: 1446}-$hm-${(hd.toIntOrNull() ?: 1).coerceIn(1, 30)}" else g.toString()
                    val o = e.copy(name = name.trim(), emoji = emoji, hijri = hijri, yearly = yearly, notifyDays = notifyDays, date = date)
                    persist { x -> x.copy(items = x.items.filter { it.id != e.id } + o) }; edit = null
                }, enabled = name.isNotBlank()) { Text("حفظ") }
            }, dismissButton = { TextButton(onClick = { edit = null }) { Text("إلغاء") } })
    }
}

// =================== Voice recorder ===================
@Composable
fun RecorderScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dir = remember { File(ctx.filesDir, "recordings").apply { mkdirs() } }
    var files by remember { mutableStateOf(dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    var rec by remember { mutableStateOf<MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }
    var started by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<File?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun refresh() { files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
    fun stopPlay() { player?.runCatching { stop(); release() }; player = null; playing = null }
    fun stopRec() {
        rec?.runCatching { stop(); release() }?.onFailure { recFile?.delete() }
        rec = null; level = 0f; refresh()
    }
    DisposableEffect(Unit) { onDispose { stopRec(); stopPlay() } }
    LaunchedEffect(rec) { while (rec != null) { now = System.currentTimeMillis(); level = ((rec?.maxAmplitude ?: 0) / 32767f).coerceIn(0f, 1f); delay(100) } }

    ToolScaffold("مسجّل الصوت 🎙️", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val secs = if (rec != null) (now - started) / 1000 else 0
                    Text(String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60), fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(10.dp))
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.size(96.dp).clip(CircleShape).background(if (rec != null) Bad else MaterialTheme.colorScheme.primary).clickable {
                        if (rec != null) { stopRec(); return@clickable }
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { perm.launch(Manifest.permission.RECORD_AUDIO); return@clickable }
                        stopPlay()
                        val t = LocalDateTime.now()
                        val f = File(dir, String.format(Locale.US, "تسجيل %d-%02d-%02d %02d.%02d.%02d.m4a", t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second))
                        rec = runCatching {
                            (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                                setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(96_000); setAudioSamplingRate(44_100)
                                setOutputFile(f.path); prepare(); start()
                            }
                        }.getOrElse { Toast.makeText(ctx, "تعذر بدء التسجيل", Toast.LENGTH_SHORT).show(); null }
                        recFile = f; started = System.currentTimeMillis()
                    }, contentAlignment = Alignment.Center) { Text(if (rec != null) "⏹" else "🎙️", fontSize = 40.sp) }
                    Text(if (rec != null) "يسجّل… اضغط للإيقاف" else "اضغط للتسجيل", style = MaterialTheme.typography.bodySmall)
                    Text("أبقِ الشاشة مفتوحة أثناء التسجيل.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (files.isEmpty()) item { EmptyState("🎧", "لا توجد تسجيلات") }
            items(files, key = { it.path }) { f ->
                val dur = remember(f.path, f.length()) { runCatching { android.media.MediaMetadataRetriever().use { r -> r.setDataSource(f.path); (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0) / 1000 } }.getOrDefault(0) }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            if (playing == f.path) stopPlay() else {
                                stopPlay(); player = runCatching { MediaPlayer().apply { setDataSource(f.path); prepare(); start(); setOnCompletionListener { stopPlay() } } }.getOrNull(); playing = f.path
                            }
                        }) { Text(if (playing == f.path) "⏸" else "▶", fontSize = 22.sp) }
                        Column(Modifier.weight(1f)) {
                            Text(f.nameWithoutExtension, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(String.format(Locale.US, "%d:%02d", dur / 60, dur % 60) + " · " + com.alma.toolbox.transfer.NetUtil.fmtSize(f.length()), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        }) { Text("مشاركة") }
                        TextButton(onClick = {
                            val ok = runCatching { val t = Downloads.open(ctx, "تسجيلات", f.name); try { t.out.use { o -> f.inputStream().use { it.copyTo(o) } } } finally { t.done() } }.isSuccess
                            Toast.makeText(ctx, if (ok) "حُفظ في التنزيلات/حقيبة الما/تسجيلات ✓" else "تعذر الحفظ", Toast.LENGTH_SHORT).show()
                        }) { Text("حفظ") }
                        TextButton(onClick = { rename = f }) { Text("تسمية") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { if (playing == f.path) stopPlay(); f.delete(); refresh() }) { Text("حذف", color = Bad) }
                    }
                }
            }
        }
    }
    rename?.let { f ->
        var name by remember(f.path) { mutableStateOf(f.nameWithoutExtension) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("اسم التسجيل") },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
            confirmButton = { Button(onClick = { val n = com.alma.toolbox.transfer.NetUtil.safePath(name).replace('/', '_'); f.renameTo(File(dir, "$n.m4a")); refresh(); rename = null }) { Text("حفظ") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("إلغاء") } })
    }
}
