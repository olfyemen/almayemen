package com.maher.almaher.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

class CalcException(msg: String) : Exception(msg)

/**
 * Expression evaluator. Basic arithmetic uses BigDecimal (no 0.1+0.2 errors),
 * scientific functions go through Double and are rounded back.
 *
 * Expression symbols: digits . + − × ÷ ^ ( ) ! ² ³ % ⁻¹ π e E(exp) P(nPr) C(nCr) ˣ√ Ans
 * and functions listed in [FUNCS] (each includes its opening parenthesis).
 */
object Evaluator {
    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val PI_BD = BigDecimal("3.141592653589793238462643383279503")
    private val E_BD = BigDecimal("2.718281828459045235360287471352662")
    private val LIMIT = BigDecimal("1E9999")

    val FUNCS = listOf(
        "sinh⁻¹(", "cosh⁻¹(", "tanh⁻¹(", "sin⁻¹(", "cos⁻¹(", "tan⁻¹(",
        "sinh(", "cosh(", "tanh(", "sin(", "cos(", "tan(",
        "log(", "ln(", "√(", "∛(", "Abs("
    )
    private val NAMES = FUNCS + listOf("Ans", "ˣ√", "⁻¹")

    /** Splits an expression string back into input tokens (for editing / backspace). */
    fun splitTokens(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        outer@ while (i < s.length) {
            for (n in NAMES) if (s.startsWith(n, i)) {
                out.add(n); i += n.length; continue@outer
            }
            out.add(s[i].toString()); i++
        }
        return out
    }

    fun eval(expr: String, ans: BigDecimal, degrees: Boolean): BigDecimal {
        val toks = tokenize(expr, ans)
        if (toks.isEmpty()) throw CalcException("empty")
        val v = Parser(toks, degrees).parse()
        if (v.abs() > LIMIT) throw CalcException("overflow")
        return v
    }

    /** Canonical display string: '.' decimal, '−' minus, no grouping. */
    fun format(v: BigDecimal): String {
        if (v.signum() == 0) return "0"
        val a = v.abs()
        val s = if (a >= BigDecimal("1E15") || a < BigDecimal("1E-10")) {
            val r = v.round(MathContext(10, RoundingMode.HALF_UP))
            val exp = r.precision() - r.scale() - 1
            val mant = r.movePointLeft(exp).stripTrailingZeros().toPlainString()
            "${mant}E$exp"
        } else {
            var r = v.round(MathContext(15, RoundingMode.HALF_UP))
            if (r.scale() > 10) r = r.setScale(10, RoundingMode.HALF_UP)
            r = r.stripTrailingZeros()
            if (r.signum() == 0) "0" else r.toPlainString()
        }
        return s.replace('-', '−')
    }

    // ---------------------------------------------------------------- tokens

    private sealed class Tok {
        class Num(val v: BigDecimal) : Tok()
        class Op(val c: Char) : Tok()
        class Func(val name: String) : Tok()
        object LParen : Tok()
        object RParen : Tok()
        class Const(val v: BigDecimal) : Tok()
        class Post(val c: Char) : Tok()
    }

    private fun tokenize(s: String, ans: BigDecimal): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit() || c == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && s[i] == 'E') {
                    i++
                    if (i < s.length && (s[i] == '−' || s[i] == '-')) i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                val lit = s.substring(st, i).replace('−', '-')
                out.add(Tok.Num(lit.toBigDecimalOrNull() ?: throw CalcException("number")))
                continue
            }
            val fn = FUNCS.firstOrNull { s.startsWith(it, i) }
            if (fn != null) {
                out.add(Tok.Func(fn)); i += fn.length; continue
            }
            when {
                s.startsWith("Ans", i) -> { out.add(Tok.Const(ans)); i += 3 }
                s.startsWith("ˣ√", i) -> { out.add(Tok.Op('r')); i += 2 }
                s.startsWith("⁻¹", i) -> { out.add(Tok.Post('i')); i += 2 }
                else -> {
                    when (c) {
                        '+', '×', '÷', '^', 'P', 'C' -> out.add(Tok.Op(c))
                        '−', '-' -> out.add(Tok.Op('−'))
                        '(' -> out.add(Tok.LParen)
                        ')' -> out.add(Tok.RParen)
                        '!', '²', '³', '%' -> out.add(Tok.Post(c))
                        'π' -> out.add(Tok.Const(PI_BD))
                        'e' -> out.add(Tok.Const(E_BD))
                        ' ' -> {}
                        else -> throw CalcException("char $c")
                    }
                    i++
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- parser

    private class Parser(private val t: List<Tok>, private val deg: Boolean) {
        private var i = 0
        /** true when the factor just parsed ended with a % sign (for 200 + 10% = 220). */
        private var lastPct = false

        private fun peek(): Tok? = t.getOrNull(i)
        private fun isOp(c: Char) = (peek() as? Tok.Op)?.c == c

        fun parse(): BigDecimal {
            val v = expr()
            if (i < t.size) throw CalcException("syntax")
            return v
        }

        private fun expr(): BigDecimal {
            var left = termP().first
            while (true) {
                val plus = isOp('+')
                val minus = isOp('−')
                if (!plus && !minus) return left
                i++
                val (r, pct) = termP()
                val rv = if (pct) left.multiply(r, MC) else r
                left = if (plus) left.add(rv, MC) else left.subtract(rv, MC)
            }
        }

        private fun termP(): Pair<BigDecimal, Boolean> {
            var left = perm()
            var pct = lastPct
            while (true) {
                val p = peek()
                if (p is Tok.Op && (p.c == '×' || p.c == '÷')) {
                    i++
                    val r = perm()
                    left = if (p.c == '×') left.multiply(r, MC) else {
                        if (r.signum() == 0) throw CalcException("div0")
                        left.divide(r, MC)
                    }
                    pct = false
                } else if (p != null && startsPrimary(p)) {
                    left = left.multiply(perm(), MC) // implicit multiplication: 2π, 3(4), 2sin(30)
                    pct = false
                } else return left to pct
            }
        }

        private fun startsPrimary(p: Tok) =
            p is Tok.Num || p is Tok.Const || p is Tok.LParen || p is Tok.Func

        private fun perm(): BigDecimal {
            var left = unary()
            while (isOp('P') || isOp('C')) {
                val comb = isOp('C')
                i++
                val r = unary()
                left = permComb(left, r, comb)
                lastPct = false
            }
            return left
        }

        private fun unary(): BigDecimal {
            if (isOp('−')) { i++; return unary().negate() }
            if (isOp('+')) { i++; return unary() }
            return power()
        }

        private fun power(): BigDecimal {
            val base = postfix()
            if (isOp('^')) { i++; val e = unary(); lastPct = false; return pow(base, e) }
            if (isOp('r')) { i++; val x = unary(); lastPct = false; return root(base, x) }
            return base
        }

        private fun postfix(): BigDecimal {
            var v = primary()
            lastPct = false
            while (true) {
                val p = peek() as? Tok.Post ?: return v
                i++
                lastPct = false
                v = when (p.c) {
                    '!' -> factorial(v)
                    '²' -> v.multiply(v, MC)
                    '³' -> v.pow(3, MC)
                    '%' -> { lastPct = true; v.divide(BigDecimal(100), MC) }
                    'i' -> {
                        if (v.signum() == 0) throw CalcException("div0")
                        BigDecimal.ONE.divide(v, MC)
                    }
                    else -> throw CalcException("postfix")
                }
            }
        }

        private fun primary(): BigDecimal {
            val p = peek() ?: throw CalcException("eof")
            i++
            return when (p) {
                is Tok.Num -> p.v
                is Tok.Const -> p.v
                is Tok.LParen -> { val v = expr(); closeParen(); v }
                is Tok.Func -> { val v = expr(); closeParen(); applyFunc(p.name, v, deg) }
                else -> throw CalcException("syntax")
            }
        }

        /** Missing closing parentheses at the end are allowed (auto-closed). */
        private fun closeParen() {
            when (peek()) {
                is Tok.RParen -> i++
                null -> {}
                else -> throw CalcException("paren")
            }
        }
    }

    // ---------------------------------------------------------------- math helpers

    private fun isInt(v: BigDecimal) = v.signum() == 0 || v.stripTrailingZeros().scale() <= 0

    private fun fromD(d: Double): BigDecimal {
        if (d.isNaN() || d.isInfinite()) throw CalcException("math")
        return BigDecimal(d).round(MathContext(15, RoundingMode.HALF_EVEN))
    }

    /** Removes floating noise from trig results, e.g. sin(30°) = 0.49999999999999994 → 0.5 */
    private fun clean(v: BigDecimal): BigDecimal = v.setScale(13, RoundingMode.HALF_UP).stripTrailingZeros()

    private fun factorial(v: BigDecimal): BigDecimal {
        if (!isInt(v) || v.signum() < 0 || v > BigDecimal(1000)) throw CalcException("factorial")
        var r = BigDecimal.ONE
        for (k in 2..v.toInt()) r = r.multiply(BigDecimal(k))
        return r
    }

    private fun permComb(n: BigDecimal, r: BigDecimal, comb: Boolean): BigDecimal {
        if (!isInt(n) || !isInt(r) || n.signum() < 0 || r.signum() < 0 || r > n || n > BigDecimal(1000)) {
            throw CalcException("perm")
        }
        val ni = n.toInt()
        val ri = r.toInt()
        var p = BigDecimal.ONE
        for (k in (ni - ri + 1)..ni) p = p.multiply(BigDecimal(k))
        return if (comb) p.divide(factorial(r)) else p
    }

    private fun pow(b: BigDecimal, e: BigDecimal): BigDecimal {
        if (isInt(e) && e.abs() <= BigDecimal(9999)) {
            val n = e.toInt()
            if (n >= 0) return b.pow(n, MC)
            if (b.signum() == 0) throw CalcException("div0")
            return BigDecimal.ONE.divide(b.pow(-n, MC), MC)
        }
        val bd = b.toDouble()
        val ed = e.toDouble()
        if (bd < 0) {
            val inv = 1.0 / ed
            val ri = inv.roundToLong()
            if (abs(inv - ri) < 1e-9 && ri % 2L != 0L) return fromD(-((-bd).pow(ed)))
            throw CalcException("math")
        }
        return fromD(bd.pow(ed))
    }

    private fun root(n: BigDecimal, x: BigDecimal): BigDecimal {
        if (n.signum() == 0) throw CalcException("math")
        val nd = n.toDouble()
        val xd = x.toDouble()
        if (xd < 0) {
            if (isInt(n) && n.toLong() % 2L != 0L) return fromD(-((-xd).pow(1.0 / nd)))
            throw CalcException("math")
        }
        return fromD(xd.pow(1.0 / nd))
    }

    private fun applyFunc(name: String, x: BigDecimal, deg: Boolean): BigDecimal {
        val d = x.toDouble()
        val rad = if (deg) Math.toRadians(d) else d
        fun ang(v: Double) = if (deg) Math.toDegrees(v) else v
        fun fail(): Nothing = throw CalcException("math")
        return when (name) {
            "sin(" -> clean(fromD(sin(rad)))
            "cos(" -> clean(fromD(cos(rad)))
            "tan(" -> {
                if (deg) {
                    if (x.remainder(BigDecimal(180)).abs().compareTo(BigDecimal(90)) == 0) fail()
                } else if (abs(cos(rad)) < 1e-15) fail()
                clean(fromD(tan(rad)))
            }
            "sin⁻¹(" -> { if (abs(d) > 1) fail(); clean(fromD(ang(asin(d)))) }
            "cos⁻¹(" -> { if (abs(d) > 1) fail(); clean(fromD(ang(acos(d)))) }
            "tan⁻¹(" -> clean(fromD(ang(atan(d))))
            "sinh(" -> fromD(sinh(d))
            "cosh(" -> fromD(cosh(d))
            "tanh(" -> fromD(tanh(d))
            "sinh⁻¹(" -> fromD(asinh(d))
            "cosh⁻¹(" -> { if (d < 1) fail(); fromD(acosh(d)) }
            "tanh⁻¹(" -> { if (abs(d) >= 1) fail(); fromD(atanh(d)) }
            "log(" -> { if (x.signum() <= 0) fail(); fromD(log10(d)) }
            "ln(" -> { if (x.signum() <= 0) fail(); fromD(ln(d)) }
            "√(" -> { if (x.signum() < 0) fail(); fromD(sqrt(d)) }
            "∛(" -> fromD(cbrt(d))
            "Abs(" -> x.abs()
            else -> fail()
        }
    }
}
