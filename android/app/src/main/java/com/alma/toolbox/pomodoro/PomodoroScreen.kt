package com.alma.toolbox.pomodoro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

@Composable
fun PomodoroScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d by Stores.pomodoro.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(d.running) { while (d.running) { now = System.currentTimeMillis(); delay(250) } }
    LaunchedEffect(Unit) { PomoEngine.restore(ctx) }
    var settings by remember { mutableStateOf(false) }

    val left = d.left(now)
    val total = d.duration().coerceAtLeast(1)
    val color = when (d.phase) { "work" -> Color(0xFFE5484D); "short" -> Good; else -> Accent2 }
    val todayCount = if (d.today == LocalDate.now().toString()) d.todayCount else 0
    val todayMin = if (d.today == LocalDate.now().toString()) d.todayMinutes else 0

    ToolScaffold("بومودورو 🍅", onBack, actions = { TextButton(onClick = { settings = true }) { Text("الإعدادات") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("work", "short", "long").forEach { p ->
                    FilterChip(selected = d.phase == p, onClick = {
                        if (!d.running) Stores.pomodoro.update { it.copy(phase = p, remaining = it.duration(p)) }
                    }, label = { Text(phaseName(p)) })
                }
            }
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                val track = MaterialTheme.colorScheme.surfaceVariant
                Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                    drawArc(track, 0f, 360f, false, style = Stroke(18.dp.toPx()))
                    drawArc(color, -90f, 360f * left / total, false, style = Stroke(18.dp.toPx(), cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val s = (left + 999) / 1000
                    Text(String.format(Locale.US, "%02d:%02d", s / 60, s % 60), fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                    Text(phaseName(d.phase), color = color, fontWeight = FontWeight.Bold)
                    Text("الجلسة ${d.cycle + if (d.phase == "work") 1 else 0} من ${d.cycles}", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(d.task, { v -> Stores.pomodoro.update { it.copy(task = v) } }, label = { Text("على ماذا تعمل؟ (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { PomoEngine.reset(ctx) }) { Text("إعادة") }
                Button(onClick = { PomoEngine.toggle(ctx) }, modifier = Modifier.height(60.dp).widthIn(min = 150.dp), colors = ButtonDefaults.buttonColors(containerColor = color)) {
                    Text(if (d.running) "إيقاف مؤقت" else "ابدأ", fontSize = 20.sp)
                }
                OutlinedButton(onClick = { PomoEngine.skip(ctx) }) { Text("تخطٍّ") }
            }
            SectionCard(title = "إنجاز اليوم") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("$todayCount", "جلسة 🍅"); Stat("$todayMin", "دقيقة تركيز"); Stat("${d.totalCount}", "الإجمالي")
                }
                if (todayCount > 0) Text("🍅".repeat(todayCount.coerceAtMost(20)), fontSize = 22.sp)
            }
            if (d.goalDay + d.goalWeek + d.goalMonth > 0) {
                val (pd, pw, pm) = d.progress()
                SectionCard(title = "🎯 أهدافي") {
                    listOf(Triple("اليوم", pd, d.goalDay), Triple("هذا الأسبوع", pw, d.goalWeek), Triple("هذا الشهر", pm, d.goalMonth)).filter { it.third > 0 }.forEach { (l, v, g) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(l, Modifier.width(96.dp))
                            LinearProgressIndicator(progress = { (v.toFloat() / g).coerceAtMost(1f) }, modifier = Modifier.weight(1f).height(10.dp), color = if (v >= g) Good else MaterialTheme.colorScheme.primary)
                            Text(" $v/$g" + if (v >= g) " ✓" else "", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            PomoHistory(d)
        }
    }
    if (settings) PomoSettings(d, { settings = false })
}

@Composable
private fun Stat(v: String, l: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(l, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PomoSettings(initial: PomoData, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var d by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعدادات بومودورو") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stepper("التركيز", d.work, 1..180, 5, "د", listOf(15, 25, 45, 50, 60)) { d = d.copy(work = it) }
                Stepper("استراحة قصيرة", d.short, 1..60, 1, "د", listOf(3, 5, 10)) { d = d.copy(short = it) }
                Stepper("استراحة طويلة", d.long, 1..90, 5, "د", listOf(15, 20, 30)) { d = d.copy(long = it) }
                Stepper("جلسات قبل الطويلة", d.cycles, 1..12, 1) { d = d.copy(cycles = it) }
                Text("الأهداف (بعدد الجلسات، 0 = بدون)", fontWeight = FontWeight.Bold)
                Stepper("يومياً", d.goalDay, 0..30, 1, "", listOf(0, 4, 6, 8)) { d = d.copy(goalDay = it) }
                Stepper("أسبوعياً", d.goalWeek, 0..200, 5, "", listOf(0, 20, 30, 40)) { d = d.copy(goalWeek = it) }
                Stepper("شهرياً", d.goalMonth, 0..800, 10, "", listOf(0, 80, 120, 160)) { d = d.copy(goalMonth = it) }
                SwitchRow("بدء الاستراحة تلقائياً", d.autoBreak) { d = d.copy(autoBreak = it) }
                SwitchRow("بدء التركيز تلقائياً بعد الاستراحة", d.autoWork) { d = d.copy(autoWork = it) }
                SoundPicker(d.sound) { d = d.copy(sound = it) }
                SwitchRow("اهتزاز", d.vibrate) { d = d.copy(vibrate = it) }
            }
        },
        confirmButton = {
            Button(onClick = {
                Stores.pomodoro.update { cur ->
                    val x = cur.copy(work = d.work, short = d.short, long = d.long, cycles = d.cycles, autoBreak = d.autoBreak, autoWork = d.autoWork, sound = d.sound, vibrate = d.vibrate,
                        goalDay = d.goalDay, goalWeek = d.goalWeek, goalMonth = d.goalMonth)
                    if (!x.running) x.copy(remaining = x.duration()) else x
                }
                PomoWidget.refresh(ctx)
                onDismiss()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun PomoHistory(d: PomoData) {
    val today = LocalDate.now()
    val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
    val counts = days.map { d.log[it.toString()]?.count ?: 0 }
    val week = (0..6).sumOf { d.log[today.minusDays(it.toLong()).toString()]?.minutes ?: 0 }
    val month = d.log.filterKeys { it.startsWith(today.toString().substring(0, 7)) }.values.sumOf { it.minutes }
    var streak = 0
    while ((d.log[today.minusDays(streak.toLong()).toString()]?.count ?: 0) > 0) streak++
    var open by remember { mutableStateOf<String?>(null) }
    SectionCard(title = "📅 سجل الأيام") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("${week / 60}س ${week % 60}د", "هذا الأسبوع"); Stat("${month / 60}س", "هذا الشهر"); Stat("$streak 🔥", "أيام متتالية")
        }
        val bar = MaterialTheme.colorScheme.primary; val track = MaterialTheme.colorScheme.surfaceVariant
        val max = (counts.maxOrNull() ?: 0).coerceAtLeast(4)
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            days.forEachIndexed { i, day ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (counts[i] > 0) Text("${counts[i]}", fontSize = 10.sp)
                    Canvas(Modifier.fillMaxWidth().height((80f * counts[i] / max).coerceAtLeast(3f).dp)) { drawRect(if (counts[i] > 0) bar else track) }
                    Text(DayShort[day.dayOfWeek.value]!!, fontSize = 10.sp, color = if (day == today) bar else MaterialTheme.colorScheme.outline)
                }
            }
        }
        val list = d.log.entries.sortedByDescending { it.key }.take(30)
        if (list.isEmpty()) Text("أكمل أول جلسة تركيز ليبدأ السجل.", style = MaterialTheme.typography.bodySmall)
        list.forEach { (date, day) ->
            val dt = LocalDate.parse(date)
            Column(Modifier.fillMaxWidth().clickable { open = if (open == date) null else date }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (dt == today) "اليوم" else DayNames[dt.dayOfWeek.value]} ${dt.format(dateFmt)}", Modifier.weight(1f))
                    Text("${day.count} 🍅 · ${day.minutes} د", fontWeight = FontWeight.Bold)
                }
                if (open == date) day.sessions.forEach { ss ->
                    Text("• ${ss.at} — ${ss.minutes} د" + if (ss.task.isNotBlank()) " · ${ss.task}" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
