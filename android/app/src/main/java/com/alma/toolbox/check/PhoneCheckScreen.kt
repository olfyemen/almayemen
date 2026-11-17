package com.alma.toolbox.check

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alma.toolbox.ui.*

@Composable
fun PhoneCheckScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val results = remember { mutableStateMapOf<String, Boolean>() }
    var running by remember { mutableStateOf<HwTest?>(null) }
    ToolScaffold("فحص الهاتف 📱", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("الفحوصات", "الحساسات", "البطارية", "الجهاز").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> TestsGrid(results) { running = it }
                1 -> SensorsTab()
                2 -> BatteryTab()
                else -> DeviceInfoTab()
            }
        }
    }
    running?.let { t ->
        TestRunner(t) { r -> if (r != null) results[t.id] = r; running = null }
    }
}

@Composable
private fun TestsGrid(results: Map<String, Boolean>, onRun: (HwTest) -> Unit) {
    val ok = results.values.count { it }; val bad = results.values.count { !it }
    LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionCard {
                Text("النتيجة: $ok سليم · $bad عطل · ${HwTests.size - ok - bad} لم يُفحص", fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { (ok + bad).toFloat() / HwTests.size }, modifier = Modifier.fillMaxWidth())
                Text("اضغط أي فحص للبدء، ثم حدد هل يعمل أم لا.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(HwTests, key = { it.id }) { t ->
            val r = results[t.id]
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = when (r) { true -> Good.copy(alpha = .12f); false -> Bad.copy(alpha = .12f); null -> MaterialTheme.colorScheme.surface }),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onRun(t) }
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.emoji, fontSize = 28.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Bold)
                        Text(t.desc, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                    }
                    when (r) { true -> Text("✓", color = Good, fontSize = 22.sp, fontWeight = FontWeight.Bold); false -> Text("✗", color = Bad, fontSize = 22.sp, fontWeight = FontWeight.Bold); null -> {} }
                }
            }
        }
    }
}
