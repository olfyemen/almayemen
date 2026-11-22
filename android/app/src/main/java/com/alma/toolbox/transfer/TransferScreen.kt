package com.alma.toolbox.transfer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.alma.toolbox.core.Stores
import com.alma.toolbox.tools.Qr
import com.alma.toolbox.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun UiVisible() {
    DisposableEffect(Unit) { TransferHub.uiVisible = true; onDispose { TransferHub.uiVisible = false } }
}

@Composable
fun JobsCard() {
    val jobs by TransferHub.jobs.collectAsStateWithLifecycle()
    if (jobs.isEmpty()) return
    SectionCard(title = "التحويلات") {
        jobs.takeLast(6).reversed().forEach { j ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(j.title, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    Text(when (j.state) { "done" -> "✓"; "error" -> "✗"; else -> "${NetUtil.fmtSize(j.done)} من ${NetUtil.fmtSize(j.total)}" },
                        style = MaterialTheme.typography.bodySmall, color = when (j.state) { "done" -> Good; "error" -> Bad; else -> MaterialTheme.colorScheme.outline })
                }
                if (j.state == "run") LinearProgressIndicator(progress = { if (j.total > 0) j.done.toFloat() / j.total else 0f }, modifier = Modifier.fillMaxWidth())
                if (j.state == "error") Text(j.error, color = Bad, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (jobs.any { it.state != "run" }) TextButton(onClick = { TransferHub.clearFinished() }) { Text("مسح المنتهية") }
    }
}

@Composable
fun QrBox(text: String, label: String) {
    val bmp = remember(text) { runCatching { Qr.encode(text, 600) }.getOrNull() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(200.dp).background(Color.White))
        Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

/** File / folder pickers that produce SendItems on a background thread. */
@Composable
fun rememberPickers(onItems: (List<SendItem>) -> Unit): Pair<() -> Unit, () -> Unit> {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l ->
        if (l.isNotEmpty()) scope.launch { onItems(withContext(Dispatchers.IO) { TransferHub.itemsFromUris(ctx, l) }) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) scope.launch {
            Toast.makeText(ctx, "جارٍ تجهيز المجلد…", Toast.LENGTH_SHORT).show()
            val items = withContext(Dispatchers.IO) { TransferHub.itemsFromTree(ctx, u) }
            if (items.isEmpty()) Toast.makeText(ctx, "المجلد فارغ", Toast.LENGTH_SHORT).show() else onItems(items)
        }
    }
    return Pair({ files.launch(arrayOf("*/*")) }, { folder.launch(null) })
}

@Composable
fun TransferScreen(nav: NavHostController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d by Stores.transfer.state.collectAsStateWithLifecycle()
    val offers by TransferHub.offers.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var editName by remember { mutableStateOf(false) }
    val storagePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    UiVisible()
    DisposableEffect(Unit) {
        onDispose {
            // economy: nothing running and not asked to stay ready → stop the server, discovery and wifi locks
            val idle = TransferHub.client.value == null && TransferHub.jobs.value.none { it.state == "run" } &&
                TransferHub.hotspot.value == null && TransferHub.offers.value.isEmpty()
            if (idle && !Stores.transfer.value.keepAlive) TransferHub.stop(ctx)
        }
    }
    LaunchedEffect(Unit) {
        TransferHub.start(ctx)
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    var url by remember { mutableStateOf(TransferHub.url()) }
    LaunchedEffect(Unit) { while (true) { url = TransferHub.url(); kotlinx.coroutines.delay(3000) } }

    ToolScaffold("نقل الملفات 📲", onBack, actions = {
        TextButton(onClick = { TransferHub.stop(ctx); onBack() }) { Text("إيقاف") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("📱", fontSize = 28.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.myName, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { editName = true })
                        Text(url ?: "غير متصل بواي فاي أو هوتسبوت", style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                    }
                    TextButton(onClick = { editName = true }) { Text("الاسم") }
                }
            }
            TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
                listOf("📤 إرسال", "💬 الغرف", "📶 هوتسبوت").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> DevicesTab()
                1 -> RoomsTab(nav)
                else -> HotspotTab()
            }
        }
    }
    offers.firstOrNull()?.let { o ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("📥 ${o.req.fromName} يريد إرسال") },
            text = {
                Column {
                    Text("${o.req.items.size} ملف · ${NetUtil.fmtSize(o.total)}", fontWeight = FontWeight.Bold)
                    o.req.items.take(6).forEach { Text("• ${it.path}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    if (o.req.items.size > 6) Text("و${o.req.items.size - 6} أخرى…", style = MaterialTheme.typography.bodySmall)
                    Text("تُحفظ في: التنزيلات/$SAVE_ROOT/${o.req.fromName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = { Button(onClick = { TransferHub.answerOffer(ctx, o.id, true) }) { Text("قبول") } },
            dismissButton = { TextButton(onClick = { TransferHub.answerOffer(ctx, o.id, false) }) { Text("رفض", color = Bad) } }
        )
    }
    if (editName) {
        var n by remember { mutableStateOf(d.myName) }
        AlertDialog(onDismissRequest = { editName = false }, title = { Text("اسم جهازك كما يراه الآخرون") },
            text = {
                Column {
                    OutlinedTextField(n, { n = it.take(30) }, singleLine = true)
                    SwitchRow("قبول الملفات تلقائياً دون سؤال", d.autoAccept) { v -> Stores.transfer.update { it.copy(autoAccept = v) } }
                    SwitchRow("البقاء جاهزاً للاستقبال بعد الخروج", d.keepAlive, "يستهلك بطارية أكثر؛ بدونه تتوقف الخدمة عند الخروج إن لم يكن هناك نقل أو غرفة") { v -> Stores.transfer.update { it.copy(keepAlive = v) } }
                }
            },
            confirmButton = { Button(onClick = { if (n.isNotBlank()) Stores.transfer.update { it.copy(myName = n.trim()) }; editName = false }) { Text("حفظ") } })
    }
}

@Composable
private fun DevicesTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by TransferHub.peers.collectAsStateWithLifecycle()
    var target by remember { mutableStateOf<Peer?>(null) }
    val status = remember { mutableStateMapOf<String, String>() }
    var manual by remember { mutableStateOf(false) }
    val (pickFiles, pickFolder) = rememberPickers { items ->
        val p = target ?: return@rememberPickers
        status[p.id] = "تجهيز ${items.size} ملف…"
        TransferHub.sendTo(ctx, p, items) { s -> status[p.id] = s }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { JobsCard() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("يبحث عن الأجهزة على نفس الشبكة…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { manual = true }) { Text("إضافة بالعنوان") }
            }
        }
        if (peers.isEmpty()) item {
            EmptyState("📡", "لا توجد أجهزة بعد", "افتح «نقل الملفات» في حقيبة الما على الهاتف الآخر، وتأكد أن الهاتفين على نفس الواي فاي أو أن أحدهما متصل بهوتسبوت الآخر.\nلمن ليس عنده التطبيق: أنشئ غرفة وأعطه رابطها.")
        }
        items(peers.values.sortedBy { it.name }, key = { it.id }) { p ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Soft), contentAlignment = Alignment.Center) { Text("📱", fontSize = 22.sp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.Bold)
                        Text(p.ip, style = MaterialTheme.typography.bodySmall)
                        status[p.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { target = p; pickFiles() }, modifier = Modifier.weight(1f)) { Text("📄 ملفات") }
                    OutlinedButton(onClick = { target = p; pickFolder() }, modifier = Modifier.weight(1f)) { Text("📁 مجلد") }
                }
            }
        }
    }
    if (manual) {
        var addr by remember { mutableStateOf("192.168.43.1") }
        var err by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { manual = false }, title = { Text("إضافة جهاز بالعنوان") },
            text = {
                Column {
                    Text("اكتب العنوان الظاهر أعلى شاشة «نقل الملفات» في الجهاز الآخر.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(addr, { addr = it }, singleLine = true, textStyle = LocalTextStyleLtr())
                    if (err.isNotEmpty()) Text(err, color = Bad)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val clean = addr.trim().removePrefix("http://").trimEnd('/')
                        val hostPort = if (clean.contains(':')) clean else "$clean:$HTTP_PORT"
                        val r = withContext(Dispatchers.IO) { runCatching { Http.get("http://$hostPort/api/info", Beacon.serializer(), 5000) } }
                        r.onSuccess { b ->
                            TransferHub.peers.value = TransferHub.peers.value + (b.id to Peer(b.id, b.n, hostPort.substringBefore(':'), hostPort.substringAfter(':').toInt(), b.r, Long.MAX_VALUE / 2))
                            manual = false
                        }.onFailure { err = "لم يُعثر على حقيبة الما في هذا العنوان" }
                    }
                }) { Text("إضافة") }
            }, dismissButton = { TextButton(onClick = { manual = false }) { Text("إلغاء") } })
    }
}

@Composable
private fun LocalTextStyleLtr() = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)

@Composable
private fun RoomsTab(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val d by Stores.transfer.state.collectAsStateWithLifecycle()
    val peers by TransferHub.peers.collectAsStateWithLifecycle()
    val current by TransferHub.client.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var joinFor by remember { mutableStateOf<Pair<Peer, RoomBrief>?>(null) }
    var delete by remember { mutableStateOf<SavedRoom?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun go(block: () -> RoomClient) {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block) }
            busy = false
            r.onSuccess { nav.navigate("transfer/room") }.onFailure { Toast.makeText(ctx, it.message ?: "تعذر الدخول", Toast.LENGTH_LONG).show() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        current?.let { c ->
            item {
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("transfer/room") }, colors = CardDefaults.cardColors(containerColor = Good.copy(alpha = .15f)), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("💬", fontSize = 26.sp); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text("أنت الآن في «${c.roomName}»", fontWeight = FontWeight.Bold); Text("اضغط للعودة إلى الغرفة", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item { Button(onClick = { create = true }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !busy) { Text("➕ إنشاء غرفة دردشة") } }
        val net = peers.values.flatMap { p -> p.rooms.map { p to it } }
        item { Text("غرف على الشبكة", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        if (net.isEmpty()) item { Text("لا توجد غرف مفتوحة حالياً على هذه الشبكة.", style = MaterialTheme.typography.bodySmall) }
        items(net, key = { it.second.id }) { (p, r) ->
            SectionCard(Modifier.clickable(enabled = !busy) { if (r.l) joinFor = p to r else go { TransferHub.connect(ctx, p.base, r.id, r.n, "") } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.l) "🔒" else "💬", fontSize = 24.sp); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) { Text(r.n, fontWeight = FontWeight.Bold); Text("عند ${p.name} · ${r.m} متصل", style = MaterialTheme.typography.bodySmall) }
                    Text("دخول", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (d.rooms.isNotEmpty()) item { Text("غرفي المحفوظة", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
        items(d.rooms, key = { "saved-" + it.id }) { r ->
            val live = net.firstOrNull { it.second.id == r.id }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (r.mine) "🏠 " else "💬 ") + r.name, fontWeight = FontWeight.Bold)
                        Text("${r.messages.size} رسالة" + (if (r.mine) " · غرفتي" + (if (r.password.isNotEmpty()) " · 🔒" else "") else "") + (if (live != null) " · متاحة الآن" else ""), style = MaterialTheme.typography.bodySmall)
                        r.messages.lastOrNull()?.let { Text(if (it.kind == "file") "📄 ${it.name}" else "${it.from}: ${it.text}", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.outline) }
                    }
                }
                Row {
                    if (r.mine) TextButton(onClick = { go { TransferHub.hostRoom(ctx, r.id, r.name, r.password) } }, enabled = !busy && current?.roomId != r.id) { Text("فتح الغرفة") }
                    else if (live != null) TextButton(onClick = { if (live.second.l) joinFor = live else go { TransferHub.connect(ctx, live.first.base, r.id, r.name, "") } }, enabled = !busy) { Text("انضمام") }
                    TextButton(onClick = { nav.navigate("transfer/history/${r.id}") }) { Text("السجل") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { delete = r }) { Text("حذف", color = Bad) }
                }
            }
        }
    }
    if (create) {
        var name by remember { mutableStateOf("غرفة ${d.myName.substringBefore(' ')}") }
        var pass by remember { mutableStateOf("") }
        var locked by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { create = false }, title = { Text("غرفة جديدة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("اسم الغرفة") }, singleLine = true)
                    SwitchRow("بكلمة سر", locked, if (locked) null else "مفتوحة لكل من على الشبكة") { locked = it }
                    if (locked) OutlinedTextField(pass, { pass = it.take(30) }, label = { Text("كلمة السر") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = { create = false; go { TransferHub.hostRoom(ctx, UUID.randomUUID().toString().take(8), name.trim().ifEmpty { "غرفة" }, if (locked) pass else "") } },
                    enabled = name.isNotBlank() && (!locked || pass.isNotBlank())) { Text("إنشاء") }
            }, dismissButton = { TextButton(onClick = { create = false }) { Text("إلغاء") } })
    }
    joinFor?.let { (p, r) ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { joinFor = null }, title = { Text("🔒 ${r.n}") },
            text = { OutlinedTextField(pass, { pass = it }, label = { Text("كلمة السر") }, singleLine = true) },
            confirmButton = { Button(onClick = { joinFor = null; go { TransferHub.connect(ctx, p.base, r.id, r.n, pass) } }) { Text("دخول") } },
            dismissButton = { TextButton(onClick = { joinFor = null }) { Text("إلغاء") } })
    }
    delete?.let { r ->
        ConfirmDialog("حذف الغرفة", "حذف «${r.name}» وكل رسائلها من هذا الهاتف؟ (الملفات المحفوظة في التنزيلات تبقى)", onDismiss = { delete = null }) {
            if (TransferHub.client.value?.roomId == r.id) TransferHub.leaveRoom()
            Stores.transfer.update { x -> x.copy(rooms = x.rooms.filter { it.id != r.id }) }
        }
    }
}

@Composable
private fun HotspotTab() {
    val ctx = LocalContext.current
    val hs by TransferHub.hotspot.collectAsStateWithLifecycle()
    val err by TransferHub.hotspotError.collectAsStateWithLifecycle()
    val client by TransferHub.client.collectAsStateWithLifecycle()
    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.any { it }) TransferHub.startHotspot(ctx) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(title = "هوتسبوت سريع للنقل") {
                Text("ينشئ شبكة واي فاي مؤقتة من هاتفك (بدون مشاركة إنترنت) ليتصل بها الآخرون بمسح الرمز، ثم يظهرون في «إرسال» و«الغرف».", style = MaterialTheme.typography.bodySmall)
                if (hs == null) Button(onClick = {
                    if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) TransferHub.startHotspot(ctx) else ask.launch(perms)
                }, modifier = Modifier.fillMaxWidth()) { Text("📶 تشغيل الهوتسبوت") }
                else OutlinedButton(onClick = { TransferHub.stopHotspot() }, modifier = Modifier.fillMaxWidth()) { Text("إيقاف الهوتسبوت") }
                if (err.isNotEmpty()) Text(err, color = Bad)
            }
        }
        hs?.let { h ->
            item {
                SectionCard(title = "١) اتصل بالشبكة") {
                    QrBox("WIFI:T:WPA;S:${h.ssid};P:${h.pass};;", "امسح بكاميرا الهاتف الآخر أو من إعدادات الواي فاي ← مسح رمز")
                    ValueRow("اسم الشبكة", h.ssid); ValueRow("كلمة السر", h.pass)
                }
            }
        }
        item {
            val url = TransferHub.url()
            SectionCard(title = if (hs != null) "٢) افتح الغرفة من المتصفح" else "المشاركة من المتصفح") {
                val c = client
                if (c != null && c.mine && url != null) {
                    QrBox("$url/?r=${c.roomId}", "لمن ليس عنده التطبيق: يفتح هذا الرابط في المتصفح\n$url")
                } else Text("أنشئ غرفة من تبويب «الغرف» ليظهر هنا رابطها ورمزها للمتصفح (آيفون، كمبيوتر…).", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text("يمكنك أيضاً استخدام هوتسبوت الهاتف العادي من الإعدادات: الأجهزة المتصلة به تظهر تلقائياً.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
