package com.maher.almaher.calc

import com.maher.almaher.data.Settings

/** Display formatting: digit grouping, decimal separator and Arabic-Indic digits. */
object Fmt {
    private const val AR = "٠١٢٣٤٥٦٧٨٩"

    fun digits(s: String, arabic: Boolean): String =
        if (!arabic) s else buildString {
            for (c in s) append(if (c in '0'..'9') AR[c - '0'] else c)
        }

    fun format(s: String, st: Settings): String {
        val sep = st.thousandsStr
        val dec = st.decimalChar
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isDigit() || c == '.') {
                var j = i
                while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                val run = s.substring(i, j)
                val exponent = i > 0 && (s[i - 1] == 'E' || (s[i - 1] == '−' && i > 1 && s[i - 2] == 'E'))
                sb.append(if (exponent) run else groupRun(run, sep, dec))
                i = j
            } else {
                sb.append(c); i++
            }
        }
        return digits(sb.toString(), st.arabicDigits)
    }

    private fun groupRun(run: String, sep: String, dec: Char): String {
        val dot = run.indexOf('.')
        val intPart = if (dot >= 0) run.substring(0, dot) else run
        val frac = if (dot >= 0) run.substring(dot + 1) else null
        val grouped = if (sep.isEmpty() || intPart.length <= 3) intPart else buildString {
            val first = intPart.length % 3
            for ((k, ch) in intPart.withIndex()) {
                if (k > 0 && (k - first) % 3 == 0) append(sep)
                append(ch)
            }
        }
        return if (frac != null) grouped + dec + frac else grouped
    }
}
