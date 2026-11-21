package com.alma.toolbox.prayer

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alma.toolbox.core.Stores
import com.alma.toolbox.reminder.ExactAlarmBanner
import com.alma.toolbox.silent.PrayerCalc
import com.alma.toolbox.silent.PrayerSettingsDialog
import com.alma.toolbox.silent.SilentEngine
import com.alma.toolbox.tools.Hijri
import com.alma.toolbox.ui.*
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

@Composable
fun PrayerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d by Stores.adhan.state.collectAsStateWithLifecycle()
    val silent by Stores.silent.state.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000) } }
    var settings by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(false) }
    var muteGrid by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (fajr?, key)
    DisposableEffect(Unit) { onDispose { AdhanService.previewStop() } }

    fun save(f: (AdhanData) -> AdhanData) { Stores.adhan.update(f); AdhanScheduler.schedule(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        val target = pickFor ?: return@rememberLauncherForActivityResult
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "ملف"
            if (target.first) save { it.copy(fajrSound = target.second, fajrUri = u.toString(), fajrName = name) }
            else save { it.copy(sound = target.second, soundUri = u.toString(), soundName = name) }
        }
        pickFor = null
    }

    val t = remember(silent.prayer, now.toLocalDate()) { PrayerCalc.times(now.toLocalDate(), silent.prayer) }
    val nextAny = remember(silent.prayer, now.minute) {
        (0L..1L).flatMap { off -> val day = now.toLocalDate().plusDays(off); val tt = PrayerCalc.times(day, silent.prayer)
            AdhanPrayers.map { p -> p to day.atStartOfDay().plusSeconds((tt[p] * 3600).toLong()) } }.firstOrNull { it.second.isAfter(now) }
    }

    ToolScaffold("مواقيت الصلاة 🕌", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.enabled.isNotEmpty()) item { ExactAlarmBanner() }
            item {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val h = Hijri.fromGregorian(now.toLocalDate(), Hijri.adjust(ctx))
                        Text("${DayNames[now.dayOfWeek.value]} · $h", style = MaterialTheme.typography.bodySmall)
                        nextAny?.let { (p, at) ->
                            val left = Duration.between(now, at)
                            Text("الصلاة القادمة: ${PrayerCalc.names[p]}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(String.format(Locale.US, "%02d:%02d:%02d", left.toHours(), left.toMinutesPart(), left.toSecondsPart()), fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            Text("عند ${fmtTime(at.hour * 60 + at.minute)} · ${silent.prayer.city}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                SectionCard(title = "اليوم") {
                    (0..5).forEach { i ->
                        val adhan = i in AdhanPrayers
                        val on = i in d.enabled
                        val isNext = nextAny?.first == i && nextAny.second.toLocalDate() == now.toLocalDate()
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isNext) Good.copy(alpha = .12f) else MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(PrayerCalc.names[i], Modifier.weight(1f), fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
                            Text(fmtHours(t[i]), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
                            if (adhan) IconToggleButton(on, { v -> save { it.copy(enabled = if (v) it.enabled + i else it.enabled - i) } }) { Text(if (on) "🔊" else "🔇", fontSize = 20.sp) }
                            else Spacer(Modifier.width(48.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { save { it.copy(enabled = AdhanPrayers.toSet()) } }) { Text("تفعيل الأذان للكل") }
                        TextButton(onClick = { settings = true }) { Text("الموقع وطريقة الحساب") }
                        TextButton(onClick = { month = !month }) { Text(if (month) "إخفاء الشهر" else "جدول الشهر") }
                    }
                }
            }
            if (month) item {
                SectionCard(title = "جدول 30 يوماً") {
                    Row { listOf("اليوم", "الفجر", "الظهر", "العصر", "المغرب", "العشاء").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } }
                    (0L until 30L).forEach { off ->
                        val day = LocalDate.now().plusDays(off); val tt = PrayerCalc.times(day, silent.prayer)
                        Row {
                            Text("${DayShort[day.dayOfWeek.value]} ${day.dayOfMonth}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            AdhanPrayers.forEach { p -> Text(fmtHours(tt[p]).substringBefore(' '), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            item {
                SectionCard(title = "صوت الأذان") {
                    AdhanPicker("الظهر والعصر والمغرب والعشاء", d.sound, d.soundName, onPlay = { AdhanService.preview(ctx, d.sound, d.soundUri) }) { c ->
                        if (c.needsFile) { pickFor = false to c.key; picker.launch(arrayOf("audio/*")) } else save { it.copy(sound = c.key) }
                    }
                    HorizontalDivider()
                    AdhanPicker("أذان الفجر", d.fajrSound, d.fajrName, onPlay = { AdhanService.preview(ctx, d.fajrSound, d.fajrUri) }) { c ->
                        if (c.needsFile) { pickFor = true to c.key; picker.launch(arrayOf("audio/*")) } else save { it.copy(fajrSound = c.key) }
                    }
                    TextButton(onClick = { AdhanService.previewStop() }) { Text("⏹ إيقاف الاستماع") }
                    Text("أذان الجامع الكبير بصنعاء: ضع ملف التسجيل (MP3) على هاتفك ثم اختره هنا، ويمكن اختيار ملف مختلف للفجر.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                SectionCard(title = "خيارات") {
                    Stepper("تنبيه قبل الأذان", d.preMin, 0..60, 5, "د", listOf(0, 5, 10, 15)) { v -> save { it.copy(preMin = v) } }
                    Text("مستوى صوت الأذان: ${d.volume}%")
                    Slider(d.volume.toFloat(), { v -> Stores.adhan.update { it.copy(volume = v.toInt()) } }, valueRange = 20f..100f)
                    SwitchRow("لا يؤذّن إذا جعلتُ الهاتف صامتاً بنفسي", d.respectSilent, "الصامت المجدول من حقيبة الما لا يمنع الأذان") { v -> save { it.copy(respectSilent = v) } }
                    HorizontalDivider()
                    Text("مدة الأذان", fontWeight = FontWeight.Bold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "كاملاً", 10 to "10 ث", 20 to "20 ث", 30 to "30 ث", 60 to "دقيقة").forEach { (v, l) ->
                            FilterChip(d.maxSec == v, { save { it.copy(maxSec = v) } }, label = { Text(l) })
                        }
                    }
                    SwitchRow("رفع الصوت تدريجياً", d.fadeIn, "يبدأ هادئاً ثم يرتفع خلال 8 ثوانٍ") { v -> save { it.copy(fadeIn = v) } }
                    Stepper("تنبيه الإقامة بعد الأذان", d.iqamaMin, 0..60, 5, "د", listOf(0, 10, 15, 20)) { v -> save { it.copy(iqamaMin = v) } }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("كتم أذان صلوات في أيام معيّنة", fontWeight = FontWeight.Bold)
                            Text(if (d.muted.isEmpty()) "لا شيء مكتوم" else "${d.muted.size} مكتوم أسبوعياً", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { muteGrid = true }) { Text("تحديد") }
                    }
                }
            }
            item {
                Text("الأذان المدمج: المسجد النبوي (ejaz215، CC BY 3.0) وأذان عام (CC0)، من ويكيميديا كومنز.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (muteGrid) MuteGridDialog(d.muted, { muteGrid = false }) { m -> save { it.copy(muted = m) }; muteGrid = false }
    if (settings) PrayerSettingsDialog(silent.prayer, { settings = false }) { p ->
        Stores.silent.update { it.copy(prayer = p) }; SilentEngine.apply(ctx); AdhanScheduler.schedule(ctx); settings = false
    }
}

@Composable
private fun AdhanPicker(label: String, key: String, fileName: String, onPlay: () -> Unit, onPick: (AdhanChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = AdhanChoices.firstOrNull { it.key == key } ?: AdhanChoices[0]
    Column {
        Text(label, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(cur.title.replace(" (اختر ملفه)", "") + if (cur.needsFile && fileName.isNotEmpty()) " · $fileName" else "", maxLines = 1)
                }
                DropdownMenu(open, { open = false }) { AdhanChoices.forEach { c -> DropdownMenuItem(text = { Text(c.title) }, onClick = { open = false; onPick(c) }) } }
            }
            IconButton(onClick = onPlay, enabled = key != "beep") { Text("▶", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun MuteGridDialog(initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val m = remember { initial.toMutableStateList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("كتم الأذان أسبوعياً") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("اضغط على الخانة لكتم أذان تلك الصلاة في ذلك اليوم (🔇). مثال: ظهر الجمعة.", style = MaterialTheme.typography.bodySmall)
                Row {
                    Spacer(Modifier.width(56.dp))
                    WeekOrder.forEach { dw -> Text(DayShort[dw]!!, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold) }
                }
                AdhanPrayers.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(PrayerCalc.names[p], Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall)
                        WeekOrder.forEach { dw ->
                            val k = "$dw-$p"; val off = k in m
                            Box(Modifier.weight(1f).padding(2.dp).height(34.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (off) Bad.copy(alpha = .18f) else Good.copy(alpha = .12f))
                                .clickable { if (off) m.remove(k) else m.add(k) }, contentAlignment = Alignment.Center) { Text(if (off) "🔇" else "🔊", fontSize = 14.sp) }
                        }
                    }
                }
                TextButton(onClick = { m.clear() }) { Text("إلغاء كل الكتم") }
            }
        },
        confirmButton = { Button(onClick = { onSave(m.toSet()) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
