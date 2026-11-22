package com.alma.toolbox.tools

import android.content.Context
import android.icu.util.IslamicCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alma.toolbox.ui.*
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

val HijriMonths = listOf("محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة", "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة")

data class HDate(val y: Int, val m: Int, val d: Int) { // m: 0..11
    override fun toString() = "$d ${HijriMonths[m]} $y هـ"
}

object Hijri {
    private fun cal() = IslamicCalendar(TimeZone.GMT_ZONE, ULocale("ar")).apply { calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA }

    fun fromGregorian(date: LocalDate, adjust: Int = 0): HDate {
        val c = cal()
        c.timeInMillis = date.plusDays(adjust.toLong()).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return HDate(c.get(IslamicCalendar.YEAR), c.get(IslamicCalendar.MONTH), c.get(IslamicCalendar.DAY_OF_MONTH))
    }

    fun toGregorian(h: HDate, adjust: Int = 0): LocalDate {
        val c = cal(); c.clear(); c.set(h.y, h.m, h.d, 12, 0)
        return java.time.Instant.ofEpochMilli(c.timeInMillis).atZone(ZoneOffset.UTC).toLocalDate().minusDays(adjust.toLong())
    }

    fun monthLength(y: Int, m: Int): Int { val c = cal(); c.clear(); c.set(y, m, 1); return c.getActualMaximum(IslamicCalendar.DAY_OF_MONTH) }

    fun adjust(ctx: Context) = ctx.getSharedPreferences("hijri", 0).getInt("adj", 0)
    fun setAdjust(ctx: Context, v: Int) = ctx.getSharedPreferences("hijri", 0).edit().putInt("adj", v).apply()
}

@Composable
fun HijriScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var adj by remember { mutableIntStateOf(Hijri.adjust(ctx)) }
    val today = LocalDate.now()
    val h = Hijri.fromGregorian(today, adj)
    var g by remember { mutableStateOf(today) }
    var hy by remember { mutableStateOf(h.y.toString()) }
    var hm by remember { mutableIntStateOf(h.m) }
    var hd by remember { mutableStateOf(h.d.toString()) }

    ToolScaffold("التاريخ الهجري 🌙", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(DayNames[today.dayOfWeek.value]!!, style = MaterialTheme.typography.titleMedium)
                    Text("${h.d}", fontSize = 64.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                    Text("${HijriMonths[h.m]} ${h.y} هـ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("الموافق ${today.format(dateFmt)} م", style = MaterialTheme.typography.bodyMedium)
                    Text("الشهر ${Hijri.monthLength(h.y, h.m)} يوماً (تقويم أم القرى)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Stepper("تعديل يوم (حسب رؤية بلدك)", adj, -2..2) { adj = it; Hijri.setAdjust(ctx, it) }
            }
            val upcoming = remember(adj, today) { IslamicEvents.upcoming(today, adj) }
            upcoming.firstOrNull()?.let { (name, emoji, gd, days) -> CountdownCard(name, emoji, gd, days) }
            SectionCard(title = "⏳ كم بقي على المناسبات الإسلامية") {
                upcoming.forEach { (n, e, gd, days) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$e $n", Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (days == 0L) "اليوم 🎉" else "$days يوم", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(gd.format(dateFmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                Text("المواعيد حسب تقويم أم القرى مع تعديلك أعلاه، وقد تختلف يوماً حسب رؤية الهلال.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            SectionCard(title = "من ميلادي إلى هجري") {
                ValueRow("التاريخ الميلادي", g.format(dateFmt)) { pickDate(ctx, g) { g = it } }
                val r = Hijri.fromGregorian(g, adj)
                Text("= ${DayNames[g.dayOfWeek.value]} $r", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
            }
            SectionCard(title = "من هجري إلى ميلادي") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(hd, { hd = it.filter(Char::isDigit).take(2) }, label = { Text("اليوم") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(80.dp))
                    var open by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(HijriMonths[hm]) }
                        DropdownMenu(open, { open = false }) { HijriMonths.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n) }, onClick = { hm = i; open = false }) } }
                    }
                    OutlinedTextField(hy, { hy = it.filter(Char::isDigit).take(4) }, label = { Text("السنة") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                }
                val yy = hy.toIntOrNull(); val dd = hd.toIntOrNull()
                if (yy != null && dd != null && yy in 1300..1600 && dd in 1..30) {
                    val gd = runCatching { Hijri.toGregorian(HDate(yy, hm, dd), adj) }.getOrNull()
                    if (gd != null) Text("= ${DayNames[gd.dayOfWeek.value]} ${gd.format(dateFmt)} م", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                } else Text("أدخل تاريخاً بين 1300 و1600 هـ", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

object IslamicEvents {
    /** name, emoji, hijri month (0-based), day */
    val list = listOf(
        Triple("رمضان", "🌙", 8 to 1), Triple("ليلة القدر (ليلة 27)", "✨", 8 to 27), Triple("عيد الفطر", "🎉", 9 to 1),
        Triple("العشر من ذي الحجة", "🕋", 11 to 1), Triple("يوم عرفة", "🤲", 11 to 9), Triple("عيد الأضحى", "🐑", 11 to 10),
        Triple("عيد الغدير", "🌿", 11 to 18), Triple("رأس السنة الهجرية", "📅", 0 to 1), Triple("عاشوراء", "🕯️", 0 to 10),
        Triple("المولد النبوي", "💚", 2 to 12), Triple("الإسراء والمعراج", "🌌", 6 to 27), Triple("النصف من شعبان", "🌕", 7 to 15),
    ).map { (n, e, md) -> Triple(n, e, md) }

    data class Up(val name: String, val emoji: String, val date: LocalDate, val days: Long)

    fun upcoming(today: LocalDate, adj: Int): List<Up> {
        val hy = Hijri.fromGregorian(today, adj).y
        return list.map { (n, e, md) ->
            var gd = Hijri.toGregorian(HDate(hy, md.first, md.second), adj)
            if (gd.isBefore(today)) gd = Hijri.toGregorian(HDate(hy + 1, md.first, md.second), adj)
            Up(n, e, gd, ChronoUnit.DAYS.between(today, gd))
        }.sortedBy { it.days }
    }
}

@Composable
private fun CountdownCard(name: String, emoji: String, date: LocalDate, days: Long) {
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = java.time.LocalDateTime.now(); kotlinx.coroutines.delay(1000) } }
    val left = java.time.Duration.between(now, date.atStartOfDay()).coerceAtLeast(java.time.Duration.ZERO)
    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$emoji $name", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (days == 0L) Text("اليوم 🎉 كل عام وأنتم بخير", fontSize = 18.sp)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(left.toDays() to "يوم", left.toHoursPart().toLong() to "ساعة", left.toMinutesPart().toLong() to "دقيقة", left.toSecondsPart().toLong() to "ثانية").forEach { (v, l) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$v", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary); Text(l, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Text("${DayNames[date.dayOfWeek.value]} ${date.format(dateFmt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
