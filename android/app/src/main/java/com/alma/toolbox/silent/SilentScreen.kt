package com.alma.toolbox.silent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alma.toolbox.core.Stores
import com.alma.toolbox.core.newId
import com.alma.toolbox.ui.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

@Composable
fun SilentScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data by Stores.silent.state.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(SilentEngine.hasPolicyAccess(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = SilentEngine.hasPolicyAccess(ctx); SilentEngine.apply(ctx) }
    var editing by remember { mutableStateOf<SilentRule?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var prayerSettings by remember { mutableStateOf(false) }

    fun save(r: SilentRule) {
        Stores.silent.update { d -> d.copy(rules = d.rules.filter { it.id != r.id } + r) }
        SilentEngine.apply(ctx)
    }

    ToolScaffold("الصامت المجدول 🔕", onBack, fab = {
        Box {
            ExtendedFloatingActionButton(onClick = { addMenu = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("إضافة") })
            DropdownMenu(addMenu, { addMenu = false }) {
                DropdownMenuItem(text = { Text("🕖 وقت محدد (مثلاً 7–10 م)") }, onClick = { addMenu = false; editing = SilentRule(newId(), "وقت مجدول") })
                DropdownMenuItem(text = { Text("🕌 أوقات الصلاة") }, onClick = { addMenu = false; editing = SilentRule(newId(), "أوقات الصلاة", kind = "prayer") })
                DropdownMenuItem(text = { Text("🕋 صلاة الجمعة") }, onClick = {
                    addMenu = false; editing = SilentRule(newId(), "صلاة الجمعة", kind = "prayer", prayers = setOf(JUMUAH), beforeMin = 10, afterMin = 60)
                })
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!access) item {
                Banner("لتفعيل الصامت تلقائياً اسمح للتطبيق بـ«الوصول إلى وضع عدم الإزعاج». بدونه يُستخدم الاهتزاز فقط.", "السماح") {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
            item {
                SectionCard {
                    SwitchRow("تشغيل الصامت المجدول", data.enabled, if (data.engaged) "الصامت مفعّل الآن" else null) {
                        Stores.silent.update { d -> d.copy(enabled = it) }; SilentEngine.apply(ctx)
                    }
                }
            }
            item { TodayPrayers(data.prayer) { prayerSettings = true } }
            item { UpcomingCard(data) }
            if (data.rules.isEmpty()) item { EmptyState("🔕", "لا توجد أوقات صامت", "أضف وقتاً محدداً أو أوقات الصلاة أو صلاة الجمعة") }
            items(data.rules, key = { it.id }) { r ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (r.kind == "time") "🕖 " else "🕌 ") + r.name, fontWeight = FontWeight.Bold)
                            if (r.kind == "time") Text("${fmtTime(r.startMin)} ← ${fmtTime(r.endMin)} · ${daysLabel(r.days)}")
                            else {
                                Text(r.prayers.sorted().joinToString("، ") { if (it == JUMUAH) "الجمعة" else PrayerCalc.names[it] })
                                Text("قبل الأذان ${r.beforeMin} د · بعده ${r.afterMin} د", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (r.vibrate) "اهتزاز" else "صامت", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Switch(r.enabled, { save(r.copy(enabled = it)) })
                    }
                    Row {
                        TextButton(onClick = { editing = r }) { Text("تعديل") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { Stores.silent.update { d -> d.copy(rules = d.rules.filter { it.id != r.id }) }; SilentEngine.apply(ctx) }) { Text("حذف", color = Bad) }
                    }
                }
            }
        }
    }
    editing?.let { e -> SilentEditor(e, { editing = null }) { save(it); editing = null } }
    if (prayerSettings) PrayerSettingsDialog(data.prayer, { prayerSettings = false }) { p ->
        Stores.silent.update { it.copy(prayer = p) }; SilentEngine.apply(ctx); prayerSettings = false
    }
}

@Composable
private fun TodayPrayers(p: PrayerSettings, onSettings: () -> Unit) {
    val t = remember(p) { PrayerCalc.times(LocalDate.now(), p) }
    SectionCard(title = "🕌 مواقيت اليوم · ${p.city}") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, 1, 2, 3, 4, 5).forEach { i ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(PrayerCalc.names[i], style = MaterialTheme.typography.bodySmall)
                    Text(fmtHours(t[i]).replace(" ", "\n"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        Text("طريقة الحساب: ${PrayerCalc.methods[p.method]?.title}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onSettings) { Text("الموقع وطريقة الحساب والتعديل اليدوي") }
    }
}

@Composable
private fun UpcomingCard(d: SilentData) {
    val list = remember(d) {
        val now = LocalDateTime.now()
        SilentEngine.intervals(d, LocalDate.now(), 3).filter { it.end.isAfter(now) }.take(4)
    }
    if (d.enabled && list.isNotEmpty()) SectionCard(title = "القادم") {
        list.forEach { iv ->
            val day = if (iv.start.toLocalDate() == LocalDate.now()) "اليوم" else DayNames[iv.start.dayOfWeek.value]!!
            Text("• $day ${fmtTime(iv.start.hour * 60 + iv.start.minute)} – ${fmtTime(iv.end.hour * 60 + iv.end.minute)} · ${iv.label}")
        }
    }
}

@Composable
fun SilentEditor(initial: SilentRule, onDismiss: () -> Unit, onSave: (SilentRule) -> Unit) {
    var r by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (r.kind == "time") "وقت صامت" else "صامت الصلاة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (r.kind == "time") {
                    Text("الأيام", fontWeight = FontWeight.Bold)
                    DaysSelector(r.days) { r = r.copy(days = it) }
                    TimeField("من", r.startMin) { r = r.copy(startMin = it) }
                    TimeField("إلى", r.endMin) { r = r.copy(endMin = it) }
                } else {
                    Text("الصلوات", fontWeight = FontWeight.Bold)
                    val opts = listOf(0, 2, 3, 4, 5, JUMUAH)
                    opts.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { p ->
                                FilterChip(selected = p in r.prayers, onClick = { r = r.copy(prayers = if (p in r.prayers) r.prayers - p else r.prayers + p) },
                                    label = { Text(if (p == JUMUAH) "الجمعة" else PrayerCalc.names[p]) })
                            }
                        }
                    }
                    if (JUMUAH in r.prayers) Text("عند اختيار الجمعة يُستبدل ظهر الجمعة بها.", style = MaterialTheme.typography.bodySmall)
                    if (r.prayers.any { it != JUMUAH }) {
                        Text("الأيام", fontWeight = FontWeight.Bold)
                        DaysSelector(r.days) { r = r.copy(days = it) }
                    }
                    Stepper("قبل الأذان", r.beforeMin, 0..60, 5, "د", listOf(0, 5, 10, 15)) { r = r.copy(beforeMin = it) }
                    Stepper("بعد الأذان", r.afterMin, 5..180, 5, "د", listOf(15, 20, 30, 45, 60)) { r = r.copy(afterMin = it) }
                }
                SwitchRow("اهتزاز بدل الصامت التام", r.vibrate) { r = r.copy(vibrate = it) }
            }
        },
        confirmButton = { Button(onClick = { onSave(r) }, enabled = if (r.kind == "time") r.days.isNotEmpty() else r.prayers.isNotEmpty()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@SuppressLint("MissingPermission")
private fun locate(ctx: Context, onLoc: (Location?) -> Unit) {
    val lm = ctx.getSystemService(LocationManager::class.java)
    val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    if (last != null) { onLoc(last); return }
    val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER
    try {
        if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { onLoc(it) }
        else @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, { onLoc(it) }, null)
    } catch (e: Exception) { onLoc(null) }
}

@Composable
fun PrayerSettingsDialog(initial: PrayerSettings, onDismiss: () -> Unit, onSave: (PrayerSettings) -> Unit) {
    val ctx = LocalContext.current
    var p by remember { mutableStateOf(initial) }
    var locating by remember { mutableStateOf(false) }
    val doLocate = {
        locating = true
        locate(ctx) { l ->
            locating = false
            if (l == null) Toast.makeText(ctx, "تعذر تحديد الموقع، فعّل الموقع (GPS) وحاول مجدداً", Toast.LENGTH_LONG).show()
            else p = p.copy(lat = l.latitude, lon = l.longitude, city = "موقعي")
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.any { it }) doLocate() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إعدادات مواقيت الصلاة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) doLocate()
                    else perm.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                }, modifier = Modifier.fillMaxWidth()) { Text(if (locating) "جارٍ تحديد الموقع…" else "📍 تحديد موقعي") }
                Text("أو اختر مدينة:", fontWeight = FontWeight.Bold)
                var cityMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { cityMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("${p.city} (${String.format(Locale.US, "%.3f، %.3f", p.lat, p.lon)})") }
                    DropdownMenu(cityMenu, { cityMenu = false }) {
                        Cities.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { p = p.copy(city = c.name, lat = c.lat, lon = c.lon); cityMenu = false }) }
                    }
                }
                Text("طريقة الحساب:", fontWeight = FontWeight.Bold)
                PrayerCalc.methods.forEach { (k, m) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.method == k, onClick = { p = p.copy(method = k) })
                        Text(m.title)
                    }
                }
                SwitchRow("العصر على المذهب الحنفي", p.asrHanafi) { p = p.copy(asrHanafi = it) }
                Text("تعديل يدوي بالدقائق (لمطابقة تقويم مسجدك):", fontWeight = FontWeight.Bold)
                PrayerCalc.names.forEachIndexed { i, n ->
                    Stepper(n, p.adjust.getOrElse(i) { 0 }, -30..30) { v -> p = p.copy(adjust = p.adjust.toMutableList().also { while (it.size < 6) it.add(0); it[i] = v }) }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(p) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
