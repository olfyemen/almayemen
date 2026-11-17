package com.alma.toolbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

val SeriesColors = listOf(Color(0xFF6C5CE7), Color(0xFF00A3C4), Color(0xFFE5484D), Color(0xFF16A34A))

/** Fixed-size ring buffer of floats for live charts. */
class History(val capacity: Int = 80) {
    private val data = FloatArray(capacity)
    var size = 0; private set
    private var head = 0
    fun add(v: Float) { data[head] = v; head = (head + 1) % capacity; if (size < capacity) size++ }
    operator fun get(i: Int): Float = data[(head - size + i + capacity * 2) % capacity]
    fun toList(): List<Float> = List(size) { get(it) }
    fun clear() { size = 0; head = 0 }
}

@Composable
fun LineChart(series: List<List<Float>>, modifier: Modifier = Modifier.fillMaxWidth().height(120.dp), colors: List<Color> = SeriesColors, fixedMin: Float? = null, fixedMax: Float? = null) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = .2f)
    Canvas(modifier) {
        val all = series.flatten()
        if (all.isEmpty()) return@Canvas
        var lo = fixedMin ?: all.min(); var hi = fixedMax ?: all.max()
        if (hi - lo < 1e-6f) { hi += 1f; lo -= 1f }
        for (g in 0..4) { val y = size.height * g / 4; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
        if (lo < 0 && hi > 0) { val y0 = size.height * (hi / (hi - lo)); drawLine(grid.copy(alpha = .5f), Offset(0f, y0), Offset(size.width, y0), 2f) }
        series.forEachIndexed { si, s ->
            if (s.size < 2) return@forEachIndexed
            val p = Path()
            s.forEachIndexed { i, v ->
                // RTL: newest value on the left side is confusing, keep time flowing left→right
                val x = size.width * i / (s.size - 1).toFloat()
                val y = size.height * (1 - (v - lo) / (hi - lo))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, colors[si % colors.size], style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun AxisBars(values: List<Float>, labels: List<String>, range: Float, colors: List<Color> = SeriesColors) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEachIndexed { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels.getOrElse(i) { "" }, Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
                    val mid = size.width / 2
                    val frac = (v / range).coerceIn(-1f, 1f)
                    val w = mid * frac
                    drawRect(colors[i % colors.size], topLeft = Offset(min(mid, mid + w), 0f), size = Size(kotlin.math.abs(w), size.height))
                    drawLine(Color.Gray, Offset(mid, 0f), Offset(mid, size.height), 2f)
                }
                Text(String.format(java.util.Locale.US, "%.2f", v), Modifier.width(64.dp), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun Gauge(value: Float, min: Float, max: Float, label: String, color: Color = Accent, modifier: Modifier = Modifier.size(180.dp)) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val st = Stroke(14.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 135f, 270f, false, style = st)
            val f = ((value - min) / max(1e-6f, max - min)).coerceIn(0f, 1f)
            drawArc(color, 135f, 270f * f, false, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}
