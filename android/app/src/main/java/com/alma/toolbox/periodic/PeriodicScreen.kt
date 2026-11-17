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
        ExtendedFloatingActionButton(onClick = { editing = PeriodicAlert(newId()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("تنبيه جديد") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ExactAlarmBanner() }
            if (data.alerts.isEmpty()) item { EmptyState("⏰", "لا توجد تنبيهات دورية", "مثال: تنبيه كل 60 دقيقة من 8 ص إلى 10 م من السبت للخميس") }
            items(data.alerts.sortedBy { it.startMin }, key = { it.id }) { a ->
                val next = remember(a, data) { PeriodicScheduler.nextFire(a) }
                SectionCard(Modifier, null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.Bold)
                            Text("كل ${a.intervalMin} دقيقة · ${fmtTime(a.startMin)} ← ${fmtTime(a.endMin)}")
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
                SoundPicker(a.sound) { a = a.copy(sound = it) }
                SwitchRow("اهتزاز", a.vibrate) { a = a.copy(vibrate = it) }
                SwitchRow("إظهار الوقت في الإشعار", a.showTime) { a = a.copy(showTime = it) }
            }
        },
        confirmButton = { Button(onClick = { onSave(a.copy(enabled = true)) }, enabled = a.days.isNotEmpty()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
