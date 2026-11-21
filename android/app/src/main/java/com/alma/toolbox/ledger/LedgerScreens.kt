package com.alma.toolbox.ledger

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.alma.toolbox.core.Stores
import com.alma.toolbox.core.newId
import com.alma.toolbox.ui.*
import kotlinx.coroutines.launch
import java.time.LocalDate

private val GroupEmojis = listOf("👥", "🚚", "🏠", "🏪", "👷", "🧑‍🤝‍🧑", "💼", "🏦", "🛒", "📦")

@Composable
private fun BalanceChips(bal: Map<String, Double>, big: Boolean = false) {
    if (bal.isEmpty()) { Pill("متزن ✓", MaterialTheme.colorScheme.outline); return }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        bal.forEach { (c, v) ->
            val col = if (v > 0) Bad else Good
            if (big) Text(balanceText(v, c), color = col, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            else Pill(balanceText(v, c), col)
        }
    }
}

// ======================= HOME (groups) =======================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LedgerHome(nav: NavHostController, onBack: () -> Unit) {
    val d by Stores.ledger.state.collectAsStateWithLifecycle()
    var addGroup by remember { mutableStateOf(false) }
    var editGroup by remember { mutableStateOf<LGroup?>(null) }
    var delGroup by remember { mutableStateOf<LGroup?>(null) }
    var q by remember { mutableStateOf("") }
    var newAccount by remember { mutableStateOf(false) }

    ToolScaffold("سجل الحسابات 📒", onBack, actions = {
        TextButton(onClick = { nav.navigate("ledger/report") }) { Text("📊 تقارير") }
        if (d.pinHash.isNotEmpty()) IconButton(onClick = { LedgerLock.lock() }) { Text("🔒") }
        IconButton(onClick = { nav.navigate("ledger/settings") }) { Icon(Icons.Default.Settings, "الإعدادات") }
    }, fab = {
        ExtendedFloatingActionButton(onClick = { newAccount = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("حساب جديد") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(q, { q = it }, placeholder = { Text("بحث عن حساب بالاسم أو الرقم") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            }
            if (q.isNotBlank()) {
                val res = d.accounts.filter { it.name.contains(q.trim(), true) || it.phone.contains(q.trim()) }
                if (res.isEmpty()) item { Text("لا نتائج", Modifier.padding(8.dp)) }
                items(res, key = { it.id }) { a -> AccountRow(d, a) { nav.navigate("ledger/account/${a.id}") } }
            } else {
                item {
                    // sum receivables and payables separately (not netted across people)
                    val onThem = LinkedHashMap<String, Double>(); val forThem = LinkedHashMap<String, Double>()
                    d.accounts.forEach { a -> d.balance(a.id).forEach { (c, v) -> val m = if (v > 0) onThem else forThem; m[c] = (m[c] ?: 0.0) + v } }
                    val all = onThem + forThem
                    SectionCard(title = "الإجمالي") {
                        Text("${d.accounts.size} حساب · ${d.txs.size} عملية", style = MaterialTheme.typography.bodySmall)
                        if (all.isEmpty()) Text("لا توجد أرصدة")
                        onThem.forEach { (c, v) -> Text("لكم عند الآخرين: ${fmtNum(v)} $c", color = Bad, fontWeight = FontWeight.Bold) }
                        forThem.forEach { (c, v) -> Text("عليكم للآخرين: ${fmtNum(-v)} $c", color = Good, fontWeight = FontWeight.Bold) }
                        val bOn = d.toBase(onThem); val bFor = d.toBase(forThem)
                        if (bOn != null && bFor != null && (onThem.size + forThem.size) > 1) {
                            HorizontalDivider()
                            Text("بـ${d.baseCurrency}: لكم ${fmtNum(Math.round(bOn).toDouble())} · عليكم ${fmtNum(Math.round(-bFor).toDouble())} · الصافي ${balanceText(Math.round(bOn + bFor).toDouble(), d.baseCurrency).replace("عليه", "لكم").replace("له", "عليكم")}",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                items(d.groups, key = { it.id }) { g ->
                    val accs = d.groupAccounts(g.id)
                    Card(
                        shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .combinedClickable(onClick = { nav.navigate("ledger/group/${g.id}") }, onLongClick = { editGroup = g })
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text(g.emoji, fontSize = 24.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(g.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("${accs.size} حساب", style = MaterialTheme.typography.bodySmall)
                            }
                            BalanceChips(d.balances(accs.map { it.id }))
                        }
                    }
                }
                item { OutlinedButton(onClick = { addGroup = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text(" مجموعة جديدة (حساب أم)") } }
                item { Text("«${com.alma.toolbox.Credits.SLOGAN}»", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                item { Text("اضغط مطولاً على مجموعة لتعديلها أو حذفها", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
        }
    }
    if (addGroup) GroupDialog(null, { addGroup = false }) { n, e -> Ledger.addGroup(n, e); addGroup = false }
    editGroup?.let { g ->
        GroupDialog(g, { editGroup = null }, onDelete = { delGroup = g; editGroup = null }) { n, e -> Ledger.editGroup(g.copy(name = n, emoji = e)); editGroup = null }
    }
    delGroup?.let { g ->
        ConfirmDialog("حذف المجموعة", "سيتم حذف «${g.name}» وكل حساباتها (${d.groupAccounts(g.id).size}) وعملياتها نهائياً.", onDismiss = { delGroup = null }) { Ledger.deleteGroup(g.id) }
    }
    if (newAccount) AccountDialog(null, d.groups.firstOrNull()?.id ?: 0, d.groups, { newAccount = false }) { a ->
        Ledger.saveAccount(a); newAccount = false; nav.navigate("ledger/account/${a.id}")
    }
}

@Composable
private fun GroupDialog(g: LGroup?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(g?.name ?: "") }
    var emoji by remember { mutableStateOf(g?.emoji ?: "👥") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (g == null) "مجموعة جديدة" else "تعديل المجموعة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم المجموعة (مثلاً: العملاء)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GroupEmojis.forEach { e ->
                        Box(Modifier.size(40.dp).clip(CircleShape).background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { emoji = e }, contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) }
                    }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("حذف المجموعة", color = Bad) }
            }
        },
        confirmButton = { Button(onClick = { onSave(name.trim(), emoji) }, enabled = name.isNotBlank()) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun AccountRow(d: LedgerData, a: LAccount, onClick: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Soft), contentAlignment = Alignment.Center) {
                Text(a.name.take(1), fontWeight = FontWeight.Bold, color = Accent, fontSize = 18.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.name, fontWeight = FontWeight.Bold)
                if (a.phone.isNotBlank()) Text(a.phone, style = MaterialTheme.typography.bodySmall)
            }
            BalanceChips(d.balance(a.id))
        }
    }
}

/** Contact picker that needs no contacts permission. */
@Composable
fun rememberContactPicker(onPicked: (name: String, phone: String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) onPicked(c.getString(1) ?: "", c.getString(0) ?: "")
            }
        }
    }
    return { l.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
}

@Composable
fun AccountDialog(a: LAccount?, groupId: Long, groups: List<LGroup>, onDismiss: () -> Unit, onSave: (LAccount) -> Unit) {
    var name by remember { mutableStateOf(a?.name ?: "") }
    var phone by remember { mutableStateOf(a?.phone ?: "") }
    var note by remember { mutableStateOf(a?.note ?: "") }
    var gid by remember { mutableLongStateOf(a?.groupId ?: groupId) }
    val pick = rememberContactPicker { n, p -> if (name.isBlank()) name = n; phone = p }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (a == null) runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (a == null) "حساب جديد" else "تعديل الحساب") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
                OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    trailingIcon = { IconButton(onClick = pick) { Icon(Icons.Default.Person, "من جهات الاتصال") } },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("ملاحظة") }, modifier = Modifier.fillMaxWidth())
                Text("المجموعة", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g -> FilterChip(gid == g.id, { gid = g.id }, label = { Text("${g.emoji} ${g.name}") }) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave((a ?: LAccount(newId(), gid, "")).copy(name = name.trim(), phone = phone.trim(), note = note.trim(), groupId = gid))
            }, enabled = name.isNotBlank() && groups.any { it.id == gid }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

// ======================= GROUP (accounts) =======================
@Composable
fun LedgerGroupScreen(nav: NavHostController, groupId: Long) {
    val d by Stores.ledger.state.collectAsStateWithLifecycle()
    val g = d.groups.firstOrNull { it.id == groupId }
    var add by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    if (g == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val accs = d.groupAccounts(groupId).filter { q.isBlank() || it.name.contains(q.trim(), true) || it.phone.contains(q.trim()) }
        .let { l -> when (sort) { 0 -> l.sortedByDescending { d.lastActivity(it.id) }; 1 -> l.sortedBy { it.name }; else -> l.sortedByDescending { d.balance(it.id).values.sumOf { v -> Math.abs(v) } } } }

    ToolScaffold("${g.emoji} ${g.name}", { nav.popBackStack() }, fab = {
        ExtendedFloatingActionButton(onClick = { add = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("حساب جديد") })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionCard { Text("رصيد المجموعة", fontWeight = FontWeight.Bold); BalanceChips(d.balances(d.groupAccounts(groupId).map { it.id }), big = true) }
            }
            item {
                OutlinedTextField(q, { q = it }, placeholder = { Text("بحث") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("الأحدث", "الاسم", "الأكبر رصيداً").forEachIndexed { i, s -> FilterChip(sort == i, { sort = i }, label = { Text(s) }) }
                }
            }
            if (accs.isEmpty()) item { EmptyState("📒", "لا توجد حسابات", "أضف أول حساب في «${g.name}»") }
            items(accs, key = { it.id }) { a -> AccountRow(d, a) { nav.navigate("ledger/account/${a.id}") } }
        }
    }
    if (add) AccountDialog(null, groupId, d.groups, { add = false }) { a -> Ledger.saveAccount(a); add = false; nav.navigate("ledger/account/${a.id}") }
}

// ======================= ACCOUNT (transactions + fast entry) =======================
@Composable
fun LedgerAccountScreen(nav: NavHostController, accountId: Long) {
    val ctx = LocalContext.current
    val d by Stores.ledger.state.collectAsStateWithLifecycle()
    val a = d.accounts.firstOrNull { it.id == accountId }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    var send by remember { mutableStateOf(false) }
    var editTx by remember { mutableStateOf<LTx?>(null) }
    var q by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableIntStateOf(0) } // 0 all, 1 عليه, 2 له
    if (a == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }

    val txs = d.txs.filter { it.accountId == accountId }.sortedWith(compareBy({ it.date }, { it.created }))
    // running balance per currency, newest first for display
    val running = remember(txs) {
        val m = HashMap<String, Double>(); txs.map { t -> m[t.currency] = (m[t.currency] ?: 0.0) + if (t.onHim) t.amount else -t.amount; t to m[t.currency]!! }.reversed()
    }

    ToolScaffold(a.name, { nav.popBackStack() }, snackbar = snack, actions = {
        IconButton(onClick = { q = if (q == null) "" else null }) { Icon(Icons.Default.Search, "بحث في العمليات") }
        IconButton(onClick = { send = true }) { Icon(Icons.Default.Send, "إرسال الحساب") }
        IconButton(onClick = { edit = true }) { Icon(Icons.Default.Edit, "تعديل") }
        IconButton(onClick = { del = true }) { Icon(Icons.Default.Delete, "حذف") }
    }, bottomBar = {
        QuickEntry(d) { t ->
            val tx = t.copy(accountId = accountId)
            Ledger.addTx(tx)
            scope.launch {
                snack.currentSnackbarData?.dismiss()
                val r = snack.showSnackbar("تمت إضافة ${if (tx.onHim) "عليه" else "له"} ${fmtNum(tx.amount)} ${tx.currency}", "تراجع", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) Ledger.deleteTx(tx.id)
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("الرصيد", style = MaterialTheme.typography.bodySmall)
                            BalanceChips(d.balance(accountId), big = true)
                            d.balance(accountId).takeIf { it.size > 1 || (it.isNotEmpty() && it.keys.first() != d.baseCurrency) }?.let { b ->
                                d.toBase(b)?.let { Text("≈ " + balanceText(Math.round(it).toDouble(), d.baseCurrency), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        FilledTonalButton(onClick = { send = true }) { Text("📤 إرسال") }
                    }
                    if (a.phone.isNotBlank()) Text("📞 ${a.phone}", style = MaterialTheme.typography.bodySmall)
                    if (a.note.isNotBlank()) Text(a.note, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (txs.isEmpty()) item { EmptyState("✍️", "لا توجد عمليات بعد", "اكتب المبلغ في الأسفل ثم اضغط «عليه» أو «له»") }
            if (q != null) item {
                OutlinedTextField(q!!, { q = it }, placeholder = { Text("بحث بالبيان أو المبلغ أو التاريخ") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("الكل", "عليه", "له").forEachIndexed { i, l -> FilterChip(kind == i, { kind = i }, label = { Text(l) }) }
                }
            }
            val shown = running.filter { (t, _) ->
                val qq = q?.trim().orEmpty()
                (kind == 0 || (kind == 1) == t.onHim) &&
                    (qq.isEmpty() || t.note.contains(qq, true) || fmtNum(t.amount).replace(",", "").contains(qq.replace(",", "")) || fmtDate(t.date).contains(qq) || t.currency.contains(qq))
            }
            if (q != null) item {
                val on = shown.filter { it.first.onHim }.groupBy { it.first.currency }.mapValues { e -> e.value.sumOf { it.first.amount } }
                val fo = shown.filter { !it.first.onHim }.groupBy { it.first.currency }.mapValues { e -> e.value.sumOf { it.first.amount } }
                Text("${shown.size} عملية" + on.entries.joinToString("") { " · عليه ${fmtNum(it.value)} ${it.key}" } + fo.entries.joinToString("") { " · له ${fmtNum(it.value)} ${it.key}" },
                    style = MaterialTheme.typography.bodySmall)
            }
            items(shown, key = { it.first.id }) { (t, bal) ->
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { editTx = t }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(if (t.onHim) Bad else Good))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.note.ifBlank { if (t.onHim) "عليه" else "له" }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text("${fmtDate(t.date)} · الرصيد: ${balanceText(bal, t.currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtNum(t.amount), fontWeight = FontWeight.ExtraBold, color = if (t.onHim) Bad else Good, fontSize = 17.sp)
                            Text((if (t.onHim) "عليه · " else "له · ") + t.currency, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (edit) AccountDialog(a, a.groupId, d.groups, { edit = false }) { Ledger.saveAccount(it); edit = false }
    if (del) ConfirmDialog("حذف الحساب", "حذف «${a.name}» وكل عملياته (${txs.size}) نهائياً؟", onDismiss = { del = false }) { Ledger.deleteAccount(a.id); nav.popBackStack() }
    if (send) SendDialog(d, a) { send = false }
    editTx?.let { t -> TxDialog(d, t, { editTx = null }) { editTx = null } }
    LaunchedEffect(Unit) { if (Stores.ledger.value.currencies.isEmpty()) Toast.makeText(ctx, "أضف عملة من الإعدادات", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun CurrencyPicker(d: LedgerData, cur: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(cur) }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) })
        DropdownMenu(open, { open = false }) { d.currencies.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { onPick(c); open = false }) } }
    }
}

@Composable
private fun QuickEntry(d: LedgerData, onAdd: (LTx) -> Unit) {
    val ctx = LocalContext.current
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var cur by remember(d.lastCurrency) { mutableStateOf(d.currencies.firstOrNull { it == d.lastCurrency } ?: d.currencies.firstOrNull() ?: "ريال") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val parsed = Ledger.parseAmount(amount)
    val focus = remember { FocusRequester() }
    fun submit(onHim: Boolean) {
        val v = parsed ?: return
        onAdd(LTx(newId(), 0, v, onHim, cur, date.toString(), note.trim()))
        amount = ""; note = ""
        runCatching { focus.requestFocus() }
    }
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(amount, { amount = it }, placeholder = { Text("المبلغ") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    supportingText = if (parsed != null && amount.any { it in "+-*/×÷" }) ({ Text("= ${fmtNum(parsed)}") }) else null,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f).focusRequester(focus))
                CurrencyPicker(d, cur) { cur = it }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(note, { note = it }, placeholder = { Text("البيان (اختياري)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { }),
                    modifier = Modifier.weight(1f))
                AssistChip(onClick = { pickDate(ctx, date) { date = it } }, label = { Text(if (date == LocalDate.now()) "اليوم" else date.format(dateFmt)) },
                    leadingIcon = { Icon(Icons.Default.DateRange, null, Modifier.size(18.dp)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { submit(true) }, enabled = parsed != null, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Bad)) { Text("عليه  ➖", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Button(onClick = { submit(false) }, enabled = parsed != null, modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Good)) { Text("له  ➕", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun TxDialog(d: LedgerData, t: LTx, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var amount by remember { mutableStateOf(fmtNum(t.amount).replace(",", "")) }
    var note by remember { mutableStateOf(t.note) }
    var onHim by remember { mutableStateOf(t.onHim) }
    var cur by remember { mutableStateOf(t.currency) }
    var date by remember { mutableStateOf(runCatching { LocalDate.parse(t.date) }.getOrDefault(LocalDate.now())) }
    var confirm by remember { mutableStateOf(false) }
    val parsed = Ledger.parseAmount(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل العملية") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedPick("عليه", onHim, { onHim = true }, Modifier.weight(1f))
                    OutlinedPick("له", !onHim, { onHim = false }, Modifier.weight(1f))
                }
                OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("البيان") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurrencyPicker(d, cur) { cur = it }
                    AssistChip(onClick = { pickDate(ctx, date) { date = it } }, label = { Text(date.format(dateFmt)) })
                }
                TextButton(onClick = { confirm = true }) { Text("حذف العملية", color = Bad) }
            }
        },
        confirmButton = {
            Button(onClick = { Ledger.saveTx(t.copy(amount = parsed!!, note = note.trim(), onHim = onHim, currency = cur, date = date.toString())); onDone() }, enabled = parsed != null) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
    if (confirm) ConfirmDialog("حذف العملية", "حذف هذه العملية؟", onDismiss = { confirm = false }) { Ledger.deleteTx(t.id); onDone() }
}

// ======================= SEND =======================
@Composable
private fun SendDialog(d: LedgerData, a: LAccount, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var pdf by remember { mutableStateOf(d.sendMode == "pdf") }
    var useOwn by remember { mutableStateOf(a.phone.isNotBlank()) }
    var other by remember { mutableStateOf("") }
    val pick = rememberContactPicker { _, p -> other = p; useOwn = false }
    val raw = if (useOwn) a.phone else other
    val phone = if (raw.isBlank()) "" else Ledger.normalizePhone(raw, d.countryCode)
    var remind by remember { mutableStateOf(false) }
    var period by remember { mutableIntStateOf(0) } // 0 all, 1 this month, 2 last month, 3 custom
    var from by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var to by remember { mutableStateOf(LocalDate.now()) }
    val range: Pair<LocalDate?, LocalDate?> = when (period) {
        1 -> LocalDate.now().withDayOfMonth(1) to LocalDate.now()
        2 -> LocalDate.now().minusMonths(1).withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        3 -> from to to
        else -> null to null
    }
    val text = remember(d, a, remind) { if (remind) LedgerShare.reminderText(d, a) else LedgerShare.lastAndBalanceText(d, a) }
    val pdfFile = { LedgerShare.buildPdf(ctx, d, a, range.first, range.second) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إرسال حساب ${a.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ماذا تريد أن ترسل؟", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedPick("آخر عملية والرصيد", !pdf && !remind, { pdf = false; remind = false }, Modifier.weight(1f))
                    OutlinedPick("تذكير بالسداد", !pdf && remind, { pdf = false; remind = true }, Modifier.weight(1f))
                }
                OutlinedPick("كشف حساب PDF", pdf, { pdf = true }, Modifier.fillMaxWidth())
                if (pdf) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("كل العمليات", "هذا الشهر", "الشهر الماضي", "فترة محددة").forEachIndexed { i, l -> FilterChip(period == i, { period = i }, label = { Text(l) }) }
                    }
                    if (period == 3) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = { pickDate(ctx, from) { from = it } }, label = { Text("من ${from.format(dateFmt)}") })
                        AssistChip(onClick = { pickDate(ctx, to) { to = it } }, label = { Text("إلى ${to.format(dateFmt)}") })
                    }
                    if (period != 0) Text("يُضاف «رصيد سابق» بما قبل الفترة.", style = MaterialTheme.typography.bodySmall)
                }
                if (!pdf) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
                Text("إلى رقم", fontWeight = FontWeight.Bold)
                if (a.phone.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { useOwn = true }) {
                    RadioButton(useOwn, { useOwn = true }); Text("رقم صاحب الحساب: ${a.phone}")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { useOwn = false }) {
                    RadioButton(!useOwn, { useOwn = false }); Text("رقم آخر")
                }
                if (!useOwn) OutlinedTextField(other, { other = it }, label = { Text("رقم الهاتف") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    trailingIcon = { IconButton(onClick = pick) { Icon(Icons.Default.Person, "جهات الاتصال") } }, modifier = Modifier.fillMaxWidth())
                if (phone.isNotBlank()) Text("سيُرسل إلى: +$phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        if (pdf) LedgerShare.sendPdf(ctx, pdfFile(), phone.ifBlank { null }, whatsapp = true)
                        else if (phone.isBlank()) LedgerShare.shareText(ctx, text) else LedgerShare.sendTextWhatsApp(ctx, phone, text)
                        onDismiss()
                    }, colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF25D366))) { Text("واتساب") }
                    if (!pdf) OutlinedButton(onClick = { if (phone.isNotBlank()) LedgerShare.sendSms(ctx, phone, text); onDismiss() }, enabled = phone.isNotBlank()) { Text("SMS") }
                }
                TextButton(onClick = {
                    if (pdf) LedgerShare.sendPdf(ctx, pdfFile(), null, whatsapp = false) else LedgerShare.shareText(ctx, text)
                    onDismiss()
                }) { Text("مشاركة بتطبيق آخر…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

// ======================= SETTINGS =======================
@Composable
fun LedgerSettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val d by Stores.ledger.state.collectAsStateWithLifecycle()
    var newCur by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(Stores.ledger.exportText().toByteArray()) }
            Toast.makeText(ctx, "تم حفظ النسخة الاحتياطية ✓", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(ctx, "فشل الحفظ", Toast.LENGTH_SHORT).show() }
    }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val merger = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val other = com.alma.toolbox.core.AppJson.decodeFromString(LedgerData.serializer(), ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
            Ledger.merge(other)
        }.onSuccess { (g, a, t) -> Toast.makeText(ctx, "أُضيف: $a حساب، $t عملية، $g مجموعة ✓", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(ctx, "الملف ليس ملف حسابات صالحاً", Toast.LENGTH_SHORT).show() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            .onSuccess { pendingImport = it }.onFailure { Toast.makeText(ctx, "تعذرت قراءة الملف", Toast.LENGTH_SHORT).show() }
    }

    ToolScaffold("إعدادات سجل الحسابات", { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "بياناتك") {
                OutlinedTextField(d.owner, { v -> Stores.ledger.update { it.copy(owner = v) } }, label = { Text("اسمك أو اسم المحل (يظهر في الكشف)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(d.countryCode, { v -> Stores.ledger.update { it.copy(countryCode = v.filter(Char::isDigit)) } }, label = { Text("مفتاح الدولة الافتراضي") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("يُضاف للأرقام المحلية عند الإرسال، مثلاً 967 لليمن") })
            }
            SectionCard(title = "طريقة الإرسال الافتراضية") {
                listOf("ask" to "أختار عند كل إرسال", "last" to "آخر عملية والرصيد (نص)", "pdf" to "كشف حساب كامل PDF").forEach { (k, l) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { Stores.ledger.update { it.copy(sendMode = k) } }) {
                        RadioButton(d.sendMode == k, { Stores.ledger.update { it.copy(sendMode = k) } }); Text(l)
                    }
                }
            }
            SectionCard(title = "العملات") {
                d.currencies.forEachIndexed { i, c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c, Modifier.weight(1f))
                        if (i > 0) TextButton(onClick = { Stores.ledger.update { x -> x.copy(currencies = x.currencies.toMutableList().also { l -> l.removeAt(i); l.add(i - 1, c) }) } }) { Text("⬆") }
                        val used = d.txs.any { it.currency == c }
                        TextButton(onClick = { Stores.ledger.update { x -> x.copy(currencies = x.currencies - c) } }, enabled = !used && d.currencies.size > 1) { Text(if (used) "مستخدمة" else "حذف") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newCur, { newCur = it }, placeholder = { Text("عملة جديدة") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { val c = newCur.trim(); if (c.isNotEmpty() && c !in d.currencies) Stores.ledger.update { it.copy(currencies = it.currencies + c) }; newCur = "" }) { Text("إضافة") }
                }
            }
            SectionCard(title = "🔒 القفل (اختياري)") {
                var pinDlg by remember { mutableStateOf(false) }
                Text(if (d.pinHash.isEmpty()) "سجل الحسابات مفتوح بدون رمز." else "مقفل برمز من ${d.pinLength} أرقام، ويُقفل تلقائياً بعد دقيقة من الخروج.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { pinDlg = true }) { Text(if (d.pinHash.isEmpty()) "تفعيل القفل برمز" else "تغيير الرمز أو إزالته") }
                if (d.pinHash.isNotEmpty() && bioAvailable(ctx)) SwitchRow("الفتح بالبصمة أيضاً", d.lockBio) { v -> Stores.ledger.update { it.copy(lockBio = v) } }
                if (pinDlg) PinSetupDialog(d) { pinDlg = false }
            }
            SectionCard(title = "💱 عملة موحّدة للإجمالي (اختياري)") {
                SwitchRow("إظهار الأرصدة بعملة واحدة", d.baseCurrency.isNotEmpty(), "بأسعار صرف تُدخلها أنت") { on ->
                    Stores.ledger.update { it.copy(baseCurrency = if (on) it.currencies.firstOrNull() ?: "" else "") }
                }
                if (d.baseCurrency.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.currencies.forEach { c -> FilterChip(d.baseCurrency == c, { Stores.ledger.update { it.copy(baseCurrency = c) } }, label = { Text(c) }) }
                    }
                    d.currencies.filter { it != d.baseCurrency }.forEach { c ->
                        var txt by remember(c, d.baseCurrency) { mutableStateOf(d.rates[c]?.let { fmtNum(it).replace(",", "") } ?: "") }
                        OutlinedTextField(txt, { v -> txt = v; Ledger.parseAmount(v)?.let { r -> Stores.ledger.update { it.copy(rates = it.rates + (c to r)) } } },
                            label = { Text("1 $c = ؟ ${d.baseCurrency}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            SectionCard(title = "⏰ تذكير بالمتأخرين (اختياري)") {
                SwitchRow("تنبيه أسبوعي بمن عليه رصيد بلا حركة", d.remindOverdue) { v -> Stores.ledger.update { it.copy(remindOverdue = v) }; LedgerRemind.schedule(ctx) }
                if (d.remindOverdue) Stepper("بلا حركة منذ", d.overdueDays, 7..180, 7, "يوم", listOf(15, 30, 60, 90)) { v -> Stores.ledger.update { it.copy(overdueDays = v) } }
            }
            SectionCard(title = "📲 الحسابات على هاتف آخر") {
                Text("أرسل نسخة من الحسابات لهاتف آخر (عبر «نقل الملفات» أو واتساب)، ثم افتحها هناك بـ«استيراد ودمج»: تُضاف الحسابات والعمليات الجديدة فقط دون حذف شيء.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val f = java.io.File(java.io.File(ctx.cacheDir, "shared").apply { mkdirs() }, "حسابات-الما-${LocalDate.now()}.json")
                        f.writeText(Stores.ledger.exportText())
                        val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("application/json")
                            .putExtra(android.content.Intent.EXTRA_STREAM, u).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "إرسال الحسابات"))
                    }) { Text("إرسال نسخة") }
                    OutlinedButton(onClick = { merger.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("استيراد ودمج") }
                }
            }
            SectionCard(title = "النسخ الاحتياطي") {
                Text("احفظ نسخة من كل الحسابات في ملف، واسترجعها على هاتف آخر.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exporter.launch("حسابات-الما-${LocalDate.now()}.json") }) { Text("حفظ نسخة") }
                    OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("استرجاع") }
                }
            }
        }
    }
    pendingImport?.let { txt ->
        ConfirmDialog("استرجاع النسخة", "سيتم استبدال كل الحسابات الحالية بمحتوى الملف. متابعة؟", confirm = "استرجاع", onDismiss = { pendingImport = null }) {
            runCatching { Stores.ledger.importText(txt) }
                .onSuccess { Toast.makeText(ctx, "تم الاسترجاع ✓", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "الملف غير صالح", Toast.LENGTH_SHORT).show() }
        }
    }
}
