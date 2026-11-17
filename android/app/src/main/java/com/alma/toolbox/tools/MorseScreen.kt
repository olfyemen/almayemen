package com.alma.toolbox.tools

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alma.toolbox.check.playTone
import com.alma.toolbox.ui.*
import kotlinx.coroutines.*

object Morse {
    val latin = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
        'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
        'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
        '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '/' to "-..-.", '(' to "-.--.", ')' to "-.--.-", '&' to ".-...",
        ':' to "---...", '=' to "-...-", '+' to ".-.-.", '-' to "-....-", '"' to ".-..-.", '@' to ".--.-.",
    )
    val arabic = mapOf(
        'ا' to ".-", 'ب' to "-...", 'ت' to "-", 'ث' to "-.-.", 'ج' to ".---", 'ح' to "....", 'خ' to "---", 'د' to "-..", 'ذ' to "--..", 'ر' to ".-.",
        'ز' to "---.", 'س' to "...", 'ش' to "----", 'ص' to "-..-", 'ض' to "...-", 'ط' to "..-", 'ظ' to "-.--", 'ع' to ".-.-", 'غ' to "--.", 'ف' to "..-.",
        'ق' to "--.-", 'ك' to "-.-", 'ل' to ".-..", 'م' to "--", 'ن' to "-.", 'ه' to "..-..", 'و' to ".--", 'ي' to "..", 'ء' to ".",
    )
    private val norm = mapOf('أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ى' to 'ي', 'ة' to 'ه', 'ؤ' to 'و', 'ئ' to 'ي', '،' to ',', '؟' to '?',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4', '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9')

    fun encode(text: String): String = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" / ") { w ->
        w.mapNotNull { c0 -> val c = norm[c0] ?: c0.uppercaseChar(); arabic[c] ?: latin[c] }.joinToString(" ")
    }

    fun decode(code: String, ar: Boolean): String {
        val rev = (if (ar) arabic + latin.filterKeys { it.isDigit() || !it.isLetter() } else latin).entries.associate { it.value to it.key }
        return code.trim().replace("—", "-").replace("_", "-").replace("•", ".").replace("·", ".")
            .split(Regex("\\s*/\\s*|\\s{3,}")).joinToString(" ") { w -> w.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { rev[it] ?: '؟' }.joinToString("") }
    }
}

@Composable
fun MorseScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var decodeAr by remember { mutableStateOf(true) }
    var wpm by remember { mutableIntStateOf(12) }
    var useFlash by remember { mutableStateOf(true) }
    var useSound by remember { mutableStateOf(true) }
    var useVib by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(false) }
    var lit by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    val torch = remember { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
    val vib: Vibrator = remember { if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java) }
    fun setTorch(on: Boolean) { if (useFlash && torch != null) runCatching { cm.setTorchMode(torch, on) } }
    DisposableEffect(Unit) { onDispose { job?.cancel(); if (torch != null) runCatching { cm.setTorchMode(torch, false) } } }

    fun play(seq: String) {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            val unit = 1200L / wpm
            do {
                for (ch in seq) {
                    if (!isActive) break
                    when (ch) {
                        '.', '-' -> {
                            val d = if (ch == '.') unit else unit * 3
                            lit = true; setTorch(true)
                            if (useVib) vib.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE))
                            if (useSound) launch(Dispatchers.IO) { playTone(700.0, d.toInt()) }
                            delay(d); lit = false; setTorch(false); delay(unit)
                        }
                        ' ' -> delay(unit * 2)
                        '/' -> delay(unit * 4)
                    }
                }
                if (loop) delay(unit * 7)
            } while (loop && isActive)
            lit = false; setTorch(false)
        }
    }
    val playing = job?.isActive == true

    ToolScaffold("شفرة مورس 📡", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = "من نص إلى مورس") {
                OutlinedTextField(text, { text = it; code = Morse.encode(it) }, label = { Text("اكتب نصاً (عربي أو إنجليزي)") }, modifier = Modifier.fillMaxWidth())
            }
            SectionCard(title = "شفرة مورس") {
                OutlinedTextField(code, { code = it; text = Morse.decode(it, decodeAr) }, label = { Text("نقاط وشرطات (. -) · فراغ بين الحروف · / بين الكلمات") },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(decodeAr, { decodeAr = true; text = Morse.decode(code, true) }, label = { Text("فك بالعربي") })
                    FilterChip(!decodeAr, { decodeAr = false; text = Morse.decode(code, false) }, label = { Text("English") })
                }
                Text("لوحة إدخال سريعة:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("•" to ".", "—" to "-", "حرف ␣" to " ", "كلمة /" to " / ").forEach { (l, v) ->
                        FilledTonalButton(onClick = { code += v; text = Morse.decode(code, decodeAr) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(l) }
                    }
                    IconButton(onClick = { code = code.dropLast(1); text = Morse.decode(code, decodeAr) }) { Text("⌫", fontSize = 20.sp) }
                }
            }
            SectionCard(title = "الإرسال") {
                Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(16.dp)).background(if (lit) Color(0xFFFFE066) else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Text(if (lit) "💡" else if (playing) "…" else "جاهز", fontSize = 28.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(useFlash, { useFlash = !useFlash }, label = { Text("🔦 فلاش") }, enabled = torch != null)
                    FilterChip(useSound, { useSound = !useSound }, label = { Text("🔊 صوت") })
                    FilterChip(useVib, { useVib = !useVib }, label = { Text("📳 اهتزاز") })
                }
                SwitchRow("تكرار مستمر", loop) { loop = it }
                Stepper("السرعة", wpm, 5..30, 1, "كلمة/د", listOf(8, 12, 18, 25)) { wpm = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (playing) { job?.cancel(); lit = false; setTorch(false) } else play(code) }, enabled = code.isNotBlank() || playing, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(if (playing) "⏹ إيقاف" else "▶ إرسال", fontSize = 18.sp)
                    }
                    Button(onClick = { code = "... --- ..."; text = "SOS"; play(code) }, colors = ButtonDefaults.buttonColors(containerColor = Bad), modifier = Modifier.height(56.dp)) {
                        Text("SOS", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                }
            }
            SectionCard(title = "جدول الحروف") {
                val all = Morse.arabic.entries.toList() + Morse.latin.entries.filter { it.key.isLetterOrDigit() }
                all.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (c, m) -> Text("$c  $m", Modifier.weight(1f), fontFamily = FontFamily.Monospace) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
