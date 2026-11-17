package com.alma.toolbox.reminder

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import com.alma.toolbox.core.newId
import com.alma.toolbox.ui.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shows banners for whatever is blocking reliable alarms. */
@SuppressLint("BatteryLife")
@Composable
fun ExactAlarmBanner() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val exact = remember(tick) { Alarms.canExact(ctx) }
    val notif = remember(tick) { Notify.canPost(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!notif) Banner("الإشعارات متوقفة للتطبيق، لن تظهر التنبيهات.", "تفعيل") {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        if (!exact && Build.VERSION.SDK_INT >= 31) Banner("اسمح بالمنبهات الدقيقة حتى تصل التنبيهات في وقتها تماماً.", "السماح") {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
        }
        if (!battery) Banner("لضمان عمل التنبيهات والشاشة مغلقة، استثنِ التطبيق من توفير البطارية.", "استثناء", color = Accent2) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))) }
        }
    }
}

private val dtFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
private fun ldt(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun fmtDateTime(ms: Long): String { val t = ldt(ms); return "${DayNames[t.dayOfWeek.value]} ${t.format(dtFmt)} ${fmtTime(t.hour * 60 + t.minute)}" }

@Composable
fun ReminderScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data by Stores.reminders.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var confirmDel by remember { mutableStateOf<Reminder?>(null) }

    ToolScaffold("التنبيه المتكرر 🔁", onBack, fab = {
        ExtendedFloatingActionButton(onClick = { editing = Reminder(newId()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("تذكير جديد") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ExactAlarmBanner() }
            if (data.items.isEmpty()) item { EmptyState("🔁", "لا توجد تذكيرات", "تذكير مرة واحدة أو بتكرار: دقائق، ساعات، أيام محددة، شهرياً…") }
            val sorted = data.items.sortedWith(compareBy({ !it.enabled || it.nextAt == 0L }, { it.nextAt }))
            items(sorted, key = { it.id }) { r ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.title.ifBlank { "تذكير" }, fontWeight = FontWeight.Bold)
                                if (r.alarm) { Spacer(Modifier.width(6.dp)); Pill("منبه", Bad) }
                            }
                            if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            Text("${r.repeatLabel()}" + if (r.limitHours) " · ${fmtTime(r.fromMin)}–${fmtTime(r.toMin)}" else "")
                            Text(
                                if (r.enabled && r.nextAt > 0) "القادم: ${fmtDateTime(r.nextAt)}" else if (r.enabled) "انتهى" else "متوقف",
                                style = MaterialTheme.typography.bodySmall, color = if (r.enabled && r.nextAt > 0) Good else MaterialTheme.colorScheme.outline
                            )
                            if (r.endType == "count") Text("نُفّذ ${r.fired} من ${r.count}", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(r.enabled, { ReminderScheduler.save(ctx, r.copy(enabled = it, fired = if (it && r.endType == "count" && r.fired >= r.count) 0 else r.fired)) })
                    }
                    Row {
                        TextButton(onClick = { editing = r }) { Text("تعديل") }
                        TextButton(onClick = { ReminderScheduler.show(ctx, r) }) { Text("تجربة") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDel = r }) { Text("حذف", color = Bad) }
                    }
                }
            }
        }
    }
    editing?.let { e -> ReminderEditor(e, { editing = null }) { ReminderScheduler.save(ctx, it); editing = null } }
    confirmDel?.let { r -> ConfirmDialog("حذف التذكير", "حذف «${r.title}»؟", onDismiss = { confirmDel = null }) { ReminderScheduler.delete(ctx, r.id) } }
}

@Composable
fun ReminderEditor(initial: Reminder, onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    val ctx = LocalContext.current
    var r by remember { mutableStateOf(initial) }
    val start = ldt(r.startAt)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تذكير") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(r.title, { r = r.copy(title = it) }, label = { Text("العنوان") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(r.note, { r = r.copy(note = it) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())

                Text("الموعد", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val now = LocalDateTime.now().withSecond(0).withNano(0)
                    listOf(
                        "بعد 10 د" to now.plusMinutes(10), "بعد 30 د" to now.plusMinutes(30), "بعد ساعة" to now.plusHours(1),
                        "غداً 8 ص" to now.toLocalDate().plusDays(1).atTime(8, 0),
                    ).forEach { (l, t) -> AssistChip(onClick = { r = r.copy(startAt = ms(t)) }, label = { Text(l) }) }
                }
                ValueRow("التاريخ", start.format(dtFmt)) { pickDate(ctx, start.toLocalDate()) { d -> r = r.copy(startAt = ms(d.atTime(start.toLocalTime()))) } }
                ValueRow("الوقت", fmtTime(start.hour * 60 + start.minute)) {
                    pickTime(ctx, start.hour * 60 + start.minute) { m -> r = r.copy(startAt = ms(start.toLocalDate().atTime(m / 60, m % 60))) }
                }

                Text("التكرار", fontWeight = FontWeight.Bold)
                RepeatNames.entries.chunked(4).forEach { row ->
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (k, v) -> FilterChip(selected = r.repeat == k, onClick = { r = r.copy(repeat = k) }, label = { Text(v) }) }
                    }
                }
                when (r.repeat) {
                    "minute" -> Stepper("كل", r.every, 1..720, 5, "دقيقة", listOf(5, 10, 15, 30, 45)) { r = r.copy(every = it) }
                    "hour" -> Stepper("كل", r.every, 1..48, 1, "ساعة", listOf(1, 2, 3, 4, 6, 8, 12)) { r = r.copy(every = it) }
                    "day" -> Stepper("كل", r.every, 1..365, 1, "يوم") { r = r.copy(every = it) }
                    "week" -> Stepper("كل", r.every, 1..52, 1, "أسبوع") { r = r.copy(every = it) }
                    "month" -> Stepper("كل", r.every, 1..24, 1, "شهر") { r = r.copy(every = it) }
                    "weekdays" -> DaysSelector(r.weekdays) { r = r.copy(weekdays = it) }
                }
                if (r.repeat == "minute" || r.repeat == "hour") {
                    SwitchRow("فقط خلال ساعات محددة", r.limitHours) { r = r.copy(limitHours = it) }
                    if (r.limitHours) {
                        TimeField("من", r.fromMin) { r = r.copy(fromMin = it) }
                        TimeField("إلى", r.toMin) { r = r.copy(toMin = it) }
                    }
                }
                if (r.repeat != "none") {
                    Text("ينتهي", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(r.endType == "never", { r = r.copy(endType = "never") }, label = { Text("أبداً") })
                        FilterChip(r.endType == "until", {
                            r = r.copy(endType = "until", until = if (r.until == 0L) ms(LocalDate.now().plusMonths(1).atTime(23, 59)) else r.until)
                        }, label = { Text("في تاريخ") })
                        FilterChip(r.endType == "count", { r = r.copy(endType = "count", fired = 0) }, label = { Text("بعد عدد") })
                    }
                    if (r.endType == "until") ValueRow("حتى تاريخ", ldt(r.until).format(dtFmt)) {
                        pickDate(ctx, ldt(r.until).toLocalDate()) { d -> r = r.copy(until = ms(d.atTime(23, 59))) }
                    }
                    if (r.endType == "count") Stepper("عدد المرات", r.count, 1..999, 1) { r = r.copy(count = it) }
                }

                Text("التنبيه", fontWeight = FontWeight.Bold)
                SwitchRow("منبه بملء الشاشة", r.alarm, "يرن باستمرار حتى تُوقفه، ويعمل والشاشة مقفلة") { r = r.copy(alarm = it) }
                SoundPicker(r.sound, if (r.alarm) android.media.RingtoneManager.TYPE_ALARM else android.media.RingtoneManager.TYPE_NOTIFICATION) { r = r.copy(sound = it) }
                SwitchRow("اهتزاز", r.vibrate) { r = r.copy(vibrate = it) }
                Stepper("تذكير مسبق قبل", r.preMin, 0..1440, 5, "د", listOf(0, 5, 10, 15, 30, 60)) { r = r.copy(preMin = it) }
                Stepper("مدة الغفوة", r.snoozeMin, 1..120, 5, "د", listOf(5, 10, 15, 30)) { r = r.copy(snoozeMin = it) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(r.copy(enabled = true, title = r.title.ifBlank { "تذكير" })) }, enabled = r.repeat != "weekdays" || r.weekdays.isNotEmpty()) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
