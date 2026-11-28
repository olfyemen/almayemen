package com.alma.toolbox.screentime

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.alma.toolbox.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class Stats(val today: ScreenTime.Use, val days: List<Pair<LocalDate, Long>>)

@Composable
fun ScreenTimeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var pick by remember { mutableStateOf<String?>(null) } // "exempt" | "distract"
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val access = remember(version) { ScreenTime.hasUsage(ctx) }
    val overlay = remember(version) { ScreenTime.hasOverlay(ctx) }
    val on = remember(version) { ScreenTime.enabled(ctx) }
    val goal = remember(version) { ScreenTime.goalMin(ctx) }
    val extra = remember(version) { ScreenTime.extraMin(ctx) }
    LaunchedEffect(version, tab) {
        stats = withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val n = when (tab) { 1 -> 7; 2 -> today.dayOfMonth; else -> 7 }
            Stats(ScreenTime.day(ctx, today), (n - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to ScreenTime.total(ctx, it) })
        }
    }
    val names = remember { HashMap<String, String>() }
    fun label(p: String) = names.getOrPut(p) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }

    ToolScaffold("⏳ وقت الشاشة", onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!access) item {
                Banner("لحساب وقت الشاشة امنح «الوصول إلى بيانات الاستخدام».", "فتح الإعدادات") { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            if (on && !overlay) item {
                Banner("للعداد الصغير وشاشة القفل امنح «الظهور فوق التطبيقات».", "منح") {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                }
            }
            item {
                val used = stats?.today?.total ?: 0L
                val g = (goal + extra) * 60_000L
                SectionCard {
                    Text("اليوم", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                    Text(ScreenTime.fmt(used), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                    if (on) {
                        LinearProgressIndicator(progress = { (used.toFloat() / g).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(10.dp),
                            color = if (used >= g) Bad else if (g - used <= 3600_000L) Warn else Good)
                        Text(if (used < g) "المتبقي ${ScreenTime.clock(g - used)} من هدف ${ScreenTime.fmt(g)}" else "⛔ تجاوزت الهدف (${ScreenTime.fmt(g)})",
                            fontWeight = FontWeight.SemiBold)
                        if (extra > 0) Text("منها مدة إضافية: $extra دقيقة", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("فتحت الهاتف ${stats?.today?.unlocks ?: 0} مرة", style = MaterialTheme.typography.bodySmall)
                    SwitchRow("تفعيل الهدف والعداد", on, "يعمل فقط والشاشة مضاءة") { v -> ScreenTime.setEnabled(ctx, v); version++ }
                }
            }
            if (on) item {
                SectionCard(title = "🎯 الهدف اليومي") {
                    Stepper("الساعات", goal / 60, 0..16) { ScreenTime.setGoalMin(ctx, it * 60 + goal % 60); ScreenTime.sync(ctx); version++ }
                    Stepper("الدقائق", goal % 60, 0..55, step = 5) { ScreenTime.setGoalMin(ctx, (goal / 60) * 60 + it); ScreenTime.sync(ctx); version++ }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(60, 120, 180, 240, 300).forEach { m -> FilterChip(goal == m, { ScreenTime.setGoalMin(ctx, m); ScreenTime.sync(ctx); version++ }, label = { Text(ScreenTime.fmt(m * 60_000L)) }) }
                    }
                }
            }
            if (on) item {
                SectionCard(title = "الخيارات") {
                    SwitchRow("لا تحسب الموسيقى في الخلفية", ScreenTime.noMusic(ctx), "لا يُحسب وقت شاشة القفل ولا وقت تطبيقات الصوت") { ScreenTime.setNoMusic(ctx, it); version++ }
                    SwitchRow("العداد الصغير على الشاشة", ScreenTime.bubble(ctx), "يظهر 5 ثوانٍ كل 15 دقيقة، وكل 10 في آخر ساعة") { ScreenTime.setBubble(ctx, it); version++ }
                    SwitchRow("القفل عند انتهاء الوقت", ScreenTime.lock(ctx), "يغطي التطبيقات حتى تختار مدة إضافية") { ScreenTime.setLock(ctx, it); version++ }
                    TextButton(onClick = { pick = "exempt" }) { Text("التطبيقات المستثناة من القفل (${ScreenTime.exempt(ctx).size})") }
                }
            }
            item {
                SectionCard(title = "🍅 التركيز مع البومودورو") {
                    SwitchRow("حجب المشتتات أثناء جلسة التركيز", ScreenTime.focusBlock(ctx), "لا يظهر العداد أثناء التركيز، وتُغطّى التطبيقات المختارة") { ScreenTime.setFocusBlock(ctx, it); version++ }
                    TextButton(onClick = { pick = "distract" }) { Text("اختيار التطبيقات المشتتة (${ScreenTime.distracting(ctx).size})") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("يومي", "أسبوعي", "شهري").forEachIndexed { i, l -> FilterChip(tab == i, { tab = i }, label = { Text(l) }) }
                }
            }
            val s = stats
            if (s == null) item { Text("جارٍ الحساب…", style = MaterialTheme.typography.bodySmall) }
            else if (tab == 0) {
                item { Text("أكثر التطبيقات استخداماً اليوم", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                val top = s.today.apps.entries.sortedByDescending { it.value }.filter { it.value >= 60_000L }.take(15)
                if (top.isEmpty()) item { EmptyState("📱", "لا توجد بيانات بعد") }
                items(top, key = { it.key }) { (p, ms) ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label(p), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(ScreenTime.fmt(ms), fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = { (ms.toFloat() / (top.first().value)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    }
                }
            } else item {
                val total = s.days.sumOf { it.second }
                val counted = s.days.count { it.second > 0 }.coerceAtLeast(1)
                SectionCard(title = if (tab == 1) "آخر 7 أيام" else "هذا الشهر") {
                    Text("المجموع ${ScreenTime.fmt(total)} · المعدل اليومي ${ScreenTime.fmt(total / counted)}", fontWeight = FontWeight.SemiBold)
                    DayBars(s.days, goal * 60_000L, on)
                    if (on) Text("الخط الأحمر = الهدف اليومي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
    pick?.let { k -> AppPicker(if (k == "exempt") "مستثناة من القفل" else "التطبيقات المشتتة",
        if (k == "exempt") ScreenTime.exempt(ctx) else ScreenTime.distracting(ctx),
        { p, v -> if (k == "exempt") ScreenTime.setExempt(ctx, p, v) else ScreenTime.setDistracting(ctx, p, v) }) { pick = null; version++ } }
}

@Composable
private fun DayBars(days: List<Pair<LocalDate, Long>>, goal: Long, showGoal: Boolean) {
    val max = maxOf(days.maxOfOrNull { it.second } ?: 0L, if (showGoal) goal else 0L, 1L).toFloat()
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val n = days.size; val gap = if (n > 10) 3f else 10f
            val w = (size.width - gap * (n - 1)) / n
            days.forEachIndexed { i, (_, v) ->
                // RTL: today on the left end like the rest of the screen reads
                val x = size.width - (i + 1) * w - i * gap
                drawRoundRect(track, Offset(x, 0f), Size(w, size.height), CornerRadius(6f))
                val hgt = size.height * (v / max)
                drawRoundRect(if (showGoal && v > goal) Color(0xFFDC2626) else color, Offset(x, size.height - hgt), Size(w, hgt), CornerRadius(6f))
            }
            if (showGoal && goal > 0) {
                val y = size.height * (1 - goal / max)
                drawLine(Color(0xFFDC2626), Offset(0f, y), Offset(size.width, y), 3f)
            }
        }
        if (days.size <= 7) Row(Modifier.fillMaxWidth()) {
            days.forEach { (d, v) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(DayShort[d.dayOfWeek.value] ?: "", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(ScreenTime.clock(v), fontSize = 10.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Launcher apps with a checkbox each. */
@Composable
fun AppPicker(title: String, selected: Set<String>, onSet: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(selected) }
    var q by remember { mutableStateOf("") }
    val apps by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
                .filter { it.first != ctx.packageName }.sortedBy { it.second }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(q, { q = it }, placeholder = { Text("بحث") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(apps.filter { q.isBlank() || it.second.contains(q.trim(), true) }, key = { it.first }) { (p, n) ->
                    Row(Modifier.fillMaxWidth().clickable { val v = p !in sel; onSet(p, v); sel = if (v) sel + p else sel - p },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p in sel, { v -> onSet(p, v); sel = if (v) sel + p else sel - p })
                        Text(n, maxLines = 1)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } })
}
