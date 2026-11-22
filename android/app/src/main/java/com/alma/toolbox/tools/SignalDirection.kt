package com.alma.toolbox.tools

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alma.toolbox.ui.*
import kotlin.math.*

private val DirNames = listOf("الشمال", "الشمال الشرقي", "الشرق", "الجنوب الشرقي", "الجنوب", "الجنوب الغربي", "الغرب", "الشمال الغربي")
fun dirName(deg: Float) = DirNames[(((deg + 22.5f) % 360f + 360f) % 360f / 45f).toInt() % 8]

/** Live compass heading (degrees from north) — only registered while this card is on screen. */
@Composable
fun rememberHeading(): State<Float?> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val l = object : SensorEventListener {
            val rm = FloatArray(9); val o = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rm, e.values); SensorManager.getOrientation(rm, o)
                st.value = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
            }
            override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return st
}

/** 12 sectors of 30°: running average signal (dBm) per sector. */
class DirectionScan {
    val sum = DoubleArray(12); val n = IntArray(12)
    fun add(heading: Float, dbm: Int) { val i = ((heading + 15f) / 30f).toInt() % 12; sum[i] += dbm.toDouble(); n[i]++ }
    fun avg(i: Int): Double? = if (n[i] > 0) sum[i] / n[i] else null
    fun covered() = n.count { it > 0 }
    fun best(): Int? = (0 until 12).filter { n[it] > 0 }.maxByOrNull { avg(it)!! }
    fun reset() { sum.fill(0.0); n.fill(0) }
}

data class PlaceReading(val name: String, val dbm: Int)

@Composable
fun SignalDirectionCard(wifiDbm: Int?, cellDbm: Int?) {
    var useWifi by remember { mutableStateOf(wifiDbm != null) }
    val value = if (useWifi) wifiDbm else cellDbm
    val heading by rememberHeading()
    val scan = remember { DirectionScan() }
    var scanning by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val places = remember { mutableStateListOf<PlaceReading>() }
    var placeName by remember { mutableStateOf("") }

    // one sample per signal update while scanning
    LaunchedEffect(value, scanning) { val h = heading; if (scanning && value != null && h != null) { scan.add(h, value); tick++ } }

    SectionCard(title = "🧭 أين تكون الإشارة أقوى؟") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(useWifi, { useWifi = true; scan.reset(); tick++ }, label = { Text("📶 الواي فاي") }, enabled = wifiDbm != null)
            FilterChip(!useWifi, { useWifi = false; scan.reset(); tick++ }, label = { Text("📡 شبكة الاتصال") }, enabled = cellDbm != null)
        }
        if (value == null) { Text("لا توجد قراءة لهذه الإشارة الآن.", style = MaterialTheme.typography.bodySmall); return@SectionCard }
        Text("1) الاتجاه: اضغط «ابدأ» ثم استدر ببطء دورة كاملة وأنت تمسك الهاتف أمامك.", style = MaterialTheme.typography.bodySmall)
        @Suppress("UNUSED_EXPRESSION") tick
        val best = scan.best()
        val primary = MaterialTheme.colorScheme.primary; val track = MaterialTheme.colorScheme.surfaceVariant; val text = MaterialTheme.colorScheme.onSurface
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(220.dp)) {
                val c = center; val R = size.minDimension / 2 - 8
                drawCircle(track, R, c, style = Stroke(2f)); drawCircle(track, R * .5f, c, style = Stroke(1f))
                // sectors: longer = stronger (-110 dBm .. -40 dBm)
                for (i in 0 until 12) {
                    val a = scan.avg(i) ?: continue
                    val f = ((a + 110) / 70).toFloat().coerceIn(0.05f, 1f)
                    val ang = Math.toRadians(i * 30.0 - 90)
                    drawLine(if (i == best) Good else primary.copy(alpha = .55f), c, Offset(c.x + (R * f * cos(ang)).toFloat(), c.y + (R * f * sin(ang)).toFloat()), 18f)
                }
                heading?.let { h ->
                    val ang = Math.toRadians(h.toDouble() - 90)
                    drawCircle(text, 7f, Offset(c.x + (R * cos(ang)).toFloat(), c.y + (R * sin(ang)).toFloat()))
                }
            }
            Text("ش", Modifier.align(Alignment.TopCenter), fontWeight = FontWeight.Bold)
        }
        Text("الآن: $value dBm" + (heading?.let { " · تتجه نحو ${dirName(it)}" } ?: " · (البوصلة غير متاحة)"), fontWeight = FontWeight.Bold)
        if (best != null && scan.covered() >= 6) {
            val bestDeg = best * 30f
            val turn = heading?.let { (((bestDeg - it) % 360f + 540f) % 360f) - 180f }
            Text("✓ أقوى إشارة نحو ${dirName(bestDeg)} (${scan.avg(best)!!.roundToInt()} dBm)", color = Good, fontWeight = FontWeight.Bold)
            if (turn != null) Text(if (abs(turn) < 20) "أنت تتجه إليها الآن 👍" else "استدر ${if (turn > 0) "يميناً" else "يساراً"} نحو ${abs(turn).roundToInt()}°", fontSize = 16.sp)
            Text("قرّب الهاتف أو المودم أو الهوائي من ذلك الاتجاه، ويُفضّل قرب نافذة فيه.", style = MaterialTheme.typography.bodySmall)
        } else if (scanning) Text("غطّيت ${scan.covered()} من 12 اتجاهاً… استمر بالدوران ببطء", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scanning = !scanning; if (scanning) { scan.reset(); tick++ } }, enabled = heading != null) { Text(if (scanning) "⏹ إيقاف" else "▶ ابدأ الدوران") }
            if (scan.covered() > 0) TextButton(onClick = { scan.reset(); tick++ }) { Text("مسح") }
        }
        HorizontalDivider()
        Text("2) الأماكن: احفظ قراءة في كل مكان وقارن بينها.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(placeName, { placeName = it.take(20) }, placeholder = { Text("اسم المكان (الصالة، النافذة…)") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { places.add(PlaceReading(placeName.trim().ifEmpty { "مكان ${places.size + 1}" }, value)); placeName = "" }) { Text("حفظ هنا") }
        }
        val sorted = places.sortedByDescending { it.dbm }
        sorted.forEachIndexed { i, p ->
            val (q, col) = quality(((p.dbm + 110).coerceIn(0, 60) * 4 / 60))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (i == 0) "🏆 " else "• ") + p.name, Modifier.weight(1f), fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
                Text("${p.dbm} dBm · $q", color = col)
            }
        }
        if (places.size > 1) TextButton(onClick = { places.clear() }) { Text("مسح الأماكن") }
    }
}
