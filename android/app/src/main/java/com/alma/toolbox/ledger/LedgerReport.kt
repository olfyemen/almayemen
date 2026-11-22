package com.alma.toolbox.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.*
import java.time.LocalDate
import java.time.YearMonth

private val MonthNames = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")

@Composable
fun LedgerReportScreen(nav: NavHostController) {
    val d by Stores.ledger.state.collectAsStateWithLifecycle()
    var ym by remember { mutableStateOf(YearMonth.now()) }
    val prefix = ym.toString() // yyyy-MM
    val month = d.txs.filter { it.date.startsWith(prefix) }
    val names = d.accounts.associate { it.id to it.name }

    ToolScaffold("تقارير الحسابات 📊", { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { ym = ym.minusMonths(1) }) { Text("▶ السابق") }
                Text("${MonthNames[ym.monthValue - 1]} ${ym.year}", Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                TextButton(onClick = { ym = ym.plusMonths(1) }, enabled = ym < YearMonth.now()) { Text("التالي ◀") }
            }

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
                
            SectionCard(title = "حركة الشهر (${month.size} عملية)") {
                if (month.isEmpty()) Text("لا توجد عمليات في هذا الشهر")
                month.groupBy { it.currency }.forEach { (cur, l) ->
                    val on = l.filter { it.onHim }.sumOf { it.amount }; val fo = l.filter { !it.onHim }.sumOf { it.amount }
                    Text(cur, fontWeight = FontWeight.Bold)
                    ValueRow("إجمالي «عليه» (ديون جديدة)", fmtNum(on))
                    ValueRow("إجمالي «له» (مدفوعات/دائن)", fmtNum(fo))
                    ValueRow("صافي الشهر", balanceText(on - fo, cur))
                    HorizontalDivider()
                }
            }
            val active = month.groupBy { it.accountId }.entries.sortedByDescending { it.value.size }.take(5)
            if (active.isNotEmpty()) SectionCard(title = "الأكثر حركة هذا الشهر") {
                active.forEach { (id, l) -> ValueRow(names[id] ?: "—", "${l.size} عملية") { nav.navigate("ledger/account/$id") } }
            }
            // collection list: who owes the most right now
            val debtors = d.accounts.mapNotNull { a -> d.balance(a.id).filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { a to it } }
                .sortedByDescending { it.second.values.sum() }.take(10)
            SectionCard(title = "أكبر المبالغ المستحقة لك الآن") {
                if (debtors.isEmpty()) Text("لا توجد مبالغ مستحقة ✓")
                debtors.forEach { (a, b) -> ValueRow(a.name, b.entries.joinToString("، ") { "${fmtNum(it.value)} ${it.key}" }) { nav.navigate("ledger/account/${a.id}") } }
            }
            val cutoff = LocalDate.now().minusDays(30).toString()
            val stale = debtors.filter { (a, _) -> (d.txs.filter { it.accountId == a.id }.maxOfOrNull { it.date } ?: "") < cutoff }
            if (stale.isNotEmpty()) SectionCard(title = "⚠ عليها رصيد ولا حركة منذ 30 يوماً") {
                stale.forEach { (a, b) -> ValueRow(a.name, b.entries.joinToString("، ") { "${fmtNum(it.value)} ${it.key}" }) { nav.navigate("ledger/account/${a.id}") } }
                Text("افتح الحساب واضغط «إرسال» ثم «تذكير بالسداد».", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Optional weekly reminder about accounts that owe money and haven't moved. */
object LedgerRemind {
    fun schedule(ctx: android.content.Context) {
        if (!Stores.ledger.value.remindOverdue) { com.alma.toolbox.core.Alarms.cancel(ctx, "alma://ledgerremind/0"); return }
        val at = LocalDate.now().plusDays(7).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        com.alma.toolbox.core.Alarms.set(ctx, "alma://ledgerremind/0", at)
    }

    fun fire(ctx: android.content.Context) {
        val d = Stores.ledger.value
        val l = d.overdue()
        if (l.isNotEmpty() && com.alma.toolbox.core.Notify.canPost(ctx)) {
            val n = androidx.core.app.NotificationCompat.Builder(ctx, com.alma.toolbox.core.Notify.CH_INFO).setSmallIcon(com.alma.toolbox.R.drawable.ic_notify)
                .setContentTitle("📒 ${l.size} حساب عليه رصيد بلا حركة منذ ${d.overdueDays} يوماً")
                .setContentText(l.take(4).joinToString("، ") { it.name })
                .setAutoCancel(true).setContentIntent(com.alma.toolbox.core.Notify.openApp(ctx, "ledger/report", 51)).build()
            com.alma.toolbox.core.Notify.nm(ctx).notify(5101, n)
        }
        schedule(ctx)
    }
}
