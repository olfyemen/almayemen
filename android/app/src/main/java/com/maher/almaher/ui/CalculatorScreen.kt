package com.maher.almaher.ui

import com.maher.almaher.S
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maher.almaher.CalcViewModel
import com.maher.almaher.Feedback
import com.maher.almaher.calc.Fmt
import kotlinx.coroutines.launch

@Composable
fun CalculatorScreen(
    vm: CalcViewModel,
    fb: Feedback,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val st = vm.settings
    val kc = keyColors(st)
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, S.back)
            }
            ModeToggle(st.scientific) { vm.updateSettings(st.copy(scientific = it)) }
            Spacer(Modifier.weight(1f))
            val ib = Modifier.size(42.dp)
            // ♻️ recurring key: adds the operation saved in settings (e.g. ×200); opens settings when not set yet
            IconButton(
                onClick = {
                    fb.tap(st)
                    if (st.recurring.isBlank()) onSettings() else vm.pressRecurring()
                },
                modifier = ib
            ) {
                Text("♻️", fontSize = 22.sp)
            }
            IconButton(onClick = onHistory, modifier = ib) {
                Icon(Icons.AutoMirrored.Filled.List, S.history)
            }
            IconButton(onClick = onSettings, modifier = ib) {
                Icon(Icons.Filled.Settings, S.settings)
            }
        }
        Display(vm, Modifier.weight(if (st.scientific) 0.24f else 0.30f))
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            if (st.scientific) SciPad(vm, fb, kc, Modifier.weight(0.34f))
            BasicPad(vm, fb, kc, compact = st.scientific, modifier = Modifier.weight(if (st.scientific) 0.42f else 0.70f * st.keyScale))
        }
    }
}

@Composable
private fun ModeToggle(scientific: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceContainerHigh)
            .padding(3.dp)
    ) {
        listOf(false to S.simple, true to S.scientific).forEach { (v, res) ->
            val sel = v == scientific
            Text(
                res,
                color = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) cs.primary else Color.Transparent)
                    .clickable { onChange(v) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Expression lines on top, result line at the bottom.
 * A long expression first gets smaller, then wraps onto more lines, and when the
 * lines fill the screen it can be scrolled (arrows show there is more).
 */
@Composable
private fun Display(vm: CalcViewModel, modifier: Modifier) {
    val st = vm.settings
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val exprRaw = if (vm.justEvaluated) vm.lastExpr else vm.expression
    val exprText = Fmt.format(exprRaw, st)
    val preview = vm.preview
    val resultText = when {
        vm.error -> S.error
        vm.justEvaluated -> "= " + Fmt.format(vm.expression, st)
        preview != null -> "= " + Fmt.format(preview, st)
        else -> ""
    }
    val shrink = when {
        exprText.length <= 10 -> 1f
        exprText.length <= 14 -> 0.86f
        exprText.length <= 18 -> 0.73f
        else -> 0.64f
    }
    val exprSize = (if (vm.justEvaluated) 26.sp else 44.sp) * (shrink * st.exprScale)
    val big = vm.justEvaluated || vm.error
    val resultSize = (if (big) 46.sp else 30.sp) * (st.resultScale * if (resultText.length > 16) 0.8f else 1f)
    val sc = rememberScrollState()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(cs.surfaceContainerLow)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val flags = listOfNotNull(
                    if (st.scientific) (if (st.degrees) "DEG" else "RAD") else null,
                    if (vm.memory != null) "M" else null,
                    if (st.scientific && vm.second) "2nd" else null,
                    if (st.scientific && vm.hyp) "hyp" else null,
                ).joinToString("   ")
                Text(flags, fontSize = 12.sp, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (sc.value > 0) {
                    Text(
                        "⬆", fontSize = 18.sp, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { sc.animateScrollTo(0) } }.padding(horizontal = 8.dp)
                    )
                }
                if (sc.value < sc.maxValue) {
                    Text(
                        "⬇", fontSize = 18.sp, color = cs.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable { scope.launch { sc.animateScrollTo(sc.maxValue) } }.padding(horizontal = 8.dp)
                    )
                }
            }
            ExpressionLines(
                exprText.ifEmpty { Fmt.digits("0", st.arabicDigits) },
                exprSize,
                if (vm.justEvaluated) cs.onSurfaceVariant else cs.onSurface,
                sc,
                Modifier.weight(1f)
            )
            ScrollingLine(
                resultText, resultSize,
                if (vm.error) cs.error else cs.primary,
                if (big) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ExpressionLines(text: String, size: TextUnit, color: Color, sc: ScrollState, modifier: Modifier) {
    // keep the newest part (the end) in view while typing
    LaunchedEffect(text, sc.maxValue) { sc.scrollTo(sc.maxValue) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val h = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(sc)) {
            Box(Modifier.fillMaxWidth().heightIn(min = h), contentAlignment = Alignment.BottomEnd) {
                Text(text, fontSize = size, lineHeight = size * 1.15f, color = color, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun ScrollingLine(text: String, size: TextUnit, color: Color, weight: FontWeight) {
    val sc = rememberScrollState()
    LaunchedEffect(text, sc.maxValue) { sc.scrollTo(sc.maxValue) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text, fontSize = size, color = color, fontWeight = weight,
            maxLines = 1, softWrap = false,
            modifier = Modifier.horizontalScroll(sc)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Key(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier,
    fontSize: TextUnit,
    bold: Boolean = false,
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLong),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = fg, fontSize = fontSize, maxLines = 1,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * Simple keypad (left to right):
 *   C   UN  (   )
 *   7   8   9   ÷
 *   4   5   6   ×
 *   1   2   3   +   (+ spans two rows)
 *   0   00  .   +
 *   =   ANS ⌫   −
 */
@Composable
private fun BasicPad(vm: CalcViewModel, fb: Feedback, kc: KeyColors, compact: Boolean, modifier: Modifier) {
    val st = vm.settings
    val cs = MaterialTheme.colorScheme
    val fs = if (compact) 22.sp else 30.sp
    val opFs = if (compact) 26.sp else 34.sp
    fun tap(action: () -> Unit): () -> Unit = { fb.tap(st); action() }
    val full = Modifier.fillMaxHeight()

    @Composable
    fun ColumnScope.Row4(keys: List<String>, op: String) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            keys.forEach { k ->
                Key(Fmt.digits(k, st.arabicDigits), kc.num, kc.numFg, full.weight(1f), fs, onClick = tap { vm.press(k) })
            }
            Key(op, kc.op, kc.opFg, full.weight(1f), opFs, onClick = tap { vm.press(op) })
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Key("C", kc.ctl, kc.ctlFg, full.weight(1f), fs, bold = true, onClick = tap { vm.clear() })
            Key(
                "UN", kc.ctl, if (vm.undoCount > 0) kc.ctlFg else kc.ctlFg.copy(alpha = 0.4f),
                full.weight(1f), if (compact) 18.sp else 22.sp, bold = true, onClick = tap { vm.undo() }
            )
            Key("(", kc.op, kc.opFg, full.weight(1f), opFs, onClick = tap { vm.press("(") })
            Key(")", kc.op, kc.opFg, full.weight(1f), opFs, onClick = tap { vm.press(")") })
        }
        Row4(listOf("7", "8", "9"), "÷")
        Row4(listOf("4", "5", "6"), "×")
        Row(Modifier.fillMaxWidth().weight(2f)) {
            Column(Modifier.weight(3f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    listOf("1", "2", "3").forEach { k ->
                        Key(Fmt.digits(k, st.arabicDigits), kc.num, kc.numFg, full.weight(1f), fs, onClick = tap { vm.press(k) })
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Key(Fmt.digits("0", st.arabicDigits), kc.num, kc.numFg, full.weight(1f), fs, onClick = tap { vm.press("0") })
                    Key(Fmt.digits("00", st.arabicDigits), kc.num, kc.numFg, full.weight(1f), fs, onClick = tap { vm.press("00") })
                    Key(st.decimalChar.toString(), kc.num, kc.numFg, full.weight(1f), fs, bold = true, onClick = tap { vm.press(".") })
                }
            }
            // the big plus key
            Key(
                "+", kc.plus, kc.plusFg, full.weight(1f),
                if (compact) 48.sp else 64.sp, bold = true,
                onClick = tap { vm.press("+") }
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Key("=", kc.eq, kc.eqFg, full.weight(1f), opFs, bold = true, onClick = tap { vm.equals() })
            Key("ANS", cs.secondaryContainer, cs.onSecondaryContainer, full.weight(1f), if (compact) 16.sp else 20.sp, bold = true, onClick = tap { vm.answer() })
            Key("⌫", kc.ctl, kc.ctlFg, full.weight(1f), fs, onLong = tap { vm.clear() }, onClick = tap { vm.backspace() })
            Key("−", kc.op, kc.opFg, full.weight(1f), opFs, onClick = tap { vm.press("−") })
        }
    }
}

private class SciKey(val label: String, val active: Boolean = false, val action: () -> Unit)

@Composable
private fun SciPad(vm: CalcViewModel, fb: Feedback, kc: KeyColors, modifier: Modifier) {
    val st = vm.settings
    val s2 = vm.second
    val h = vm.hyp
    fun fn(token: String) { vm.press(token); vm.second = false; vm.hyp = false }
    fun trig(base: String): SciKey {
        val label = base + (if (h) "h" else "") + (if (s2) "⁻¹" else "")
        return SciKey(label) { fn("$label(") }
    }
    val rows = listOf(
        listOf(
            SciKey("2nd", s2) { vm.second = !vm.second },
            SciKey("hyp", h) { vm.hyp = !vm.hyp },
            SciKey(if (st.degrees) "DEG" else "RAD") { vm.toggleDegrees() },
            SciKey("%") { vm.press("%") },
            SciKey("Ans") { vm.press("Ans") },
            SciKey("nPr") { fn("P") },
        ),
        listOf(
            trig("sin"), trig("cos"), trig("tan"),
            SciKey("x²") { fn("²") },
            SciKey("x³") { fn("³") },
            if (s2) SciKey("ʸ√x") { fn("ˣ√") } else SciKey("xʸ") { fn("^") },
        ),
        listOf(
            SciKey("log") { fn("log(") },
            SciKey("ln") { fn("ln(") },
            SciKey("10ˣ") { vm.pressSeq(listOf("1", "0", "^", "(")); vm.second = false },
            SciKey("eˣ") { vm.pressSeq(listOf("e", "^", "(")); vm.second = false },
            SciKey("√") { fn("√(") },
            SciKey("∛") { fn("∛(") },
        ),
        listOf(
            if (s2) SciKey("Ran#") { vm.random(); vm.second = false } else SciKey("π") { vm.press("π") },
            SciKey("e") { vm.press("e") },
            SciKey("EXP") { vm.press("E") },
            SciKey("n!") { fn("!") },
            if (s2) SciKey("|x|") { fn("Abs(") } else SciKey("x⁻¹") { fn("⁻¹") },
            SciKey("nCr") { fn("C") },
        ),
        listOf(
            SciKey("MC") { vm.memClear() },
            SciKey("MR") { vm.memRecall() },
            SciKey("M+") { vm.memAdd(1) },
            SciKey("M−") { vm.memAdd(-1) },
            SciKey("(−)") { vm.press("−") },
            SciKey("|x|") { fn("Abs(") },
        ),
    )
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                row.forEach { k ->
                    Key(
                        k.label,
                        if (k.active) cs.primary else kc.fn,
                        if (k.active) cs.onPrimary else kc.fnFg,
                        Modifier.fillMaxHeight().weight(1f),
                        15.sp,
                        onClick = { fb.tap(st); k.action() }
                    )
                }
            }
        }
    }
}
