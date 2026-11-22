package com.alma.toolbox.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alma.toolbox.ui.*
import kotlinx.coroutines.delay

data class WifiNow(val ssid: String, val rssi: Int, val level: Int, val speed: Int, val freq: Int)
data class CellNow(val dbm: Int?, val level: Int, val op: String, val type: String, val asu: Int?)

private fun hasPerm(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun readWifi(ctx: Context): WifiNow? {
    val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    if (!wm.isWifiEnabled) return null
    val i = wm.connectionInfo ?: return null
    if (i.networkId == -1 && i.rssi <= -127) return null
    val lvl = if (Build.VERSION.SDK_INT >= 30) (wm.calculateSignalLevel(i.rssi) * 4 / wm.maxSignalLevel.coerceAtLeast(1)) else WifiManager.calculateSignalLevel(i.rssi, 5)
    val ssid = i.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "(يحتاج إذن الموقع لعرض الاسم)"
    return WifiNow(ssid, i.rssi, lvl, i.linkSpeed, i.frequency)
}

@SuppressLint("MissingPermission")
private fun readCell(ctx: Context): CellNow? {
    val tm = ctx.getSystemService(TelephonyManager::class.java) ?: return null
    if (tm.simState != TelephonyManager.SIM_STATE_READY) return null
    var dbm: Int? = null; var level = 0; var asu: Int? = null
    if (Build.VERSION.SDK_INT >= 28) tm.signalStrength?.let { s ->
        level = s.level
        s.cellSignalStrengths.firstOrNull()?.let { c -> dbm = c.dbm.takeIf { it != Int.MAX_VALUE && it != CellInfo.UNAVAILABLE }; asu = c.asuLevel.takeIf { it != 99 && it != CellInfo.UNAVAILABLE } }
    }
    if (dbm == null && hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) runCatching {
        tm.allCellInfo?.firstOrNull { it.isRegistered }?.let { ci ->
            val ss = when (ci) { is CellInfoLte -> ci.cellSignalStrength; is CellInfoGsm -> ci.cellSignalStrength; is CellInfoWcdma -> ci.cellSignalStrength; else -> null }
            ss?.let { dbm = it.dbm; level = it.level; asu = it.asuLevel }
        }
    }
    val type = if (hasPerm(ctx, Manifest.permission.READ_PHONE_STATE)) runCatching {
        when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"; TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_1xRTT -> "CDMA"
            else -> "—"
        }
    }.getOrDefault("—") else "اضغط للسماح"
    return CellNow(dbm, level, tm.networkOperatorName.ifBlank { tm.simOperatorName }, type, asu)
}

fun quality(level: Int) = when (level) { 4 -> "ممتازة" to Good; 3 -> "جيدة" to Good; 2 -> "متوسطة" to Warn; 1 -> "ضعيفة" to Bad; else -> "ضعيفة جداً" to Bad }

@Composable
private fun Bars(level: Int, color: Color) {
    val off = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(64.dp, 48.dp)) {
        val w = size.width / 5
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4
            drawRoundRect(if (i < level) color else off, Offset(i * w * 1.3f, size.height - h), Size(w, h), androidx.compose.ui.geometry.CornerRadius(6f))
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun SignalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var wifi by remember { mutableStateOf<WifiNow?>(null) }
    var cell by remember { mutableStateOf<CellNow?>(null) }
    val wh = remember { History(90) }; val ch = remember { History(90) }
    var nearby by remember { mutableStateOf<List<android.net.wifi.ScanResult>>(emptyList()) }
    var permTick by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    LaunchedEffect(permTick) {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") runCatching { wm.startScan() }
        var n = 0
        while (true) {
            wifi = readWifi(ctx); cell = readCell(ctx)
            wifi?.let { wh.add(it.rssi.toFloat()) }; cell?.dbm?.let { ch.add(it.toFloat()) }
            if (n++ % 5 == 0 && hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) nearby = runCatching { wm.scanResults.sortedByDescending { it.level }.take(15) }.getOrDefault(emptyList())
            delay(1000)
        }
    }
    val needPerm = !hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || !hasPerm(ctx, Manifest.permission.READ_PHONE_STATE)

    ToolScaffold("قوة الإشارة 📶", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (needPerm) Banner("اسمح بالموقع وحالة الهاتف لعرض اسم الشبكة ونوع الاتصال (4G/5G) والشبكات القريبة.", "السماح", Accent2) {
                perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_PHONE_STATE))
            }
            SectionCard(title = "📶 الواي فاي") {
                val w = wifi
                if (w == null) Text("الواي فاي مغلق أو غير متصل")
                else {
                    val (q, col) = quality(w.level)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.ssid, fontWeight = FontWeight.Bold)
                            Text("${w.rssi} dBm", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = col)
                            Text("الإشارة $q · ${w.speed} Mbps · ${if (w.freq > 4900) "5 GHz" else "2.4 GHz"}", style = MaterialTheme.typography.bodySmall)
                        }
                        Bars(w.level, col)
                    }
                    LineChart(listOf(wh.toList()), fixedMin = -100f, fixedMax = -30f)
                }
            }
            SectionCard(title = "📡 شبكة الاتصال") {
                val c = cell
                if (c == null) Text("لا توجد شريحة جاهزة")
                else {
                    val (q, col) = quality(c.level)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.op} · ${c.type}", fontWeight = FontWeight.Bold)
                            Text(c.dbm?.let { "$it dBm" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = col)
                            Text("الإشارة $q" + (c.asu?.let { " · ASU $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                        Bars(c.level, col)
                    }
                    if (ch.size > 1) LineChart(listOf(ch.toList()), colors = listOf(Accent2), fixedMin = -130f, fixedMax = -50f)
                }
            }
            SignalDirectionCard(wifi?.rssi, cell?.dbm)
            if (nearby.isNotEmpty()) SectionCard(title = "الشبكات القريبة") {
                nearby.forEach { r ->
                    @Suppress("DEPRECATION") val name = r.SSID.ifBlank { "(مخفية)" }
                    val lvl = ((r.level + 100).coerceIn(0, 50) * 4 / 50)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f)); Text("${r.level} dBm  ", style = MaterialTheme.typography.bodySmall); Bars(lvl, quality(lvl).second)
                    }
                }
            }
            Text("المرجع: أفضل من -60 ممتازة، -60 إلى -75 جيدة، أضعف من -85 ضعيفة. حرّك الهاتف وراقب الرسم لإيجاد أفضل مكان.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
