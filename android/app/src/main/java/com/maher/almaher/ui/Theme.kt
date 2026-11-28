package com.maher.almaher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.maher.almaher.data.Settings

val DefaultPlus = Color(0xFFFF8F00)
val DefaultEquals = Color(0xFF2E7D32)

val KeyPalette = listOf(
    0xFFFF8F00, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB,
    0xFF1E88E5, 0xFF0B3D91, 0xFF00897B, 0xFF2E7D32, 0xFF7CB342, 0xFFFDD835,
    0xFF6D4C41, 0xFF546E7A, 0xFF212121, 0xFFECEFF1, 0xFFFFFFFF,
)

@Composable
/** Optional: the colours of the standalone app. Inside another app its own theme is used. */
fun AlMaherTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF9EC2FF),
            onPrimary = Color(0xFF00315F),
            primaryContainer = Color(0xFF1E3A66),
            onPrimaryContainer = Color(0xFFD6E3FF),
            secondaryContainer = Color(0xFF2B3442),
            onSecondaryContainer = Color(0xFFD7E3F8),
            tertiaryContainer = Color(0xFF5C2B2B),
            onTertiaryContainer = Color(0xFFFFDAD6),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0B3D91),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD6E3FF),
            onPrimaryContainer = Color(0xFF001B3E),
            secondaryContainer = Color(0xFFE3EAF6),
            onSecondaryContainer = Color(0xFF1A2A40),
            tertiaryContainer = Color(0xFFFFDAD6),
            onTertiaryContainer = Color(0xFF8C1D18),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun contentOn(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF111111) else Color.White

data class KeyColors(
    val num: Color, val numFg: Color,
    val op: Color, val opFg: Color,
    val plus: Color, val plusFg: Color,
    val eq: Color, val eqFg: Color,
    val fn: Color, val fnFg: Color,
    val ctl: Color, val ctlFg: Color,
)

@Composable
fun keyColors(st: Settings): KeyColors {
    val cs = MaterialTheme.colorScheme
    fun pick(v: Long, def: Color) = if (v == 0L) def else Color(v)
    val num = pick(st.numColor, cs.surfaceContainerHighest)
    val op = pick(st.opColor, cs.primaryContainer)
    val plus = pick(st.plusColor, DefaultPlus)
    val eq = pick(st.eqColor, DefaultEquals)
    return KeyColors(
        num = num, numFg = if (st.numColor == 0L) cs.onSurface else contentOn(num),
        op = op, opFg = if (st.opColor == 0L) cs.onPrimaryContainer else contentOn(op),
        plus = plus, plusFg = contentOn(plus),
        eq = eq, eqFg = contentOn(eq),
        fn = cs.secondaryContainer, fnFg = cs.onSecondaryContainer,
        ctl = cs.tertiaryContainer, ctlFg = cs.onTertiaryContainer,
    )
}
