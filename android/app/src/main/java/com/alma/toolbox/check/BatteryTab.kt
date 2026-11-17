package com.alma.toolbox.check

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.DisplayMetrics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alma.toolbox.ui.*
import kotlinx.coroutines.delay
import java.util.Locale

data class BatteryInfo(
    val level: Int = 0, val status: Int = 0, val plugged: Int = 0, val health: Int = 0,
    val tempC: Float = 0f, val voltage: Float = 0f, val tech: String = "", val currentMa: Int? = null,
    val chargeMah: Int? = null, val designMah: Double? = null, val cycles: Int? = null,
)

fun readBattery(ctx: Context, i: Intent?): BatteryInfo {
    i ?: return BatteryInfo()
    val bm = ctx.getSystemService(BatteryManager::class.java)
    val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE && it != 0 }
    // some phones report mA, most report µA
    val curMa = cur?.let { if (Math.abs(it) > 20000) it / 1000 else it }
    val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }?.let { it / 1000 }
    val cycles = if (Build.VERSION.SDK_INT >= 34) i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 } else null
    return BatteryInfo(
        level = lvl, status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0), plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0), tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
        voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f, tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "",
        currentMa = curMa, chargeMah = charge, designMah = designCapacity(ctx), cycles = cycles,
    )
}

@SuppressLint("PrivateApi")
private fun designCapacity(ctx: Context): Double? = try {
    val c = Class.forName("com.android.internal.os.PowerProfile")
    val o = c.getConstructor(Context::class.java).newInstance(ctx)
    (c.getMethod("getBatteryCapacity").invoke(o) as Double).takeIf { it > 100 }
} catch (e: Throwable) { null }

fun healthName(h: Int) = when (h) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "جيدة ✓"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "سخونة زائدة ⚠"
    BatteryManager.BATTERY_HEALTH_DEAD -> "تالفة ✗"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "جهد زائد ⚠"
    BatteryManager.BATTERY_HEALTH_COLD -> "باردة جداً"; BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "عطل غير محدد"
    else -> "غير معروف"
}
fun statusName(s: Int) = when (s) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "يشحن ⚡"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "يفرغ"
    BatteryManager.BATTERY_STATUS_FULL -> "ممتلئة"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "لا يشحن"; else -> "غير معروف"
}
fun plugName(p: Int) = when (p) {
    BatteryManager.BATTERY_PLUGGED_AC -> "شاحن كهرباء"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "لاسلكي"; 8 -> "قاعدة"; else -> "غير موصول"
}

@Composable
fun rememberBattery(): State<BatteryInfo> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf(readBattery(ctx, ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))) }
    DisposableEffect(Unit) {
        var last: Intent? = null
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { last = i; st.value = readBattery(c, i) } }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }
    // current changes faster than the broadcast
    LaunchedEffect(Unit) { while (true) { delay(1000); st.value = readBattery(ctx, ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) } }
    return st
}

@Composable
fun BatteryTab() {
    val b by rememberBattery()
    val curHist = remember { History(90) }
    val tempHist = remember { History(90) }
    LaunchedEffect(b) { b.currentMa?.let { curHist.add(it.toFloat()) }; tempHist.add(b.tempC) }
    val color = when { b.level <= 15 -> Bad; b.level <= 40 -> Warn; else -> Good }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Gauge(b.level.toFloat(), 0f, 100f, "${b.level}%", color, Modifier.size(150.dp))
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(statusName(b.status), fontWeight = FontWeight.Bold)
                    Text("الشحن عبر: ${plugName(b.plugged)}")
                    Text("الصحة: ${healthName(b.health)}")
                    Text(String.format(Locale.US, "الحرارة: %.1f°م", b.tempC), color = if (b.tempC > 42) Bad else Color.Unspecified)
                }
            }
        }
        SectionCard(title = "التفاصيل") {
            ValueRow("الجهد", String.format(Locale.US, "%.2f فولت", b.voltage))
            ValueRow("التقنية", b.tech.ifEmpty { "—" })
            ValueRow("التيار الآن", b.currentMa?.let { "$it mA" + if (it > 0) " (داخل)" else " (خارج)" } ?: "غير مدعوم")
            ValueRow("الشحنة الحالية", b.chargeMah?.let { "$it mAh" } ?: "غير مدعوم")
            ValueRow("السعة التصميمية", b.designMah?.let { "${it.toInt()} mAh" } ?: "غير معروف")
            if (b.chargeMah != null && b.level > 0) {
                val est = b.chargeMah!! * 100 / b.level
                ValueRow("السعة الفعلية التقديرية", "$est mAh")
                b.designMah?.let { ValueRow("نسبة صحة البطارية", "${(est * 100 / it).toInt().coerceAtMost(100)}%") }
            }
            b.cycles?.let { ValueRow("دورات الشحن", "$it") }
        }
        if (curHist.size > 1) SectionCard(title = "التيار (mA) لحظياً") { LineChart(listOf(curHist.toList())) }
        SectionCard(title = "الحرارة (°م)") { LineChart(listOf(tempHist.toList()), colors = listOf(Bad)) }
        Text("للحصول على قراءة دقيقة للتيار افصل الشاحن ثم وصّله وراقب الرسم.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
fun DeviceInfoTab() {
    val ctx = LocalContext.current
    var uptime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); uptime = SystemClock.elapsedRealtime() } }
    val mem = remember { ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) } }
    val st = remember { StatFs(Environment.getDataDirectory().path) }
    val dm: DisplayMetrics = ctx.resources.displayMetrics
    val refresh = remember { runCatching { ctx.display?.refreshRate }.getOrNull() ?: 60f }
    fun gb(b: Long) = String.format(Locale.US, "%.1f GB", b / 1e9)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "الجهاز") {
            ValueRow("الشركة", Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            ValueRow("الطراز", Build.MODEL)
            ValueRow("اسم الجهاز", Build.DEVICE)
            ValueRow("أندرويد", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            ValueRow("تحديث الأمان", Build.VERSION.SECURITY_PATCH)
            ValueRow("مدة التشغيل", String.format(Locale.US, "%d ساعة %d د", uptime / 3600000, uptime / 60000 % 60))
        }
        SectionCard(title = "المعالج والذاكرة") {
            ValueRow("المعالج", if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
            ValueRow("الأنوية", "${Runtime.getRuntime().availableProcessors()}")
            ValueRow("المعمارية", Build.SUPPORTED_ABIS.joinToString("، "))
            ValueRow("الرام", "${gb(mem.totalMem - mem.availMem)} مستخدم من ${gb(mem.totalMem)}")
            LinearProgressIndicator(progress = { 1f - mem.availMem.toFloat() / mem.totalMem }, modifier = Modifier.fillMaxWidth())
            ValueRow("التخزين", "${gb(st.totalBytes - st.availableBytes)} مستخدم من ${gb(st.totalBytes)}")
            LinearProgressIndicator(progress = { 1f - st.availableBytes.toFloat() / st.totalBytes }, modifier = Modifier.fillMaxWidth())
        }
        SectionCard(title = "الشاشة") {
            ValueRow("الدقة", "${dm.widthPixels} × ${dm.heightPixels}")
            ValueRow("الكثافة", "${dm.densityDpi} dpi")
            ValueRow("معدل التحديث", String.format(Locale.US, "%.0f هرتز", refresh))
            val inches = Math.sqrt(Math.pow(dm.widthPixels / dm.xdpi.toDouble(), 2.0) + Math.pow(dm.heightPixels / dm.ydpi.toDouble(), 2.0))
            ValueRow("الحجم التقريبي", String.format(Locale.US, "%.1f بوصة", inches))
        }
    }
}
