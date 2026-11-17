package com.alma.toolbox.ledger

import com.alma.toolbox.core.Stores
import com.alma.toolbox.core.newId
import com.alma.toolbox.ui.fmtNum
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class LGroup(val id: Long, val name: String, val emoji: String = "👥")

@Serializable
data class LAccount(val id: Long, val groupId: Long, val name: String, val phone: String = "", val note: String = "", val created: Long = System.currentTimeMillis())

/** onHim = true means «عليه» (he owes us); false means «له» (we owe him). */
@Serializable
data class LTx(
    val id: Long, val accountId: Long, val amount: Double, val onHim: Boolean,
    val currency: String, val date: String = LocalDate.now().toString(), val note: String = "",
    val created: Long = System.currentTimeMillis(),
)

@Serializable
data class LedgerData(
    val groups: List<LGroup> = listOf(LGroup(1, "العملاء", "👥"), LGroup(2, "الموردون", "🚚"), LGroup(3, "شخصي", "🏠")),
    val accounts: List<LAccount> = emptyList(),
    val txs: List<LTx> = emptyList(),
    val currencies: List<String> = listOf("ريال يمني", "ريال سعودي", "دولار"),
    val lastCurrency: String = "ريال يمني",
    val owner: String = "",
    val countryCode: String = "967",
    /** ask | last | pdf */
    val sendMode: String = "ask",
)

/** currency -> balance (positive = عليه) */
fun LedgerData.balances(accountIds: Collection<Long>): Map<String, Double> {
    val set = accountIds.toHashSet()
    val m = LinkedHashMap<String, Double>()
    txs.forEach { if (it.accountId in set) m[it.currency] = (m[it.currency] ?: 0.0) + if (it.onHim) it.amount else -it.amount }
    return m.filterValues { Math.abs(it) > 0.0001 }
}

fun LedgerData.balance(accountId: Long) = balances(listOf(accountId))
fun LedgerData.groupAccounts(gid: Long) = accounts.filter { it.groupId == gid }
fun LedgerData.lastActivity(aid: Long) = txs.filter { it.accountId == aid }.maxOfOrNull { it.created } ?: accounts.firstOrNull { it.id == aid }?.created ?: 0

fun balanceText(v: Double, cur: String, you: Boolean = false): String = when {
    v > 0.0001 -> (if (you) "عليك " else "عليه ") + fmtNum(v) + " " + cur
    v < -0.0001 -> (if (you) "لك " else "له ") + fmtNum(-v) + " " + cur
    else -> "متزن"
}

object Ledger {
    fun addGroup(name: String, emoji: String) = Stores.ledger.update { it.copy(groups = it.groups + LGroup(newId(), name, emoji)) }
    fun editGroup(g: LGroup) = Stores.ledger.update { d -> d.copy(groups = d.groups.map { if (it.id == g.id) g else it }) }
    fun deleteGroup(id: Long) = Stores.ledger.update { d ->
        val ids = d.accounts.filter { it.groupId == id }.map { it.id }.toSet()
        d.copy(groups = d.groups.filter { it.id != id }, accounts = d.accounts.filter { it.groupId != id }, txs = d.txs.filter { it.accountId !in ids })
    }
    fun saveAccount(a: LAccount) = Stores.ledger.update { d -> d.copy(accounts = d.accounts.filter { it.id != a.id } + a) }
    fun deleteAccount(id: Long) = Stores.ledger.update { d -> d.copy(accounts = d.accounts.filter { it.id != id }, txs = d.txs.filter { it.accountId != id }) }
    fun addTx(t: LTx) = Stores.ledger.update { d -> d.copy(txs = d.txs + t, lastCurrency = t.currency) }
    fun saveTx(t: LTx) = Stores.ledger.update { d -> d.copy(txs = d.txs.map { if (it.id == t.id) t else it }) }
    fun deleteTx(id: Long) = Stores.ledger.update { d -> d.copy(txs = d.txs.filter { it.id != id }) }

    /** Accepts Arabic digits and simple sums like 500+250*2 */
    fun parseAmount(input: String): Double? {
        val s = input.map { c ->
            when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); '٫' -> '.'; '×' -> '*'; '÷' -> '/'; else -> c }
        }.joinToString("").replace(",", "").replace("،", "").replace(" ", "")
        if (s.isEmpty()) return null
        return try { Expr(s).parse().takeIf { it.isFinite() && it > 0 } } catch (e: Exception) { null }
    }

    private class Expr(val s: String) {
        var i = 0
        fun parse(): Double { val v = sum(); require(i == s.length); return v }
        fun sum(): Double { var v = prod(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = prod(); v = if (op == '+') v + r else v - r }; return v }
        fun prod(): Double { var v = num(); while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = num(); v = if (op == '*') v * r else v / r }; return v }
        fun num(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = sum(); require(s[i++] == ')'); return v }
            val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }

    fun normalizePhone(raw: String, cc: String): String {
        var p = raw.filter { it.isDigit() || it == '+' }.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }.joinToString("")
        p = p.replace("+", "")
        if (p.startsWith("00")) return p.substring(2)
        if (p.startsWith("0")) return cc + p.trimStart('0')
        if (p.length <= 9) return cc + p
        return p
    }
}
