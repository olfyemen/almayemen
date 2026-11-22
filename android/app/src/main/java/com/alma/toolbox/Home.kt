package com.alma.toolbox

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
            t?.let { ToolOrder.used(act, it) }
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
        composable("adhkar/week/{d}") { com.alma.toolbox.adhkar.WeekDuaReader(nav, it.arguments?.getString("d")?.toIntOrNull() ?: 1) }
        composable("adhkar/ibt/{i}") { com.alma.toolbox.adhkar.IbtihalReader(nav, it.arguments?.getString("i")?.toIntOrNull() ?: 1) }
        composable("adhkar/sahifa/{n}") { com.alma.toolbox.adhkar.SahifaReader(nav, it.arguments?.getString("n")?.toIntOrNull() ?: 1) }
        composable("adhkar/hisn/{id}") { com.alma.toolbox.adhkar.HisnReader(nav, it.arguments?.getString("id")?.toIntOrNull() ?: 27) }
        composable(Tool.MEMORIES.id) { com.alma.toolbox.memories.MemoriesScreen(nav, back) }
        composable("memories/view/{id}", listOf(navArgument("id") { type = NavType.LongType })) { com.alma.toolbox.memories.MemoryViewScreen(nav, it.arguments!!.getLong("id")) }
        composable("memories/edit/{id}?prompt={prompt}&date={date}", listOf(
            navArgument("id") { type = NavType.LongType },
            navArgument("prompt") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null },
        )) { com.alma.toolbox.memories.MemoryEditScreen(nav, it.arguments!!.getLong("id"), it.arguments?.getString("prompt") == "1", it.arguments?.getString("date")) }
        composable("about") { com.alma.toolbox.about.AboutScreen(back) { nav.navigate(Tool.ROZNAMA.id) } }
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
    var arrange by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val order = remember(version) { ToolOrder.order(ctx) }
    val groups = remember(version) { ToolOrder.groups(ctx) }
    val hidden = remember(version) { ToolOrder.hidden(ctx) }
    val top = remember(version) { ToolOrder.top(ctx) }
    val topTools = remember(version) {
        when (top) { ToolOrder.Top.AUTO -> ToolOrder.mostUsed(ctx); ToolOrder.Top.FAV -> ToolOrder.favorites(ctx); else -> emptyList() }.filter { it !in hidden }
    }
    fun open(t: Tool) { ToolOrder.used(ctx, t); nav.navigate(t.id) }

    @Composable
    fun ToolCard(t: Tool) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp),
            modifier = Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClick = { open(t) }, onLongClick = { menuFor = t })
        ) {
            Column(Modifier.padding(12.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(Color(t.color)), contentAlignment = Alignment.Center) { Text(t.emoji, fontSize = 25.sp) }
                    Spacer(Modifier.weight(1f))
                    Text("📌", fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { menuFor = t }).padding(4.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(t.title, fontWeight = FontWeight.Bold)
                Text(t.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), maxLines = 2)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(tonalElevation = 2.dp, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(Triple("💾", "نسخ احتياطي") { backup = true }, Triple("🗂️", "ترتيب") { arrange = true }, Triple("ℹ️", "حول") { nav.navigate("about") }).forEach { (e, l, a) ->
                        Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = a).padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(e, fontSize = 20.sp); Text(l, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().padding(pad).statusBarsPadding(),
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
                        Text("«${Credits.SLOGAN}»", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (topTools.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (top == ToolOrder.Top.FAV) "⭐ مفضلتي" else "🔥 الأكثر استخداماً", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                items(topTools, key = { "top-" + it.id }) { t -> ToolCard(t) }
            }
            groups.forEach { group ->
                val tools = order.filter { it.group == group && it !in hidden && it !in topTools }
                if (tools.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(group, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(tools, key = { it.id }) { t -> ToolCard(t) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("اضغط مطولاً على أي أداة أو على 📌 لإضافتها اختصاراً إلى الشاشة الرئيسية", style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f))
            }
            item(span = { GridItemSpan(maxLineSpan) }) { CreditsCard() }
        }
    }
    if (backup) BackupDialog { backup = false }
    if (arrange) ArrangeDialog(onDismiss = { arrange = false }, onReset = { ToolOrder.reset(ctx); version++; arrange = false }) { version++; arrange = false }
    menuFor?.let { t ->
        val favs = remember(version) { ToolOrder.favorites(ctx) }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            icon = { Text(t.emoji, fontSize = 34.sp) },
            title = { Text(t.title) },
            text = {
                Column {
                    Text("إضافة «${t.title}» كاختصار على الشاشة الرئيسية لفتحها مباشرة؟")
                    TextButton(onClick = {
                        val f = if (t in favs) favs - t else favs + t
                        ToolOrder.save(ctx, ToolOrder.order(ctx), ToolOrder.groups(ctx), ToolOrder.hidden(ctx), f, if (t !in favs) ToolOrder.Top.FAV else ToolOrder.top(ctx))
                        version++; menuFor = null
                    }) { Text(if (t in favs) "إزالة من مفضلتي" else "⭐ إضافة إلى مفضلتي (أعلى الشاشة)") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = Shortcuts.pin(ctx, t)
                    if (!ok) Toast.makeText(ctx, "الشاشة الرئيسية (اللانشر) لا تدعم إضافة الاختصارات", Toast.LENGTH_LONG).show()
                    menuFor = null
                }) { Text("إضافة للشاشة الرئيسية") }
            },
            dismissButton = { TextButton(onClick = { open(t); menuFor = null }) { Text("فتح") } }
        )
    }
}

object BuildConfigVersion { const val NAME = "1.8.1" }

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
fun ArrangeDialog(onDismiss: () -> Unit, onReset: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val tools = remember { ToolOrder.order(ctx).toMutableStateList() }
    val groups = remember { ToolOrder.groups(ctx).toMutableStateList() }
    val hidden = remember { ToolOrder.hidden(ctx).toMutableStateList() }
    val fav = remember { ToolOrder.favorites(ctx).toMutableStateList() }
    var top by remember { mutableStateOf(ToolOrder.top(ctx)) }
    fun <T> MutableList<T>.move(i: Int, d: Int) { val j = i + d; if (j in indices) { val x = removeAt(i); add(j, x) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ترتيب الشاشة") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Text("أعلى الشاشة", fontWeight = FontWeight.Bold)
                    listOf(ToolOrder.Top.AUTO to "🔥 الأكثر استخداماً (تلقائي)", ToolOrder.Top.FAV to "⭐ مفضلتي (أختارها بالنجمة)", ToolOrder.Top.OFF to "بدون").forEach { (k, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { top = k }) { RadioButton(top == k, { top = k }); Text(l) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("ترتيب المجموعات", fontWeight = FontWeight.Bold)
                }
                items(groups.size, key = { "g" + groups[it] }) { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(groups[i], Modifier.weight(1f))
                        IconButton(onClick = { groups.move(i, -1) }, enabled = i > 0) { Text("▲") }
                        IconButton(onClick = { groups.move(i, 1) }, enabled = i < groups.size - 1) { Text("▼") }
                    }
                }
                groups.forEach { g ->
                    item(key = "h$g") { Text(g, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)) }
                    val inGroup = tools.filter { it.group == g }
                    items(inGroup.size, key = { "t" + inGroup[it].id }) { k ->
                        val t = inGroup[k]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = t !in hidden, onCheckedChange = { if (it) hidden.remove(t) else hidden.add(t) })
                            Text(t.emoji + " " + t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (t in hidden) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                            IconButton(onClick = { if (t in fav) fav.remove(t) else fav.add(t) }) { Text(if (t in fav) "⭐" else "☆") }
                            IconButton(onClick = { if (k > 0) { val a = tools.indexOf(t); val b = tools.indexOf(inGroup[k - 1]); tools[a] = inGroup[k - 1]; tools[b] = t } }, enabled = k > 0) { Text("▲") }
                            IconButton(onClick = { if (k < inGroup.size - 1) { val a = tools.indexOf(t); val b = tools.indexOf(inGroup[k + 1]); tools[a] = inGroup[k + 1]; tools[b] = t } }, enabled = k < inGroup.size - 1) { Text("▼") }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { ToolOrder.save(ctx, tools.toList(), groups.toList(), hidden.toSet(), fav.toList(), top); onSaved() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onReset) { Text("الافتراضي") } }
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
