package com.maher.almaher.ui

import com.maher.almaher.S
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maher.almaher.CalcViewModel
import com.maher.almaher.calc.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: CalcViewModel, onAbout: () -> Unit, onBack: () -> Unit) {
    val st = vm.settings
    val cs = MaterialTheme.colorScheme
    val kc = keyColors(st)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(S.settings) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(S.example, fontSize = 13.sp, color = cs.onSurfaceVariant)
                    Text(
                        "⁦" + Fmt.format("1234567.89+250", st) + "⁩",
                        fontSize = 28.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Section(S.digits)
            RadioRow(S.digits_en, !st.arabicDigits) { vm.updateSettings(st.copy(arabicDigits = false)) }
            RadioRow(S.digits_ar, st.arabicDigits) { vm.updateSettings(st.copy(arabicDigits = true)) }

            Section(S.decimal_sep)
            RadioRow(S.sep_dot, !st.decimalComma) { vm.updateSettings(st.copy(decimalComma = false)) }
            RadioRow(S.sep_comma, st.decimalComma) { vm.updateSettings(st.copy(decimalComma = true)) }

            Section(S.thousands_sep)
            RadioRow(S.sep_space, st.thousands == "space") { vm.updateSettings(st.copy(thousands = "space")) }
            RadioRow(
                (if (st.decimalComma) S.sep_dot else S.sep_comma),
                st.thousands == "mark"
            ) { vm.updateSettings(st.copy(thousands = "mark")) }
            RadioRow(S.sep_none, st.thousands == "none") { vm.updateSettings(st.copy(thousands = "none")) }

            Section(S.recurring)
            Text(S.recurring_hint, fontSize = 13.sp, color = cs.onSurfaceVariant)
            androidx.compose.material3.OutlinedTextField(
                value = st.recurring,
                onValueChange = { vm.updateSettings(st.copy(recurring = it.take(40))) },
                singleLine = true,
                placeholder = { Text("×200") },
                leadingIcon = { Text("♻️") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("×200", "+500", "−100", "÷2", "×1.15").forEach { p ->
                    androidx.compose.material3.AssistChip(
                        onClick = { vm.updateSettings(st.copy(recurring = p)) },
                        label = { Text("⁦$p⁩") }
                    )
                }
            }

            Section(S.sizes)
            SizeRow(S.key_height, st.keyScale, 24.sp, range = 0.7f..1.4f) { vm.updateSettings(st.copy(keyScale = it)) }
            SizeRow(S.expr_size, st.exprScale, 40.sp) { vm.updateSettings(st.copy(exprScale = it)) }
            SizeRow(S.result_size, st.resultScale, 30.sp) { vm.updateSettings(st.copy(resultScale = it)) }

            Section(S.feedback)
            SwitchRow(S.sound, st.sound) { vm.updateSettings(st.copy(sound = it)) }
            SwitchRow(S.vibration, st.vibrate) { vm.updateSettings(st.copy(vibrate = it)) }

            Section(S.colors)
            ColorRow(S.color_plus, st.plusColor, DefaultPlus) { vm.updateSettings(st.copy(plusColor = it)) }
            ColorRow(S.color_ops, st.opColor, if (st.opColor == 0L) kc.op else cs.primaryContainer) { vm.updateSettings(st.copy(opColor = it)) }
            ColorRow(S.color_equals, st.eqColor, DefaultEquals) { vm.updateSettings(st.copy(eqColor = it)) }
            ColorRow(S.color_numbers, st.numColor, cs.surfaceContainerHighest) { vm.updateSettings(st.copy(numColor = it)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.updateSettings(st.copy(plusColor = 0, opColor = 0, eqColor = 0, numColor = 0)) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(S.reset_colors) }
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("ℹ️  " + S.about) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, fontSize = 16.sp)
    }
}

@Composable
private fun SizeRow(
    label: String,
    value: Float,
    base: androidx.compose.ui.unit.TextUnit,
    range: ClosedFloatingPointRange<Float> = 0.6f..1.6f,
    onChange: (Float) -> Unit,
) {
    val isKeys = range.start == 0.7f
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("${Math.round(value * 100)}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = { onChange((Math.round(it * 20) / 20f)) },
            valueRange = range,
            steps = Math.round((range.endInclusive - range.start) * 20) - 1,
        )
        if (!isKeys) Text(
            "⁦123 + 45⁩", fontSize = base * value, maxLines = 1,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorRow(label: String, value: Long, defaultColor: Color, onPick: (Long) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(label, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Swatch(defaultColor, value == 0L, label = "A") { onPick(0L) }
        KeyPalette.forEach { c -> Swatch(Color(c), value == c) { onPick(c) } }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, label: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, color = contentOn(color), fontWeight = FontWeight.Bold)
        else if (selected) Text("✓", color = contentOn(color), fontWeight = FontWeight.Bold)
    }
}
