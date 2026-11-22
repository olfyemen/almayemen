package com.alma.toolbox.transfer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.*
import java.time.Instant
import java.time.ZoneId

private fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalTime().let { fmtTime(it.hour * 60 + it.minute) }

fun openSaved(ctx: android.content.Context, uri: String) {
    val u = Uri.parse(uri)
    val type = ctx.contentResolver.getType(u) ?: "*/*"
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "لا يوجد تطبيق لفتح هذا الملف", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun Bubble(m: Msg, mine: Boolean, ctx: android.content.Context, live: Boolean) {
    if (m.kind == "sys") {
        Text(m.text, Modifier.fillMaxWidth().padding(vertical = 2.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .then(if (m.savedUri.isNotEmpty()) Modifier.clickable { openSaved(ctx, m.savedUri) } else Modifier)
                .padding(10.dp)
        ) {
            if (!mine) Text(m.from, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            if (m.kind == "file") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📄", fontSize = 24.sp); Spacer(Modifier.width(6.dp))
                    Column {
                        Text(m.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        val folder = m.path.substringBeforeLast('/', "")
                        Text(NetUtil.fmtSize(m.size) + if (folder.isNotEmpty()) " · 📁 $folder" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(when {
                    mine -> "أُرسل ✓"
                    m.savedUri.isNotEmpty() -> "✓ محفوظ في التنزيلات · اضغط للفتح"
                    live -> "جارٍ الحفظ…"
                    else -> "لم يُحفظ"
                }, style = MaterialTheme.typography.bodySmall, color = if (m.savedUri.isNotEmpty()) Good else MaterialTheme.colorScheme.outline)
            } else Text(m.text)
            Text(hm(m.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun RoomScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val c by TransferHub.client.collectAsStateWithLifecycle()
    val client = c ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val msgs by client.messages.collectAsStateWithLifecycle()
    val members by client.members.collectAsStateWithLifecycle()
    val status by client.status.collectAsStateWithLifecycle()
    val error by client.error.collectAsStateWithLifecycle()
    val people by client.people.collectAsStateWithLifecycle()
    val allowFiles by client.allowFiles.collectAsStateWithLifecycle()
    val roomLocked by client.locked.collectAsStateWithLifecycle()
    val isAdmin by client.isAdmin.collectAsStateWithLifecycle()
    var manage by remember { mutableStateOf(false) }
    val canSendFiles = allowFiles || isAdmin
    val myId = Stores.transfer.value.myId
    var text by remember { mutableStateOf("") }
    var attach by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val (pickFiles, pickFolder) = rememberPickers { items -> client.upload(items) }
    UiVisible()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    ToolScaffold("💬 ${client.roomName}", { nav.popBackStack() }, actions = {
        if (isAdmin) TextButton(onClick = { manage = true }) { Text("إدارة") }
        TextButton(onClick = { share = true }) { Text("رابط") }
        TextButton(onClick = { leave = true }) { Text(if (client.mine) "إغلاق" else "خروج", color = Bad) }
    }, bottomBar = {
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(Modifier.navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    FilledTonalIconButton(onClick = { attach = true }, enabled = status == "ok" && canSendFiles) { Icon(Icons.Default.Add, "إرفاق") }
                    DropdownMenu(attach, { attach = false }) {
                        DropdownMenuItem(text = { Text("📄 ملفات") }, onClick = { attach = false; pickFiles() })
                        DropdownMenuItem(text = { Text("📁 مجلد كامل") }, onClick = { attach = false; pickFolder() })
                    }
                }
                OutlinedTextField(text, { text = it }, placeholder = { Text("اكتب رسالة") }, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 4, shape = RoundedCornerShape(20.dp))
                FilledIconButton(onClick = { client.send(text); text = "" }, enabled = text.isNotBlank() && status == "ok") { Icon(Icons.AutoMirrored.Filled.Send, "إرسال") }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!canSendFiles) Text("📎 أوقف صاحب الغرفة إرسال الملفات · يمكنك الكتابة فقط", Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = Warn)
            Text("👥 ${members.size} متصل: ${members.joinToString("، ")}", Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            when (status) {
                "error" -> Banner("انقطع الاتصال: $error · يعيد المحاولة…", "", Bad) {}
                "closed" -> Banner("أُغلقت الغرفة من صاحبها", "خروج", Bad) { TransferHub.leaveRoom(); nav.popBackStack() }
            }
            Box(Modifier.padding(horizontal = 16.dp)) { JobsCard() }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(msgs, key = { it.id + it.seq }) { m -> Bubble(m, m.fromId == myId, ctx, true) }
            }
        }
    }
    if (share) {
        val base = if (client.base.contains("127.0.0.1")) TransferHub.url() else client.base
        AlertDialog(onDismissRequest = { share = false }, title = { Text("دعوة إلى «${client.roomName}»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (base != null) QrBox("$base/?r=${client.roomId}", "يفتحه أي جهاز على نفس الشبكة من المتصفح (آيفون، كمبيوتر…)\n$base")
                    Text("من عنده حقيبة الما يجد الغرفة تلقائياً في تبويب «الغرف».", style = MaterialTheme.typography.bodySmall)
                    Stores.transfer.value.rooms.firstOrNull { it.id == client.roomId && it.mine && it.password.isNotEmpty() }?.let { Text("كلمة السر: ${it.password}", fontWeight = FontWeight.Bold) }
                }
            }, confirmButton = { TextButton(onClick = { share = false }) { Text("تم") } })
    }
    if (manage) AlertDialog(onDismissRequest = { manage = false }, title = { Text("إدارة الغرفة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SwitchRow("الأعضاء يرسلون ملفات", allowFiles) { client.admin("files", value = it) }
                SwitchRow("قفل الغرفة أمام المنضمين الجدد", roomLocked, "المتصلون الآن يبقون") { client.admin("lock", value = it) }
                Text("المتصلون", fontWeight = FontWeight.Bold)
                people.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (p.admin) "👑 " else "👤 ") + p.name, Modifier.weight(1f))
                        if (!p.admin && p.id.isNotEmpty()) TextButton(onClick = { client.admin("kick", p.id) }) { Text("إخراج", color = Bad) }
                    }
                }
                Text("المُخرَج لا يستطيع العودة ما دامت الغرفة مفتوحة.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { manage = false }) { Text("تم") } })
    if (leave) ConfirmDialog(if (client.mine) "إغلاق الغرفة" else "الخروج من الغرفة",
        if (client.mine) "ستُغلق الغرفة لكل المتصلين وتُحذف ملفاتها المؤقتة. الرسائل تبقى محفوظة عندك." else "ستبقى الرسائل محفوظة في «غرفي المحفوظة».",
        confirm = if (client.mine) "إغلاق" else "خروج", onDismiss = { leave = false }) { TransferHub.leaveRoom(); nav.popBackStack() }
}

/** Read-only history of a saved room. */
@Composable
fun RoomHistoryScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val d by Stores.transfer.state.collectAsStateWithLifecycle()
    val r = d.rooms.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    ToolScaffold("🗂️ ${r.name}", { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (r.messages.isEmpty()) item { EmptyState("💬", "لا توجد رسائل محفوظة") }
            items(r.messages, key = { it.id + it.seq }) { m -> Bubble(m, m.fromId == d.myId, ctx, false) }
        }
    }
}
