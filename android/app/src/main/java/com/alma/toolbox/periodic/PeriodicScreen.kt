package com.alma.toolbox.periodic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import com.alma.toolbox.core.newId
import com.alma.toolbox.ui.*
import com.alma.toolbox.reminder.ExactAlarmBanner

@Composable
fun PeriodicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data by Stores.periodic.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PeriodicAlert?>(null) }

    fun save(a: PeriodicAlert) {
        Stores.periodic.update { d -> d.copy(alerts = d.alerts.filter { it.id != a.id } + a) }
        PeriodicScheduler.schedule(ctx, a)
    }

    ToolScaffold("التنبيه الدوري ⏰", onBack, fab = {
        ExtendedFloatingActionButton(onClick = { editing = PeriodicAlert(newId(), chime = true) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("تنبيه جديد") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ExactAlarmBanner() }
            if (data.alerts.none { it.chime }) item {
                SectionCard(title = "🕰️ ساعة بيج بن") {
                    Text("تنبيه كل ساعة بلحن ساعة وستمنستر، والدقات بعدد الساعة: 3 دقات الساعة 3، و12 دقة الساعة 12.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { save(PeriodicAlert(newId(), "ساعة بيج بن 🕰️", startMin = 7 * 60, endMin = 22 * 60, intervalMin = 60, chime = true)) }) { Text("تفعيل كل ساعة") }
                        OutlinedButton(onClick = { ChimePreview.play(ctx) }) { Text("▶ استماع") }
                    }
                }
            }
            if (data.alerts.isEmpty()) item { EmptyState("⏰", "لا توجد تنبيهات دورية", "مثال: تنبيه كل 60 دقيقة من 8 ص إلى 10 م من السبت للخميس") }
            items(data.alerts.sortedBy { it.startMin }, key = { it.id }) { a ->
                val next = remember(a, data) { PeriodicScheduler.nextFire(a) }
                SectionCard(Modifier, null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.Bold)
                            Text("كل ${a.intervalMin} دقيقة · ${fmtTime(a.startMin)} ← ${fmtTime(a.endMin)}" + (if (a.chime) " · 🕰️ بيج بن" else "") + (if (a.mutedHours.isNotEmpty()) " · 🔇 ${a.mutedHours.size} ساعة" else ""))
                            Text(daysLabel(a.days), style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (a.enabled && next != null) "التنبيه القادم: ${DayNames[next.dayOfWeek.value]} ${fmtTime(next.hour * 60 + next.minute)}" else "متوقف",
                                style = MaterialTheme.typography.bodySmall, color = if (a.enabled) Good else MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(a.enabled, onCheckedChange = { save(a.copy(enabled = it)) })
                    }
                    Row {
                        TextButton(onClick = { editing = a }) { Text("تعديل") }
                        TextButton(onClick = { save(a.copy(id = newId(), name = a.name + " (نسخة)")) }) { Text("نسخ") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            PeriodicScheduler.cancel(ctx, a.id)
                            Stores.periodic.update { d -> d.copy(alerts = d.alerts.filter { it.id != a.id }) }
                        }) { Text("حذف", color = Bad) }
                    }
                }
            }
        }
    }
    editing?.let { e -> PeriodicEditor(e, onDismiss = { editing = null }, onSave = { save(it); editing = null }) }
}

@Composable
fun PeriodicEditor(initial: PeriodicAlert, onDismiss: () -> Unit, onSave: (PeriodicAlert) -> Unit) {
    var a by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعداد التنبيه الدوري") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a.name, { a = a.copy(name = it) }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("الأيام", fontWeight = FontWeight.Bold)
                DaysSelector(a.days) { a = a.copy(days = it) }
                TimeField("من الساعة", a.startMin) { a = a.copy(startMin = it) }
                TimeField("إلى الساعة", a.endMin) { a = a.copy(endMin = it) }
                Stepper("التكرار كل", a.intervalMin, 1..720, 5, "د", listOf(15, 30, 45, 60, 90, 120)) { a = a.copy(intervalMin = it) }
                Text("ساعات بلا تنبيه (اضغط لكتمها)", fontWeight = FontWeight.Bold)
                MutedHoursGrid(a.mutedHours) { a = a.copy(mutedHours = it) }
                SwitchRow("🕰️ دقات ساعة بيج بن", a.chime, "لحن الربع والنصف، وعلى رأس الساعة دقات بعددها") { a = a.copy(chime = it) }
                if (a.chime) {
                    val ctx = LocalContext.current
                    TextButton(onClick = { ChimePreview.play(ctx) }) { Text("▶ استماع لدقات الساعة الآن") }
                    Text("لا يرن إن كان الهاتف على الصامت أو الاهتزاز.", style = MaterialTheme.typography.bodySmall)
                } else {
                    SoundPicker(a.sound) { a = a.copy(sound = it) }
                    SwitchRow("اهتزاز", a.vibrate) { a = a.copy(vibrate = it) }
                }
                SwitchRow("إظهار الوقت في الإشعار", a.showTime) { a = a.copy(showTime = it) }
            }
        },
        confirmButton = { Button(onClick = { onSave(a.copy(enabled = true)) }, enabled = a.days.isNotEmpty()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

object ChimePreview {
    private var t: Thread? = null
    fun play(ctx: android.content.Context) {
        t?.interrupt()
        val now = java.time.LocalTime.now()
        t = Thread { runCatching { com.alma.toolbox.core.Chime.play(com.alma.toolbox.core.Chime.render(0, com.alma.toolbox.core.Chime.strikesFor(now.hour, 0)), android.media.AudioAttributes.USAGE_MEDIA) } }.also { it.start() }
    }
}

/** 24 small cells; muted hours are dimmed with 🔇. */
@Composable
fun MutedHoursGrid(muted: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until 24).chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { h ->
                    val off = h in muted
                    FilterChip(selected = off, onClick = { onChange(if (off) muted - h else muted + h) },
                        label = { Text((if (off) "🔇" else "") + fmtTime(h * 60).replace(":00", ""), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { onChange((0..5).toSet() + 23) }, label = { Text("كتم الليل (11م–6ص)") })
            AssistChip(onClick = { onChange(emptySet()) }, label = { Text("بدون كتم") })
        }
    }
}
