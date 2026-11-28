package com.alma.toolbox.data

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alma.toolbox.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DataScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf<List<DataGuard.AppUse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val mode = remember(version) { DataGuard.mode(ctx) }
    val on = remember(version) { DataGuard.enabled(ctx) }
    val blocked = remember(version) { DataGuard.userBlocked(ctx) }
    val white = remember(version) { DataGuard.whitelist(ctx) }
    val access = remember(version) { DataGuard.hasUsageAccess(ctx) }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { DataGuard.setEnabled(ctx, true); DataGuard.reload(ctx) }
        version++
    }
    LaunchedEffect(version) {
        loading = true
        apps = withContext(Dispatchers.IO) { DataGuard.usage(ctx) }
        loading = false
    }

    fun toggle() {
        if (on) { DataGuard.stop(ctx); version++; return }
        val consent = VpnService.prepare(ctx)
        if (consent != null) vpnLauncher.launch(consent) else { DataGuard.setEnabled(ctx, true); DataGuard.reload(ctx); version++ }
    }

    val totalToday = apps.sumOf { it.today }
    val blockNow = remember(version, on, mode) { if (on) DataGuard.currentBlocked(ctx, true).size else 0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!access) item {
            Banner("لعرض استهلاك كل تطبيق امنح التطبيق «الوصول إلى بيانات الاستخدام» من الإعدادات.", "فتح الإعدادات", Warn) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (on) "🟢 توفير النت مفعّل" else "⚪ توفير النت متوقف", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("اليوم: ${DataGuard.fmt(totalToday)}", style = MaterialTheme.typography.bodySmall)
                        Text(if (on) "محجوب الآن: $blockNow تطبيق" else "لن يُحجب شيء قبل التفعيل", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { toggle() }) { Text(if (on) "إيقاف" else "تفعيل") }
                }
                Text("تفعيله يضع رمز VPN في الشريط العلوي، وجهاز واحد فقط يقدر يكون بـVPN في نفس الوقت.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        item {
            SectionCard(title = "وضع التوفير") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DataMode.all.forEach { (k, l) ->
                        FilterChip(mode == k, {
                            DataGuard.setMode(ctx, k); if (on) DataGuard.reload(ctx); version++
                        }, label = { Text(l) })
                    }
                }
                Text(DataMode.describe(mode), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("التطبيقات (الأكثر استهلاكاً اليوم)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        if (loading) item { Text("جارٍ الحساب…", style = MaterialTheme.typography.bodySmall) }
        if (!loading && apps.isEmpty()) item { EmptyState("📶", "لا توجد تطبيقات تستخدم الإنترنت") }
        items(apps, key = { it.pkg }) { a ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("اليوم ${DataGuard.fmt(a.today)} · الشهر ${DataGuard.fmt(a.month)}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { DataGuard.setWhite(ctx, a.pkg, a.pkg !in white); if (on) DataGuard.reload(ctx); version++ }) {
                        Text(if (a.pkg in white) "⭐" else "☆", fontSize = 20.sp)
                    }
                    Switch(checked = a.pkg in blocked, onCheckedChange = { v ->
                        DataGuard.setBlocked(ctx, a.pkg, v); if (on) DataGuard.reload(ctx); version++
                    })
                }
            }
        }
        item {
            Text("المفتاح يحجب التطبيق دائماً. النجمة تجعله مسموحاً في أوضاع التوفير الفائق وإيقاف الخلفية ومتوسط.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
