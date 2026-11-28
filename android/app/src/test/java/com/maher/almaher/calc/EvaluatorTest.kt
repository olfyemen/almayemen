package com.maher.almaher.calc

import com.maher.almaher.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class EvaluatorTest {
    private fun ev(s: String, deg: Boolean = true) = Evaluator.format(Evaluator.eval(s, BigDecimal("7"), deg))
    private fun err(s: String) = try { ev(s); "ok" } catch (e: Exception) { "err" }

    @Test fun basic() {
        assertEquals("0.3", ev("0.1+0.2"))
        assertEquals("1750", ev("1500+250"))
        assertEquals("2000", ev("1500+250×2"))
        assertEquals("1", ev("1÷3×3"))
        assertEquals("0.3333333333", ev("1÷3"))
        assertEquals("−2", ev("3−5"))
        assertEquals("15", ev("3×−(−5)"))
        assertEquals("err", err("5÷0"))
    }

    @Test fun percent() {
        assertEquals("220", ev("200+10%"))
        assertEquals("180", ev("200−10%"))
        assertEquals("20", ev("200×10%"))
        assertEquals("0.5", ev("50%"))
    }

    @Test fun scientific() {
        assertEquals("0.5", ev("sin(30)"))
        assertEquals("0", ev("cos(90)"))
        assertEquals("1", ev("tan(45)"))
        assertEquals("err", err("tan(90)"))
        assertEquals("30", ev("sin⁻¹(0.5)"))
        assertEquals("0", ev("sin(π)", deg = false))
        assertEquals("3", ev("log(1000)"))
        assertEquals("1", ev("ln(e)"))
        assertEquals("4", ev("√(16)"))
        assertEquals("3", ev("∛(27)"))
        assertEquals("1024", ev("2^10"))
        assertEquals("512", ev("2^3^2"))
        assertEquals("−4", ev("−2^2"))
        assertEquals("0.5", ev("2^−1"))
        assertEquals("120", ev("5!"))
        assertEquals("25", ev("5²"))
        assertEquals("27", ev("3³"))
        assertEquals("0.25", ev("4⁻¹"))
        assertEquals("2", ev("3ˣ√8"))
        assertEquals("60", ev("5P3"))
        assertEquals("10", ev("5C3"))
        assertEquals("6.283185307", ev("2π").take(11))
        assertEquals("14", ev("2Ans"))
        assertEquals("5000", ev("5E3"))
        assertEquals("0.005", ev("5E−3"))
        assertEquals("20", ev("(2+3)×4"))
        assertEquals("20", ev("(2+3)4"))
        assertEquals("5", ev("(2+3"))
        assertEquals("5", ev("Abs(−5)"))
        assertEquals("1000", ev("10^(3"))
    }

    @Test fun formatting() {
        val st = Settings()
        assertEquals("1 234 567.89", Fmt.format("1234567.89", st))
        assertEquals("1.234.567,89", Fmt.format("1234567.89", st.copy(decimalComma = true, thousands = "mark")))
        assertEquals("1,234,567.89", Fmt.format("1234567.89", st.copy(thousands = "mark")))
        assertEquals("١٢٣٤", Fmt.format("1234", st.copy(arabicDigits = true, thousands = "none")))
        assertEquals("1.5E−20", Fmt.format("1.5E−20", st))
        assertEquals("1.2E20", ev("12E19"))
        assertEquals("sin(30)", Evaluator.splitTokens("sin(30)").let { it.joinToString("") })
        assertEquals(listOf("sin⁻¹(", "1", ")", "⁻¹"), Evaluator.splitTokens("sin⁻¹(1)⁻¹"))
    }
}
