package com.alma.toolbox.check

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alma.toolbox.ui.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.sqrt

data class SensorMeta(val name: String, val unit: String, val axes: List<String>)

fun sensorMeta(type: Int): SensorMeta = when (type) {
    Sensor.TYPE_ACCELEROMETER -> SensorMeta("مقياس التسارع", "م/ث²", listOf("X", "Y", "Z"))
    Sensor.TYPE_MAGNETIC_FIELD -> SensorMeta("المجال المغناطيسي (البوصلة)", "µT", listOf("X", "Y", "Z"))
    Sensor.TYPE_GYROSCOPE -> SensorMeta("الجيروسكوب", "راديان/ث", listOf("X", "Y", "Z"))
    Sensor.TYPE_LIGHT -> SensorMeta("حساس الضوء", "لوكس", listOf("الإضاءة"))
    Sensor.TYPE_PRESSURE -> SensorMeta("الضغط الجوي", "hPa", listOf("الضغط"))
    Sensor.TYPE_PROXIMITY -> SensorMeta("حساس القرب", "سم", listOf("المسافة"))
    Sensor.TYPE_GRAVITY -> SensorMeta("الجاذبية", "م/ث²", listOf("X", "Y", "Z"))
    Sensor.TYPE_LINEAR_ACCELERATION -> SensorMeta("التسارع الخطي", "م/ث²", listOf("X", "Y", "Z"))
    Sensor.TYPE_ROTATION_VECTOR -> SensorMeta("متجه الدوران", "", listOf("X", "Y", "Z"))
    Sensor.TYPE_GAME_ROTATION_VECTOR -> SensorMeta("متجه الدوران (ألعاب)", "", listOf("X", "Y", "Z"))
    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> SensorMeta("متجه الدوران المغناطيسي", "", listOf("X", "Y", "Z"))
    Sensor.TYPE_RELATIVE_HUMIDITY -> SensorMeta("الرطوبة", "%", listOf("الرطوبة"))
    Sensor.TYPE_AMBIENT_TEMPERATURE -> SensorMeta("درجة الحرارة", "°م", listOf("الحرارة"))
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> SensorMeta("المجال المغناطيسي (خام)", "µT", listOf("X", "Y", "Z"))
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> SensorMeta("الجيروسكوب (خام)", "راديان/ث", listOf("X", "Y", "Z"))
    Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> SensorMeta("مقياس التسارع (خام)", "م/ث²", listOf("X", "Y", "Z"))
    Sensor.TYPE_STEP_COUNTER -> SensorMeta("عداد الخطوات", "خطوة", listOf("الخطوات"))
    Sensor.TYPE_STEP_DETECTOR -> SensorMeta("كاشف الخطوة", "", listOf("خطوة"))
    Sensor.TYPE_HEART_RATE -> SensorMeta("نبض القلب", "نبضة/د", listOf("النبض"))
    Sensor.TYPE_SIGNIFICANT_MOTION -> SensorMeta("الحركة الملحوظة", "", listOf("حركة"))
    Sensor.TYPE_HINGE_ANGLE -> SensorMeta("زاوية المفصل", "°", listOf("الزاوية"))
    else -> SensorMeta("", "", listOf("1", "2", "3"))
}

/** Holds latest values + history for each registered sensor. */
class SensorHub(private val sm: SensorManager) : SensorEventListener {
    val values = HashMap<Sensor, FloatArray>()
    val hist = HashMap<Sensor, List<History>>()
    fun start(list: List<Sensor>) = list.forEach {
        if (it.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT) runCatching { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    fun stop() = sm.unregisterListener(this)
    override fun onSensorChanged(e: SensorEvent) {
        val n = sensorMeta(e.sensor.type).axes.size.coerceAtMost(e.values.size)
        values[e.sensor] = e.values.copyOf(n)
        val h = hist.getOrPut(e.sensor) { List(n) { History(80) } }
        for (i in 0 until minOf(n, h.size)) h[i].add(e.values[i])
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

@Composable
fun SensorsTab() {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    val list = remember { sm.getSensorList(Sensor.TYPE_ALL).sortedBy { if (sensorMeta(it.type).name.isEmpty()) 1 else 0 } }
    val hub = remember { SensorHub(sm) }
    var tick by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(1) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) { hub.start(list); onDispose { hub.stop() } }
    LaunchedEffect(Unit) { while (true) { delay(120); tick++ } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("${list.size} حساس في هذا الهاتف", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("🔢 أرقام", "📈 مخطط", "📊 أشرطة", "⏲️ عدّاد").forEachIndexed { i, l -> FilterChip(mode == i, { mode = i }, label = { Text(l) }) }
            }
        }
        items(list, key = { it.name + it.type + it.id }) { s ->
            @Suppress("UNUSED_EXPRESSION") tick
            val meta = sensorMeta(s.type)
            val v = hub.values[s]
            SectionCard(Modifier.clickable { expanded = if (expanded == s.hashCode()) null else s.hashCode() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(meta.name.ifEmpty { s.stringType.substringAfterLast('.') }, fontWeight = FontWeight.Bold)
                        Text(s.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (v == null) Pill("لا قراءة", MaterialTheme.colorScheme.outline) else Pill("يعمل", Good)
                }
                if (v != null) when (mode) {
                    0 -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        v.forEachIndexed { i, x ->
                            Column { Text(meta.axes.getOrElse(i) { "$i" }, style = MaterialTheme.typography.bodySmall); Text(String.format(Locale.US, "%.3f", x), fontWeight = FontWeight.Bold) }
                        }
                        if (meta.unit.isNotEmpty()) Text(meta.unit, Modifier.align(Alignment.Bottom), style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        LineChart(hub.hist[s]?.map { it.toList() } ?: emptyList())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            v.forEachIndexed { i, x -> Text("${meta.axes.getOrElse(i) { "$i" }}: ${String.format(Locale.US, "%.2f", x)}", color = SeriesColors[i % 4], style = MaterialTheme.typography.bodySmall) }
                            Text(meta.unit, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    2 -> AxisBars(v.toList(), meta.axes, s.maximumRange.coerceAtLeast(1f).let { if (v.size > 1) it.coerceAtMost(40f) else it })
                    else -> {
                        val mag = if (v.size == 1) v[0] else sqrt(v.fold(0f) { a, b -> a + b * b })
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Gauge(mag, 0f, if (v.size == 1) s.maximumRange.coerceAtLeast(1f) else s.maximumRange.coerceIn(1f, 60f), String.format(Locale.US, "%.1f %s", mag, meta.unit))
                        }
                    }
                }
                if (expanded == s.hashCode()) {
                    HorizontalDivider()
                    Text("الشركة: ${s.vendor} · الإصدار ${s.version}", style = MaterialTheme.typography.bodySmall)
                    Text("المدى الأقصى: ${s.maximumRange} · الدقة: ${s.resolution} · الطاقة: ${s.power} mA", style = MaterialTheme.typography.bodySmall)
                    Text("أقل تأخير: ${s.minDelay} µs" + if (s.isWakeUpSensor) " · يوقظ الجهاز" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
