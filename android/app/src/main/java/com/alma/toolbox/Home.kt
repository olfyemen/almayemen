package com.alma.toolbox

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alma.toolbox.check.PhoneCheckScreen
import com.alma.toolbox.ledger.*
import com.alma.toolbox.periodic.PeriodicScreen
import com.alma.toolbox.pomodoro.PomodoroScreen
import com.alma.toolbox.reminder.ReminderScreen
import com.alma.toolbox.silent.SilentScreen
import com.alma.toolbox.tools.*

@Composable
fun AppNav(act: MainActivity) {
    val nav = rememberNavController()
    val pending by act.pendingTool
    LaunchedEffect(pending) {
        val t = Tool.of(pending)
        val route = t?.id ?: pending?.takeIf { it.startsWith("adhkar/") || it == "ledger/report" } // e.g. adhkar/hisn/15 from the adhan notice
        if (route != null) {
            nav.navigate(route) { popUpTo("home"); launchSingleTop = true }
            act.pendingTool.value = null
        }
    }
    val back: () -> Unit = { if (!nav.popBackStack()) act.finish() }
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable(Tool.PERIODIC.id) { PeriodicScreen(back) }
        composable(Tool.SILENT.id) { SilentScreen(back) }
        composable(Tool.REMINDER.id) { ReminderScreen(back) }
        composable(Tool.PRAYER.id) { com.alma.toolbox.prayer.PrayerScreen(back) }
        composable(Tool.ADHKAR.id) { com.alma.toolbox.adhkar.AdhkarScreen(nav, back) }
        composable("adhkar/sahifaList") { com.alma.toolbox.ui.ToolScaffold("فهرس الصحيفة السجادية", { nav.popBackStack() }) { p -> Box(Modifier.padding(p)) { com.alma.toolbox.adhkar.SahifaIndex(nav) } } }
        composable("adhkar/sahifa/{n}") { com.alma.toolbox.adhkar.SahifaReader(nav, it.arguments?.getString("n")?.toIntOrNull() ?: 1) }
        composable("adhkar/hisn/{id}") { com.alma.toolbox.adhkar.HisnReader(nav, it.arguments?.getString("id")?.toIntOrNull() ?: 27) }
        composable(Tool.ROZNAMA.id) { com.alma.toolbox.roznama.RoznamaScreen(back) }
        composable(Tool.NOTES.id) { com.alma.toolbox.daily.NotesScreen(back) }
        composable(Tool.OCCASIONS.id) { com.alma.toolbox.daily.OccasionsScreen(back) }
        composable(Tool.ZAKAT.id) { com.alma.toolbox.daily.ZakatScreen(back) }
        composable(Tool.RECORDER.id) { com.alma.toolbox.daily.RecorderScreen(back) }
        composable(Tool.POMODORO.id) { PomodoroScreen(back) }
        composable(Tool.LEDGER.id) { LedgerGate(back) { LedgerHome(nav, back) } }
        composable("ledger/group/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            LedgerGate(back) { LedgerGroupScreen(nav, it.arguments!!.getLong("id")) }
        }
        composable("ledger/account/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            LedgerGate(back) { LedgerAccountScreen(nav, it.arguments!!.getLong("id")) }
        }
        composable("ledger/settings") { LedgerGate(back) { LedgerSettingsScreen(nav) } }
        composable("ledger/report") { LedgerGate(back) { LedgerReportScreen(nav) } }
        composable(Tool.TRANSFER.id) { com.alma.toolbox.transfer.TransferScreen(nav, back) }
        composable("transfer/room") { com.alma.toolbox.transfer.RoomScreen(nav) }
        composable("transfer/history/{id}") { com.alma.toolbox.transfer.RoomHistoryScreen(nav, it.arguments?.getString("id") ?: "") }
        composable(Tool.CHECK.id) { PhoneCheckScreen(back) }
        composable(Tool.SPEED.id) { SpeedTestScreen(back) }
        composable(Tool.SIGNAL.id) { SignalScreen(back) }
        composable(Tool.HIJRI.id) { HijriScreen(back) }
        composable(Tool.QR.id) { QrScreen(back) }
        composable(Tool.MORSE.id) { MorseScreen(back) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var menuFor by remember { mutableStateOf<Tool?>(null) }
    var about by remember { mutableStateOf(false) }
    var arrange by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val order = remember(version) { ToolOrder.order(ctx) }
    val hidden = remember(version) { ToolOrder.hidden(ctx) }
    val custom = remember(version) { ToolOrder.isCustom(ctx) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("🧰", fontSize = 40.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("حقيبة الما", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("أدواتك اليومية في مكان واحد", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = .6f))
                    Text("«${Credits.SLOGAN}»", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { backup = true }) { Text("نسخ") }
                TextButton(onClick = { arrange = true }) { Text("ترتيب") }
                TextButton(onClick = { about = true }) { Text("حول") }
            }
        }
        val visible = order.filter { it !in hidden }
        // default order keeps the category headings; a custom order is shown as one list
        val sections = if (custom) listOf("أدواتي" to visible) else visible.groupBy { it.group }.toList()
        sections.forEach { (group, tools) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(group, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
            items(tools, key = { it.id }) { t ->
                Box {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp),
                        modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp))
                            .combinedClickable(onClick = { nav.navigate(t.id) }, onLongClick = { menuFor = t })
                    ) {
                        Column(Modifier.padding(12.dp).fillMaxSize()) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(
                                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(t.color)),
                                    contentAlignment = Alignment.Center
                                ) { Text(t.emoji, fontSize = 26.sp) }
                                Spacer(Modifier.weight(1f))
                                Text("📌", fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(onClick = { menuFor = t }).padding(4.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(t.title, fontWeight = FontWeight.Bold)
                            Text(t.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 2)
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("اضغط مطولاً على أي أداة أو على 📌 لإضافتها اختصاراً إلى الشاشة الرئيسية", style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f))
        }
        item(span = { GridItemSpan(maxLineSpan) }) { CreditsCard() }
    }
    if (backup) BackupDialog { backup = false }
    if (arrange) ArrangeDialog(order, hidden, onDismiss = { arrange = false }, onReset = { ToolOrder.reset(ctx); version++; arrange = false }) { o, h ->
        ToolOrder.save(ctx, o, h); version++; arrange = false
    }
    menuFor?.let { t ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            icon = { Text(t.emoji, fontSize = 34.sp) },
            title = { Text(t.title) },
            text = { Text("إضافة «${t.title}» كاختصار على الشاشة الرئيسية لفتحها مباشرة؟") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = Shortcuts.pin(ctx, t)
                    if (!ok) Toast.makeText(ctx, "الشاشة الرئيسية (اللانشر) لا تدعم إضافة الاختصارات", Toast.LENGTH_LONG).show()
                    menuFor = null
                }) { Text("إضافة للشاشة الرئيسية") }
            },
            dismissButton = { TextButton(onClick = { nav.navigate(t.id); menuFor = null }) { Text("فتح") } }
        )
    }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        icon = { Text("🧰", fontSize = 40.sp) },
        title = { Text("حقيبة الما") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الإصدار ${BuildConfigVersion.NAME} · من برامج «الما»", textAlign = TextAlign.Center)
                CreditsCard()
                Text("يعمل بدون إنترنت (عدا قياس السرعة).\nرسوم الإيموجي في الشعار من Twemoji (CC-BY 4.0).\nأذان الجامع الكبير بصنعاء: تسجيل من المصمم. أذان المسجد النبوي: ejaz215 (CC BY 3.0)، والأذان العام (CC0)، من ويكيميديا كومنز.\nحصن المسلم: الشيخ سعيد القحطاني، النص من hisnmuslim.com.\nنص الصحيفة السجادية: ويكي مصدر (CC BY-SA).", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = { about = false }) { Text("حسناً") } }
    )
}

object BuildConfigVersion { const val NAME = "1.6" }

@Composable
fun CreditsCard() {
    val ctx = LocalContext.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Credits.DESIGNER, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(Credits.PHONE, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
            Text(Credits.DUA, textAlign = TextAlign.Center)
            Text("«${Credits.SLOGAN}»", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wa.me/" + Credits.PHONE.removePrefix("00")))) }
                }) { Text("واتساب") }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:+" + Credits.PHONE.removePrefix("00")))) }
                }) { Text("اتصال") }
            }
        }
    }
}

@Composable
fun ArrangeDialog(initial: List<Tool>, initialHidden: Set<Tool>, onDismiss: () -> Unit, onReset: () -> Unit, onSave: (List<Tool>, Set<Tool>) -> Unit) {
    val list = remember { initial.toMutableStateList() }
    val hidden = remember { initialHidden.toMutableStateList() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ترتيب الأدوات") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Text("حرّك الأدوات بالأسهم، وأزل علامة ✓ لإخفاء أداة.", style = MaterialTheme.typography.bodySmall) }
                items(list.size, key = { list[it].id }) { i ->
                    val t = list[i]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = t !in hidden, onCheckedChange = { if (it) hidden.remove(t) else hidden.add(t) })
                        Text(t.emoji, fontSize = 20.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(t.title, Modifier.weight(1f), color = if (t in hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                        IconButton(onClick = { if (i > 0) { list.removeAt(i); list.add(i - 1, t) } }, enabled = i > 0) { Text("▲") }
                        IconButton(onClick = { if (i < list.size - 1) { list.removeAt(i); list.add(i + 1, t) } }, enabled = i < list.size - 1) { Text("▼") }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(list.toList(), hidden.toSet()) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onReset) { Text("الترتيب الافتراضي") } }
    )
}

@Composable
fun BackupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(com.alma.toolbox.core.Backup.autoEnabled(ctx)) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) Toast.makeText(ctx, if (runCatching { com.alma.toolbox.core.Backup.writeTo(ctx, u) }.isSuccess) "تم حفظ النسخة ✓" else "تعذر الحفظ", Toast.LENGTH_SHORT).show()
    }
    val opener = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) confirm = runCatching { ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().decodeToString() } }.getOrNull()
    }
    AlertDialog(onDismissRequest = onDismiss, icon = { Text("💾", fontSize = 32.sp) }, title = { Text("النسخ الاحتياطي الشامل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ملف واحد فيه كل بياناتك: الحسابات، التنبيهات، الصامت، التذكيرات، بومودورو، الملاحظات، المناسبات، الأذان، غرف الدردشة والإعدادات.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { saver.launch("حقيبة-الما-${java.time.LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("حفظ نسخة الآن") }
                OutlinedButton(onClick = { opener.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("استرجاع نسخة") }
                com.alma.toolbox.ui.SwitchRow("نسخ تلقائي أسبوعي (اختياري)", auto, "يُحفظ في التنزيلات/حقيبة الما/نسخ احتياطية" +
                    com.alma.toolbox.core.Backup.lastAuto(ctx).let { if (it.isNotEmpty()) " · آخر نسخة $it" else "" }) { v -> auto = v; Thread { com.alma.toolbox.core.Backup.setAuto(ctx, v) }.start() }
                Text("⚠ الملف غير مشفّر: احفظه في مكان آمن، فهو يحتوي حساباتك.", style = MaterialTheme.typography.bodySmall, color = com.alma.toolbox.ui.Warn)
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } })
    confirm?.let { txt ->
        com.alma.toolbox.ui.ConfirmDialog("استرجاع النسخة", "ستُستبدل كل بيانات التطبيق الحالية بمحتوى النسخة. متابعة؟", confirm = "استرجاع", onDismiss = { confirm = null }) {
            runCatching { com.alma.toolbox.core.Backup.import(ctx, txt) }
                .onSuccess { Toast.makeText(ctx, "تم استرجاع $it قسماً ✓", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(ctx, it.message ?: "الملف غير صالح", Toast.LENGTH_LONG).show() }
        }
    }
}
