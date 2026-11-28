package com.alma.toolbox.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Tones
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val Accent = Color(0xFF6C5CE7)
val Accent2 = Color(0xFF00B4D8)
val Good = Color(0xFF16A34A)
val Bad = Color(0xFFDC2626)
val Warn = Color(0xFFF59E0B)
val Soft = Color(0xFFF1EFFF)

private val LightColors = lightColorScheme(
    primary = Accent, onPrimary = Color.White, primaryContainer = Soft, onPrimaryContainer = Color(0xFF2A2160),
    secondary = Accent2, background = Color(0xFFF7F8FC), surface = Color.White, surfaceVariant = Color(0xFFF0F2F8),
    onBackground = Color(0xFF1E2240), onSurface = Color(0xFF1E2240), error = Bad,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA99CFF), onPrimary = Color(0xFF1B1446), primaryContainer = Color(0xFF3A3170), onPrimaryContainer = Color(0xFFE7E2FF),
    secondary = Accent2, background = Color(0xFF111325), surface = Color(0xFF1A1D33), surfaceVariant = Color(0xFF262A45),
    onBackground = Color(0xFFE8EAF6), onSurface = Color(0xFFE8EAF6), error = Color(0xFFFF6B6B),
)

@Composable
fun AlmaTheme(content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

// ---------- formatting ----------
fun fmtTime(min: Int): String {
    val m = ((min % 1440) + 1440) % 1440
    val h = m / 60; val mm = m % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return String.format(Locale.US, "%d:%02d %s", h12, mm, if (h < 12) "ص" else "م")
}
fun fmtHours(hours: Double): String = fmtTime(Math.round(hours * 60).toInt())
fun fmtNum(v: Double): String {
    return if (v == Math.floor(v) && Math.abs(v) < 1e15) String.format(Locale.US, "%,d", v.toLong())
    else String.format(Locale.US, "%,.2f", v)
}
val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US)
fun fmtDate(iso: String): String = try { LocalDate.parse(iso).format(dateFmt) } catch (e: Exception) { iso }

/** ISO day numbers in Arabic week order (Saturday first). */
val WeekOrder = listOf(6, 7, 1, 2, 3, 4, 5)
val DayNames = mapOf(1 to "الإثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس", 5 to "الجمعة", 6 to "السبت", 7 to "الأحد")
val DayShort = mapOf(1 to "ن", 2 to "ث", 3 to "ر", 4 to "خ", 5 to "ج", 6 to "س", 7 to "ح")
fun daysLabel(days: Set<Int>): String = when {
    days.size == 7 -> "كل يوم"
    days.isEmpty() -> "لا أيام"
    days == setOf(6, 7, 1, 2, 3) -> "السبت–الأربعاء"
    days == setOf(6, 7, 1, 2, 3, 4) -> "السبت–الخميس"
    else -> WeekOrder.filter { it in days }.joinToString("، ") { DayNames[it]!! }
}

// ---------- scaffold ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = fab,
        bottomBar = bottomBar,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun EmptyState(emoji: String, text: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 56.sp)
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), textAlign = TextAlign.Center)
    }
}

@Composable
fun Banner(text: String, button: String, color: Color = Warn, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = .14f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(button, fontWeight = FontWeight.Bold) }
        }
    }
}

// ---------- pickers ----------
@Composable
fun DaysSelector(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            WeekOrder.forEach { d ->
                val on = d in days
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onChange(if (on) days - d else days + d) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(DayShort[d]!!, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { onChange((1..7).toSet()) }, label = { Text("كل الأيام") })
            AssistChip(onClick = { onChange(setOf(6, 7, 1, 2, 3, 4)) }, label = { Text("عدا الجمعة") })
            AssistChip(onClick = { onChange(setOf(6, 7, 1, 2, 3)) }, label = { Text("السبت–الأربعاء") })
            AssistChip(onClick = { onChange(setOf(5)) }, label = { Text("الجمعة") })
        }
    }
}

fun pickTime(ctx: Context, min: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, min / 60, min % 60, false).show()
}

fun pickDate(ctx: Context, date: LocalDate, onPicked: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
}

@Composable
fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun TimeField(label: String, min: Int, onChange: (Int) -> Unit) {
    val ctx = LocalContext.current
    ValueRow(label, fmtTime(min)) { pickTime(ctx, min, onChange) }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, suffix: String = "", presets: List<Int> = emptyList(), onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            FilledTonalIconButton(onClick = { onChange((value - step).coerceIn(range)) }) { Text("−", fontSize = 20.sp) }
            Text("$value $suffix", Modifier.widthIn(min = 72.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            FilledTonalIconButton(onClick = { onChange((value + step).coerceIn(range)) }) { Text("+", fontSize = 20.sp) }
        }
        if (presets.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p -> FilterChip(selected = p == value, onClick = { onChange(p) }, label = { Text("$p $suffix") }) }
        }
    }
}

/** Opens the system ringtone picker. sound: null = default, "silent" = none, else uri. */
@Composable
fun SoundPicker(sound: String?, type: Int = RingtoneManager.TYPE_NOTIFICATION, onChange: (String?) -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val u: Uri? = r.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onChange(u?.toString() ?: "silent")
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tones.all.forEach { t ->
            val u = Tones.uri(ctx, t.key)
            FilterChip(sound == u, { onChange(u); Tones.play(ctx, u) }, label = { Text(t.title) })
        }
    }
    ValueRow("نغمة من هاتفي", Notify.soundTitle(ctx, sound)) {
        val i = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "اختر نغمة التنبيه")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(type))
            val cur = when (sound) { null, "" -> RingtoneManager.getDefaultUri(type); "silent" -> null; else -> Uri.parse(sound) }
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
        }
        launcher.launch(i)
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "حذف", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = Bad) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun OutlinedPick(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick, modifier = modifier,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = .4f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
    ) { Text(label) }
}
