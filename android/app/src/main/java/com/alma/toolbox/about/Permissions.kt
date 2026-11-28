package com.alma.toolbox.about

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.alma.toolbox.data.DataGuard
import com.alma.toolbox.ui.*

private class Perm(val emoji: String, val name: String, val usedBy: String, val granted: Boolean,
                   val runtime: String? = null, val open: (Context) -> Intent)

private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

private fun perms(ctx: Context): List<Perm> {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val l = mutableListOf(
        Perm("🔔", "الإشعارات", "كل التنبيهات والتذكيرات والأذان", nm.areNotificationsEnabled(),
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName) },
    )
    if (Build.VERSION.SDK_INT >= 31) l += Perm("⏰", "المنبهات الدقيقة", "التنبيه الدوري، التذكير، الأذان", ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + it.packageName)) }
    if (Build.VERSION.SDK_INT >= 34) l += Perm("📺", "التنبيه بملء الشاشة", "المنبه والتذكير المهم", nm.canUseFullScreenIntent()) {
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + it.packageName)) }
    l += listOf(
        Perm("🔕", "التحكم في عدم الإزعاج", "الصامت المجدول", nm.isNotificationPolicyAccessGranted) { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
        Perm("📊", "بيانات الاستخدام", "عداد النت، وقت الشاشة", DataGuard.hasUsageAccess(ctx)) { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
        Perm("🪟", "الظهور فوق التطبيقات", "عداد وقت الشاشة والقفل", Settings.canDrawOverlays(ctx)) {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + it.packageName)) },
        Perm("🛡️", "VPN المحلي", "حجب التطبيقات وإيقاف الواتساب في عداد النت", VpnService.prepare(ctx) == null) { appDetails(it) },
        Perm("🔋", "تجاهل توفير البطارية", "حتى لا يوقف النظام المنبهات والأذان", ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + it.packageName)) },
        Perm("📦", "تثبيت التحديثات", "تحديث التطبيق من داخله", ctx.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + it.packageName)) },
        Perm("📍", "الموقع", "مواقيت الصلاة، اتجاه الإشارة، اسم الواي فاي", has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), Manifest.permission.ACCESS_FINE_LOCATION) { appDetails(it) },
        Perm("📷", "الكاميرا", "رمز QR، الفلاش، الذكريات", has(Manifest.permission.CAMERA), Manifest.permission.CAMERA) { appDetails(it) },
        Perm("🎙️", "الميكروفون", "مسجّل الصوت وفحص الهاتف", has(Manifest.permission.RECORD_AUDIO), Manifest.permission.RECORD_AUDIO) { appDetails(it) },
    )
    return l
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val list = remember(version) { perms(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { version++ }
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { version++ }
    ToolScaffold("🔐 الأذونات", onBack) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                val missing = list.count { !it.granted }
                Text(if (missing == 0) "✅ كل الأذونات ممنوحة" else "$missing إذن غير ممنوح. امنح فقط ما تحتاجه للأدوات التي تستعملها.",
                    fontWeight = FontWeight.SemiBold)
            }
            items(list, key = { it.name }) { p ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.emoji, fontSize = 24.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold)
                            Text(p.usedBy, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        if (p.granted) Text("✅", fontSize = 20.sp)
                        else Button(onClick = {
                            when {
                                p.name == "VPN المحلي" -> VpnService.prepare(ctx)?.let { vpn.launch(it) }
                                p.runtime != null -> ask.launch(p.runtime)
                                else -> runCatching { ctx.startActivity(p.open(ctx)) }.onFailure { runCatching { ctx.startActivity(appDetails(ctx)) } }
                            }
                        }) { Text("منح") }
                    }
                }
            }
            item {
                TextButton(onClick = { ctx.startActivity(appDetails(ctx)) }) { Text("فتح إعدادات التطبيق في النظام") }
            }
        }
    }
}
