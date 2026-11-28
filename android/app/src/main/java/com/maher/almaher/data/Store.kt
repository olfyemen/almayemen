package com.maher.almaher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(val expr: String, val result: String, val time: Long)

data class Settings(
    val arabicDigits: Boolean = false,
    val decimalComma: Boolean = false,
    /** "space", "mark" (comma or dot, opposite of the decimal separator) or "none" */
    val thousands: String = "space",
    val sound: Boolean = false,
    val vibrate: Boolean = false,
    val scientific: Boolean = false,
    val degrees: Boolean = true,
    /** 0 = default colour */
    val plusColor: Long = 0,
    val opColor: Long = 0,
    val eqColor: Long = 0,
    val numColor: Long = 0,
    /** text size multipliers for the expression lines and the result line */
    val exprScale: Float = 1f,
    val resultScale: Float = 1f,
    /** key height multiplier in simple mode */
    val keyScale: Float = 1f,
    /** the ♻️ recurring key: text added to the expression, e.g. "×200" or "+500" */
    val recurring: String = "",
) {
    val decimalChar: Char get() = if (decimalComma) ',' else '.'
    val thousandsStr: String
        get() = when (thousands) {
            "none" -> ""
            "mark" -> if (decimalComma) "." else ","
            else -> " "
        }
}

data class SavedState(val expr: String, val just: Boolean, val last: String, val ans: String, val mem: String?)

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("almaher_calc", Context.MODE_PRIVATE)

    fun loadSettings() = Settings(
        arabicDigits = p.getBoolean("arDigits", false),
        decimalComma = p.getBoolean("decComma", false),
        thousands = p.getString("thousands", "space") ?: "space",
        sound = p.getBoolean("sound", false),
        vibrate = p.getBoolean("vibrate", false),
        scientific = p.getBoolean("scientific", false),
        degrees = p.getBoolean("degrees", true),
        plusColor = p.getLong("cPlus", 0L),
        opColor = p.getLong("cOp", 0L),
        eqColor = p.getLong("cEq", 0L),
        numColor = p.getLong("cNum", 0L),
        exprScale = p.getFloat("exprScale", 1f),
        resultScale = p.getFloat("resultScale", 1f),
        keyScale = p.getFloat("keyScale", 1f),
        recurring = p.getString("recurring", "") ?: "",
    )

    fun saveSettings(s: Settings) {
        p.edit()
            .putBoolean("arDigits", s.arabicDigits)
            .putBoolean("decComma", s.decimalComma)
            .putString("thousands", s.thousands)
            .putBoolean("sound", s.sound)
            .putBoolean("vibrate", s.vibrate)
            .putBoolean("scientific", s.scientific)
            .putBoolean("degrees", s.degrees)
            .putLong("cPlus", s.plusColor)
            .putLong("cOp", s.opColor)
            .putLong("cEq", s.eqColor)
            .putLong("cNum", s.numColor)
            .putFloat("exprScale", s.exprScale)
            .putFloat("resultScale", s.resultScale)
            .putFloat("keyScale", s.keyScale)
            .putString("recurring", s.recurring)
            .apply()
    }

    fun loadHistory(): List<HistoryItem> = try {
        val a = JSONArray(p.getString("history", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HistoryItem(o.getString("e"), o.optString("r"), o.optLong("t"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveHistory(list: List<HistoryItem>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("e", it.expr).put("r", it.result).put("t", it.time)) }
        p.edit().putString("history", a.toString()).apply()
    }

    fun loadState() = SavedState(
        p.getString("expr", "") ?: "",
        p.getBoolean("just", false),
        p.getString("last", "") ?: "",
        p.getString("ans", "0") ?: "0",
        p.getString("mem", null),
    )

    fun saveState(s: SavedState) {
        p.edit()
            .putString("expr", s.expr)
            .putBoolean("just", s.just)
            .putString("last", s.last)
            .putString("ans", s.ans)
            .putString("mem", s.mem)
            .apply()
    }
}
