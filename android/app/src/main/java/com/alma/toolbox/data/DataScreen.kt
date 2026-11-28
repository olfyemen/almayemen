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
    val waOff = remember(version) { DataGuard.waOff(ctx) }
    val hasWa = remember { DataGuard.installedWhatsApp(ctx).isNotEmpty() }
    var byMonth by remember { mutableStateOf(false) }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { DataGuard.setEnabled(ctx, true); DataGuard.reload(ctx) }
        version++
    }
    var mobileMonth by remember { mutableLongStateOf(0L) }
    val capMb = remember(version) { DataGuard.capMb(ctx) }
    val capBlocking = remember(version) { DataGuard.capBlocking(ctx) }
    LaunchedEffect(version) {
        loading = true
        apps = withContext(Dispatchers.IO) { DataGuard.usage(ctx) }
        mobileMonth = withContext(Dispatchers.IO) { DataGuard.mobileMonth(ctx) }
        loading = false
        withContext(Dispatchers.IO) { DataGuard.checkCap(ctx) }
    }

    fun enable() {
        val consent = VpnService.prepare(ctx)
        if (consent != null) vpnLauncher.launch(consent) else { DataGuard.setEnabled(ctx, true); DataGuard.reload(ctx); version++ }
    }
    fun toggle() { if (on) { DataGuard.stop(ctx); version++ } else enable() }
    fun toggleWa(v: Boolean) {
        DataGuard.setWaOff(ctx, v)
        if (v && !on) enable() else { if (on) DataGuard.reload(ctx); version++ }
        DataWidget.refresh(ctx)
    }

    val totalToday = apps.sumOf { it.today }
    val blockNow = remember(version, on, mode) { if (on) DataGuard.currentBlocked(ctx, true).size else 0 }
    val shown = remember(apps, byMonth) { DataGuard.sorted(apps, byMonth) }
    ToolScaffold("📶 عداد النت", onBack) { pad ->
    LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!access) item {
            Banner("لعرض استهلاك كل تطبيق امنح التطبيق «الوصول إلى بيانات الاستخدام» من الإعدادات.", "فتح الإعدادات", Warn) {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (on) "🟢 عداد النت: الحجب مفعّل" else "⚪ عداد النت: الحجب متوقف", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("اليوم: ${DataGuard.fmt(totalToday)} · الشهر: ${DataGuard.fmt(apps.sumOf { it.month })}", style = MaterialTheme.typography.bodySmall)
                        Text(if (on) "محجوب الآن: $blockNow تطبيق" else "لن يُحجب شيء قبل التفعيل", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { toggle() }) { Text(if (on) "إيقاف" else "تفعيل") }
                }
                Text("تفعيله يضع رمز VPN في الشريط العلوي، وجهاز واحد فقط يقدر يكون بـVPN في نفس الوقت.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (hasWa) item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (waOff && on) "🔕 الواتساب مقطوع عن النت" else "💬 الواتساب متصل دائماً", fontWeight = FontWeight.Bold)
                        Text(if (waOff && on) "لن تصلك رسائل ولا مكالمات واتساب حتى تعيد تشغيله."
                             else "مسموح افتراضياً في كل الأوضاع. فعّل المفتاح لإيقاف استقبال رسائله تماماً.",
                             style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = waOff && on, onCheckedChange = { toggleWa(it) })
                }
            }
        }
        item {
            var edit by remember { mutableStateOf(false) }
            SectionCard(title = "📦 باقة الشهر (بيانات الهاتف)") {
                val cap = capMb * 1024L * 1024L
                Text("هذا الشهر: ${DataGuard.fmt(mobileMonth)}" + if (cap > 0) " من ${DataGuard.fmt(cap)}" else "", fontWeight = FontWeight.SemiBold)
                if (cap > 0) LinearProgressIndicator(progress = { (mobileMonth.toFloat() / cap).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(8.dp),
                    color = if (mobileMonth >= cap) Bad else if (mobileMonth * 10 >= cap * 8) Warn else Good)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(capMb == 0, { DataGuard.setCapMb(ctx, 0); version++ }, label = { Text("بلا حد") })
                    listOf(1, 2, 5, 10, 20, 50).forEach { g -> FilterChip(capMb == g * 1024, { DataGuard.setCapMb(ctx, g * 1024); version++ }, label = { Text("$g جيجا") }) }
                    FilterChip(capMb > 0 && capMb % 1024 != 0 || capMb > 50 * 1024, { edit = true }, label = { Text("مخصص") })
                }
                if (capMb > 0) {
                    Text("تنبيه عند 80% و100%.", style = MaterialTheme.typography.bodySmall)
                    SwitchRow("حجب تلقائي عند انتهاء الباقة", DataGuard.capAuto(ctx), "على بيانات الهاتف فقط، والواي فاي يبقى. المسموح بالنجمة يعمل") { DataGuard.setCapAuto(ctx, it); if (on) DataGuard.reload(ctx); version++ }
                }
                if (capBlocking) Button(onClick = { DataGuard.liftCap(ctx); version++ }, colors = ButtonDefaults.buttonColors(containerColor = Warn)) { Text("رفع حجب الباقة لهذا الشهر") }
            }
            if (edit) {
                var t by remember { mutableStateOf(if (capMb > 0) capMb.toString() else "") }
                AlertDialog(onDismissRequest = { edit = false }, title = { Text("حجم الباقة بالميجا") },
                    text = { OutlinedTextField(t, { t = it.filter(Char::isDigit) }, singleLine = true, suffix = { Text("MB") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)) },
                    confirmButton = { TextButton(onClick = { DataGuard.setCapMb(ctx, t.toIntOrNull() ?: 0); edit = false; version++ }) { Text("حفظ") } })
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
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("الأكثر استهلاكاً", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                FilterChip(!byMonth, { byMonth = false }, label = { Text("اليوم") })
                FilterChip(byMonth, { byMonth = true }, label = { Text("هذا الشهر") })
            }
        }
        if (loading) item { Text("جارٍ الحساب…", style = MaterialTheme.typography.bodySmall) }
        if (!loading && apps.isEmpty()) item { EmptyState("📶", "لا توجد تطبيقات تستخدم الإنترنت") }
        items(shown, key = { it.pkg }) { a ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(if (byMonth) "الشهر ${DataGuard.fmt(a.month)} · اليوم ${DataGuard.fmt(a.today)}"
                             else "اليوم ${DataGuard.fmt(a.today)} · الشهر ${DataGuard.fmt(a.month)}", style = MaterialTheme.typography.bodySmall)
                        if (a.month > 0) Text("الشهر: 📱 ${DataGuard.fmt(a.monthMobile)} · 📶 واي فاي ${DataGuard.fmt(a.monthWifi)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
}
