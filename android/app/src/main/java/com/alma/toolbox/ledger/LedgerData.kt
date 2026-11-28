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
    /** double entry: id of the mirrored transaction in the counter account (0 = none) */
    val linkId: Long = 0,
    /** optional due date (yyyy-MM-dd): a reminder that day while the balance is still open */
    val due: String = "",
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
    /** optional lock: SHA-256 of the PIN, empty = no lock */
    val pinHash: String = "",
    val pinLength: Int = 4,
    val lockBio: Boolean = true,
    /** optional single currency for totals; empty = off. rates[c] = value of 1 c in baseCurrency */
    val baseCurrency: String = "",
    val rates: Map<String, Double> = emptyMap(),
    /** optional weekly reminder about balances with no movement */
    val remindOverdue: Boolean = false,
    val overdueDays: Int = 30,
    /** simple interface (default): amount, note, له/عليه — today's date */
    val simpleUi: Boolean = true,
    /** optional: pick the counter account and the opposite entry is added there automatically */
    val doubleEntry: Boolean = false,
)

/** Sum of a multi-currency balance in the base currency; null when off or a rate is missing. */
fun LedgerData.toBase(bal: Map<String, Double>): Double? {
    if (baseCurrency.isEmpty()) return null
    var sum = 0.0
    bal.forEach { (c, v) -> sum += v * (if (c == baseCurrency) 1.0 else rates[c]?.takeIf { it > 0 } ?: return null) }
    return sum
}

/** Accounts that owe money and have had no movement for [LedgerData.overdueDays]. */
fun LedgerData.overdue(today: java.time.LocalDate = java.time.LocalDate.now()): List<LAccount> {
    val cutoff = today.minusDays(overdueDays.toLong()).toString()
    return accounts.filter { a -> balance(a.id).values.any { it > 0 } && (txs.filter { it.accountId == a.id }.maxOfOrNull { it.date } ?: "") < cutoff }
}

/** currency -> balance (positive = عليه) */
fun LedgerData.balances(accountIds: Collection<Long>): Map<String, Double> {
    val set = accountIds.toHashSet()
    val m = LinkedHashMap<String, Double>()
    txs.forEach { if (it.accountId in set) m[it.currency] = (m[it.currency] ?: 0.0) + if (it.onHim) it.amount else -it.amount }
    return m.filterValues { Math.abs(it) > 0.0001 }
}

fun LedgerData.balance(accountId: Long) = balances(listOf(accountId))

/** Transactions whose due date came and whose account still has an open balance in the same direction. */
fun LedgerData.dueNow(today: LocalDate = LocalDate.now()): List<LTx> = txs.filter { t ->
    t.due.isNotEmpty() && t.due <= today.toString() && run {
        val b = balance(t.accountId)[t.currency] ?: 0.0
        if (t.onHim) b > 0.0001 else b < -0.0001
    }
}
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

    /**
     * Adds [t]; with [counter] it also adds the opposite entry there (له here → عليه there, and the reverse),
     * linked so that editing or deleting one changes both.
     */
    fun addWithCounter(t: LTx, counter: LAccount?): LTx {
        if (counter == null || counter.id == t.accountId) { addTx(t); return t }
        val d = Stores.ledger.value
        val me = d.accounts.firstOrNull { it.id == t.accountId }?.name ?: ""
        val twinId = newId()
        val main = t.copy(linkId = twinId, note = t.note.ifBlank { (if (t.onHim) "لـ " else "من ") + counter.name })
        val twin = LTx(twinId, counter.id, t.amount, !t.onHim, t.currency, t.date, t.note.ifBlank { (if (t.onHim) "من " else "لـ ") + me }, linkId = t.id)
        Stores.ledger.update { x -> x.copy(txs = x.txs + main + twin, lastCurrency = t.currency) }
        return main
    }

    fun twinOf(d: LedgerData, t: LTx): LTx? = if (t.linkId == 0L) null else d.txs.firstOrNull { it.id == t.linkId }

    fun saveTx(t: LTx) = Stores.ledger.update { d ->
        d.copy(txs = d.txs.map {
            when {
                it.id == t.id -> t
                t.linkId != 0L && it.id == t.linkId -> it.copy(amount = t.amount, onHim = !t.onHim, currency = t.currency, date = t.date)
                else -> it
            }
        })
    }

    fun deleteTx(id: Long) = Stores.ledger.update { d ->
        val link = d.txs.firstOrNull { it.id == id }?.linkId ?: 0L
        d.copy(txs = d.txs.filter { it.id != id && (link == 0L || it.id != link) })
    }

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

    /** Adds what [other] has and this phone doesn't (by id). Nothing is deleted or overwritten. */
    fun merge(other: LedgerData): Triple<Int, Int, Int> {
        var g = 0; var a = 0; var t = 0
        Stores.ledger.update { d ->
            val gIds = d.groups.map { it.id }.toHashSet(); val aIds = d.accounts.map { it.id }.toHashSet(); val tIds = d.txs.map { it.id }.toHashSet()
            val ng = other.groups.filter { it.id !in gIds }; val na = other.accounts.filter { it.id !in aIds }; val nt = other.txs.filter { it.id !in tIds }
            g = ng.size; a = na.size; t = nt.size
            // accounts whose group doesn't exist here go to the first group
            val allGroups = d.groups + ng
            val fixed = na.map { if (allGroups.any { gr -> gr.id == it.groupId }) it else it.copy(groupId = allGroups.first().id) }
            d.copy(groups = allGroups, accounts = d.accounts + fixed, txs = d.txs + nt, currencies = (d.currencies + other.currencies).distinct())
        }
        return Triple(g, a, t)
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
