package com.maher.almaher

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.maher.almaher.calc.Evaluator
import com.maher.almaher.data.HistoryItem
import com.maher.almaher.data.SavedState
import com.maher.almaher.data.Settings
import com.maher.almaher.data.Store
import java.math.BigDecimal
import kotlin.random.Random

const val HISTORY_MAX = 200
const val UNDO_MAX = 10

class CalcViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    var settings by mutableStateOf(store.loadSettings()); private set
    val history = mutableStateListOf<HistoryItem>()
    val tokens = mutableStateListOf<String>()
    var justEvaluated by mutableStateOf(false); private set
    var lastExpr by mutableStateOf(""); private set
    var error by mutableStateOf(false); private set
    var memory by mutableStateOf<BigDecimal?>(null); private set
    var second by mutableStateOf(false)
    var hyp by mutableStateOf(false)
    private var ans: BigDecimal = BigDecimal.ZERO

    init {
        history.addAll(store.loadHistory())
        val s = store.loadState()
        tokens.addAll(Evaluator.splitTokens(s.expr))
        justEvaluated = s.just && s.expr.isNotEmpty()
        lastExpr = s.last
        ans = s.ans.toBigDecimalOrNull() ?: BigDecimal.ZERO
        memory = s.mem?.toBigDecimalOrNull()
    }

    val expression: String get() = tokens.joinToString("")

    /** Live result shown under the expression before pressing '='. */
    val preview: String? by derivedStateOf {
        if (justEvaluated || error || tokens.isEmpty() || !hasOperation(expression)) null
        else evalLenient()?.let { Evaluator.format(it) }
    }

    fun updateSettings(s: Settings) {
        settings = s
        store.saveSettings(s)
    }

    fun persist() {
        store.saveState(SavedState(expression, justEvaluated, lastExpr, ans.toPlainString(), memory?.toPlainString()))
    }

    // ------------------------------------------------------------ input

    private val ARITH = setOf("+", "−", "×", "÷")
    private val BIN = ARITH + setOf("^", "P", "C", "ˣ√")
    private val POSTFIX = setOf("!", "²", "³", "%", "⁻¹")
    private val TRAILING = BIN + setOf("E")

    private fun isValueEnd(t: String?) = t != null &&
        (t[0].isDigit() || t == "." || t == ")" || t == "π" || t == "e" || t == "Ans" || t in POSTFIX)

    private fun isDigitTok(t: String) = t.length == 1 && (t[0].isDigit() || t == ".")

    private fun currentRun(): String {
        val sb = StringBuilder()
        var k = tokens.lastIndex
        while (k >= 0 && isDigitTok(tokens[k])) { sb.insert(0, tokens[k]); k-- }
        return sb.toString()
    }

    private fun runPrecededByE(): Boolean {
        var k = tokens.lastIndex
        while (k >= 0 && isDigitTok(tokens[k])) k--
        if (k >= 0 && tokens[k] == "E") return true
        return k >= 1 && tokens[k] == "−" && tokens[k - 1] == "E"
    }

    private fun beginInput(t: String?) {
        error = false
        if (!justEvaluated) return
        justEvaluated = false
        val continuing = t != null && (t in BIN || t in POSTFIX)
        if (!continuing) {
            tokens.clear()
        } else if (t !in ARITH && (tokens.firstOrNull() == "−" || tokens.contains("E"))) {
            tokens.add(0, "("); tokens.add(")")
        }
    }

    fun press(t: String) {
        beginInput(t)
        val last = tokens.lastOrNull()
        when {
            t in BIN -> pressBinary(t, last)
            t == "." -> {
                if (runPrecededByE()) return
                val run = currentRun()
                if ('.' in run) return
                if (run.isEmpty()) {
                    if (isValueEnd(last)) tokens.add("×")
                    tokens.add("0")
                }
                tokens.add(".")
            }
            t == "00" || (t.length == 1 && t[0].isDigit()) -> pressDigit(t, last)
            t == ")" -> {
                val open = tokens.count { it.endsWith("(") }
                val close = tokens.count { it == ")" }
                if (open > close && isValueEnd(last)) tokens.add(t)
            }
            t in POSTFIX -> if (isValueEnd(last)) tokens.add(t)
            t == "E" -> if (last != null && last[0].isDigit() && !runPrecededByE()) tokens.add(t)
            else -> tokens.add(t) // functions, "(", constants, Ans
        }
        while (tokens.size > 300) tokens.removeAt(tokens.lastIndex)
    }

    /** Inserts several tokens as one value, e.g. 10ˣ → 1 0 ^ ( */
    fun pressSeq(seq: List<String>) {
        beginInput(null)
        if (isValueEnd(tokens.lastOrNull())) tokens.add("×")
        tokens.addAll(seq)
    }

    private fun pressBinary(t: String, last: String?) {
        when {
            last == null -> if (t == "−") tokens.add(t)
            last == "E" -> if (t == "−") tokens.add(t)
            last.endsWith("(") -> if (t == "−") tokens.add(t)
            last in BIN -> {
                if (t == "−" && last != "−" && last != "+") { tokens.add(t); return }
                tokens.removeAt(tokens.lastIndex)
                val prev = tokens.lastOrNull()
                if (prev != null && prev in BIN) tokens.removeAt(tokens.lastIndex)
                val now = tokens.lastOrNull()
                if (now == null || now.endsWith("(")) {
                    if (t == "−") tokens.add(t)
                    return
                }
                tokens.add(t)
            }
            else -> tokens.add(t)
        }
    }

    private fun pressDigit(t: String, last: String?) {
        val run = currentRun()
        if (run.count { it.isDigit() } >= 18) return
        if (run == "0") {
            if (t == "0" || t == "00") return
            tokens[tokens.lastIndex] = t
            return
        }
        if (t == "00") {
            if (run.isEmpty()) {
                if (!isValueEnd(last)) tokens.add("0")
                return
            }
            tokens.add("0"); tokens.add("0")
            return
        }
        tokens.add(t)
    }

    private fun insertNumber(v: BigDecimal) {
        beginInput(null)
        val s = Evaluator.format(v)
        if (isValueEnd(tokens.lastOrNull())) tokens.add("×")
        val parts = Evaluator.splitTokens(s)
        if (s.startsWith("−") && tokens.isNotEmpty()) {
            tokens.add("("); tokens.addAll(parts); tokens.add(")")
        } else tokens.addAll(parts)
    }

    // ------------------------------------------------------------ ♻️ recurring key

    /** Turns what the user typed in settings (e.g. "*200", "+500", "/٤") into calculator symbols. */
    fun normalizeRecurring(raw: String): String = buildString {
        for (c in raw) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '*', 'x', 'X' -> '×'
                '/' -> '÷'
                '-', '–' -> '−'
                '٫', ',' -> '.'
                ' ' -> null
                else -> c
            } ?: ""
        )
    }

    /** Adds the saved recurring operation to the expression, key by key, so the usual input rules apply. */
    fun pressRecurring() {
        val text = normalizeRecurring(settings.recurring)
        if (text.isEmpty()) return
        Evaluator.splitTokens(text).forEach { press(it) }
    }

    // ------------------------------------------------------------ UN (undo of deletions)

    private class Snap(val tokens: List<String>, val just: Boolean, val last: String)

    private val undoStack = ArrayDeque<Snap>()
    var undoCount by mutableStateOf(0); private set

    private fun pushUndo() {
        if (tokens.isEmpty() && !justEvaluated) return
        undoStack.addLast(Snap(tokens.toList(), justEvaluated, lastExpr))
        while (undoStack.size > UNDO_MAX) undoStack.removeFirst()
        undoCount = undoStack.size
    }

    /** Brings back what the last delete key removed (up to [UNDO_MAX] steps). */
    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        undoCount = undoStack.size
        error = false
        tokens.clear(); tokens.addAll(s.tokens)
        justEvaluated = s.just
        lastExpr = s.last
    }

    /** ANS: replaces the expression with its result, so calculation continues on it. The operation is saved to history. */
    fun answer() {
        if (error) return
        if (justEvaluated) {
            justEvaluated = false; lastExpr = ""
            return
        }
        val expr = expression
        if (expr.isEmpty() || !hasOperation(expr)) return
        val v = evalLenient()
        if (v == null) { error = true; return }
        val r = Evaluator.format(v)
        addHistory(expr, r)
        ans = v
        tokens.clear()
        tokens.addAll(Evaluator.splitTokens(r))
        lastExpr = ""
        persist()
    }

    fun backspace() {
        if (error) { error = false; return }
        pushUndo()
        if (justEvaluated) {
            justEvaluated = false
            tokens.clear()
            tokens.addAll(Evaluator.splitTokens(lastExpr))
            return
        }
        if (tokens.isNotEmpty()) tokens.removeAt(tokens.lastIndex)
    }

    /** Clears the line. The line is saved to history first (unless it was already saved by '='). */
    fun clear() {
        pushUndo()
        if (tokens.isNotEmpty() && !justEvaluated) {
            val expr = expression
            if (expr != "0") addHistory(expr, evalLenient()?.let { Evaluator.format(it) } ?: "")
        }
        tokens.clear()
        justEvaluated = false
        error = false
        lastExpr = ""
        persist()
    }

    fun equals() {
        if (justEvaluated || error) return
        val expr = expression
        if (expr.isEmpty() || !hasOperation(expr)) return
        val v = evalLenient()
        if (v == null) { error = true; return }
        val r = Evaluator.format(v)
        addHistory(expr, r)
        ans = v
        lastExpr = expr
        tokens.clear()
        tokens.addAll(Evaluator.splitTokens(r))
        justEvaluated = true
        persist()
    }

    private fun hasOperation(expr: String): Boolean {
        val e = expr.removePrefix("−")
        return e.isNotEmpty() && !e.all { it.isDigit() || it == '.' }
    }

    private fun evalLenient(): BigDecimal? {
        val list = tokens.toMutableList()
        while (list.isNotEmpty() && (list.last() in TRAILING || list.last().endsWith("("))) {
            list.removeAt(list.lastIndex)
        }
        if (list.isEmpty()) return null
        return try {
            Evaluator.eval(list.joinToString(""), ans, settings.degrees)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ scientific extras

    fun toggleDegrees() = updateSettings(settings.copy(degrees = !settings.degrees))

    fun random() = insertNumber(BigDecimal(Random.nextInt(0, 1000)).movePointLeft(3))

    private fun currentValue(): BigDecimal? = if (justEvaluated) ans else evalLenient()

    fun memAdd(sign: Int) {
        val v = currentValue() ?: return
        val m = memory ?: BigDecimal.ZERO
        memory = if (sign > 0) m.add(v) else m.subtract(v)
        persist()
    }

    fun memRecall() { memory?.let { insertNumber(it) } }

    fun memClear() { memory = null; persist() }

    // ------------------------------------------------------------ history

    private fun addHistory(expr: String, result: String) {
        history.add(0, HistoryItem(expr, result, System.currentTimeMillis()))
        while (history.size > HISTORY_MAX) history.removeAt(history.lastIndex)
        store.saveHistory(history)
    }

    fun loadFromHistory(item: HistoryItem) {
        error = false
        justEvaluated = false
        tokens.clear()
        tokens.addAll(Evaluator.splitTokens(item.expr))
    }

    fun deleteHistoryItem(item: HistoryItem) {
        history.remove(item)
        store.saveHistory(history)
    }

    fun clearHistory() {
        history.clear()
        store.saveHistory(history)
    }
}
