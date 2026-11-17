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
        if (t != null) {
            nav.navigate(t.id) { popUpTo("home"); launchSingleTop = true }
            act.pendingTool.value = null
        }
    }
    val back: () -> Unit = { if (!nav.popBackStack()) act.finish() }
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable(Tool.PERIODIC.id) { PeriodicScreen(back) }
        composable(Tool.SILENT.id) { SilentScreen(back) }
        composable(Tool.REMINDER.id) { ReminderScreen(back) }
        composable(Tool.POMODORO.id) { PomodoroScreen(back) }
        composable(Tool.LEDGER.id) { LedgerHome(nav, back) }
        composable("ledger/group/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            LedgerGroupScreen(nav, it.arguments!!.getLong("id"))
        }
        composable("ledger/account/{id}", listOf(navArgument("id") { type = NavType.LongType })) {
            LedgerAccountScreen(nav, it.arguments!!.getLong("id"))
        }
        composable("ledger/settings") { LedgerSettingsScreen(nav) }
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
                TextButton(onClick = { about = true }) { Text("حول") }
            }
        }
        Tool.entries.groupBy { it.group }.forEach { (group, tools) ->
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
                Text("يعمل بدون إنترنت (عدا قياس السرعة).\nرسوم الإيموجي في الشعار من Twemoji (CC-BY 4.0).", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = { about = false }) { Text("حسناً") } }
    )
}

object BuildConfigVersion { const val NAME = "1.1" }

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
