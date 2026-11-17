package com.alma.toolbox.check

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.*
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.alma.toolbox.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

data class HwTest(val id: String, val emoji: String, val name: String, val desc: String)

val HwTests = listOf(
    HwTest("screen", "🖥️", "الشاشة والألوان", "ألوان كاملة لاكتشاف البقع والبكسلات الميتة"),
    HwTest("touch", "👆", "اللمس", "مرّر إصبعك على كل المربعات"),
    HwTest("multi", "🖐️", "اللمس المتعدد", "عدد الأصابع المدعومة معاً"),
    HwTest("speaker", "🔊", "السماعة الخارجية", "يسار / يمين / الاثنتان"),
    HwTest("earpiece", "📞", "سماعة المكالمات", "ضع الهاتف على أذنك"),
    HwTest("mic", "🎙️", "الميكروفون", "سجّل 3 ثوانٍ واستمع"),
    HwTest("vibrate", "📳", "الاهتزاز", "محرك الاهتزاز"),
    HwTest("flash", "🔦", "الفلاش", "تشغيل وإطفاء الكشاف"),
    HwTest("cam_back", "📷", "الكاميرا الخلفية", "التقط صورة"),
    HwTest("cam_front", "🤳", "الكاميرا الأمامية", "التقط صورة سيلفي"),
    HwTest("proximity", "✋", "حساس القرب", "قرّب يدك من أعلى الشاشة"),
    HwTest("light", "💡", "حساس الضوء", "غطِّ الحساس أو وجّهه للضوء"),
    HwTest("motion", "🎯", "الحركة والميلان", "حرّك الهاتف لتتحرك الكرة"),
    HwTest("compass", "🧭", "البوصلة", "المجال المغناطيسي والاتجاه"),
    HwTest("buttons", "🔘", "الأزرار", "اضغط أزرار الصوت"),
    HwTest("finger", "🔐", "البصمة", "التحقق بالبصمة أو الوجه"),
    HwTest("connect", "📡", "الاتصالات", "واي فاي، بلوتوث، GPS، NFC، الشريحة"),
    HwTest("ports", "🔌", "المنافذ", "منفذ الشحن والسماعة"),
)

private fun vibrator(ctx: Context): Vibrator = if (Build.VERSION.SDK_INT >= 31)
    ctx.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

/** Plays a sine tone. pan: -1 left, 0 both, 1 right. Blocking — call from IO. */
fun playTone(freq: Double, ms: Int, usage: Int = AudioAttributes.USAGE_MEDIA, pan: Float = 0f) {
    val rate = 44100
    val n = rate * ms / 1000
    val buf = ShortArray(n * 2)
    val lg = if (pan > 0) 0f else 1f; val rg = if (pan < 0) 0f else 1f
    for (i in 0 until n) {
        val env = min(1.0, min(i, n - i) / (rate * 0.02)) // soft fade in/out
        val s = (sin(2 * PI * freq * i / rate) * env * 0.8 * Short.MAX_VALUE)
        buf[2 * i] = (s * lg).toInt().toShort(); buf[2 * i + 1] = (s * rg).toInt().toShort()
    }
    val t = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
    try { t.write(buf, 0, buf.size); t.play(); Thread.sleep(ms.toLong() + 100) } finally { t.release() }
}

@Composable
fun TestRunner(test: HwTest, onResult: (Boolean?) -> Unit) {
    when (test.id) {
        "screen" -> ScreenColors(onResult)
        "touch" -> TouchGrid(onResult)
        "multi" -> FullScreen(onResult) { MultiTouch() }
        else -> AlertDialog(
            onDismissRequest = { onResult(null) },
            title = { Text("${test.emoji} ${test.name}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { TestBody(test.id) } },
            confirmButton = { Button(onClick = { onResult(true) }, colors = ButtonDefaults.buttonColors(containerColor = Good)) { Text("يعمل ✓") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onResult(null) }) { Text("إغلاق") }
                    TextButton(onClick = { onResult(false) }) { Text("لا يعمل ✗", color = Bad) }
                }
            }
        )
    }
}

@Composable
private fun FullScreen(onResult: (Boolean?) -> Unit, content: @Composable BoxScope.() -> Unit) {
    Dialog({ onResult(null) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            content()
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onResult(true) }, colors = ButtonDefaults.buttonColors(containerColor = Good)) { Text("يعمل ✓") }
                OutlinedButton(onClick = { onResult(false) }) { Text("لا يعمل ✗") }
                TextButton(onClick = { onResult(null) }) { Text("إغلاق") }
            }
        }
    }
}

@Composable
private fun ScreenColors(onResult: (Boolean?) -> Unit) {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black, Color(0xFF808080))
    var i by remember { mutableIntStateOf(0) }
    Dialog({ onResult(null) }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        if (i < colors.size) {
            Box(Modifier.fillMaxSize().background(colors[i]).clickable { i++ }, contentAlignment = Alignment.BottomCenter) {
                Text("اضغط للون التالي (${i + 1}/${colors.size + 1})", color = if (i == 3) Color.Black else Color.White, modifier = Modifier.padding(40.dp).navigationBarsPadding())
            }
        } else if (i == colors.size) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black, Color.White))).clickable { i++ }, contentAlignment = Alignment.BottomCenter) {
                Text("تدرج: يجب أن يكون ناعماً بلا خطوط · اضغط", color = Color.Red, modifier = Modifier.padding(40.dp).navigationBarsPadding())
            }
        } else {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("هل ظهرت كل الألوان بلا بقع أو خطوط أو بكسلات ميتة؟", textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onResult(true) }, colors = ButtonDefaults.buttonColors(containerColor = Good)) { Text("نعم ✓") }
                        OutlinedButton(onClick = { onResult(false) }) { Text("لا ✗") }
                        TextButton(onClick = { i = 0 }) { Text("إعادة") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchGrid(onResult: (Boolean?) -> Unit) {
    val cols = 8; val rows = 14
    val hit = remember { mutableStateListOf<Int>() }
    val good = Good.copy(alpha = .8f); val line = MaterialTheme.colorScheme.outline.copy(alpha = .4f)
    FullScreen(onResult) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    e.changes.forEach { c ->
                        val cx = (c.position.x / size.width * cols).toInt(); val cy = (c.position.y / size.height * rows).toInt()
                        if (cx in 0 until cols && cy in 0 until rows) { val k = cy * cols + cx; if (k !in hit) hit.add(k) }
                        c.consume()
                    }
                }
            }
        }) {
            val w = size.width / cols; val h = size.height / rows
            for (k in hit) drawRect(good, Offset((k % cols) * w, (k / cols) * h), androidx.compose.ui.geometry.Size(w, h))
            for (x in 0..cols) drawLine(line, Offset(x * w, 0f), Offset(x * w, size.height))
            for (y in 0..rows) drawLine(line, Offset(0f, y * h), Offset(size.width, y * h))
        }
        Text("${hit.size} / ${cols * rows}", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (hit.size == cols * rows) LaunchedEffect(Unit) { onResult(true) }
    }
}

@Composable
private fun BoxScope.MultiTouch() {
    val pts = remember { mutableStateListOf<Offset>() }
    var maxN by remember { mutableIntStateOf(0) }
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                pts.clear(); e.changes.filter { it.pressed }.forEach { pts.add(it.position); it.consume() }
                maxN = max(maxN, pts.size)
            }
        }
    }) { pts.forEachIndexed { i, p -> drawCircle(SeriesColors[i % 4].copy(alpha = .6f), 70f, p) } }
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${pts.size}", fontSize = 80.sp, fontWeight = FontWeight.ExtraBold)
        Text("أصابع الآن · الأقصى: $maxN")
        Text("ضع أكبر عدد من الأصابع على الشاشة", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun rememberSensor(type: Int): State<FloatArray?> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val s = sm.getDefaultSensor(type)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { st.value = e.values.copyOf() }
            override fun onAccuracyChanged(p0: Sensor?, p1: Int) {}
        }
        if (s != null) sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return st
}

@Composable
private fun Missing(text: String) = Text("⚠ $text", color = Bad, fontWeight = FontWeight.Bold)

@Composable
private fun has(type: Int): Boolean { val ctx = LocalContext.current; return remember { ctx.getSystemService(SensorManager::class.java).getDefaultSensor(type) != null } }

@SuppressLint("MissingPermission")
@Composable
private fun TestBody(id: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    when (id) {
        "speaker" -> {
            Text("اضغط واستمع للنغمة من كل جهة:")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("يمين" to 1f, "الاثنتان" to 0f, "يسار" to -1f).forEach { (l, p) ->
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { playTone(660.0, 900, pan = p) } }) { Text(l) }
                }
            }
            OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { listOf(262.0, 330.0, 392.0, 523.0, 784.0, 1046.0, 2093.0).forEach { playTone(it, 350) } } }) { Text("نغمات متدرجة (منخفض ← عالٍ)") }
            Text("ارفع الصوت أولاً. الهاتف بسماعة واحدة يُخرج الجهتين من نفس السماعة.", style = MaterialTheme.typography.bodySmall)
        }
        "earpiece" -> {
            Text("اضغط ثم ضع الهاتف على أذنك كأنك في مكالمة:")
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    val am = ctx.getSystemService(AudioManager::class.java)
                    val old = am.mode
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
                    try { repeat(3) { playTone(440.0, 700, AudioAttributes.USAGE_VOICE_COMMUNICATION); Thread.sleep(250) } } finally { am.mode = old }
                }
            }) { Text("تشغيل نغمة في سماعة الأذن") }
        }
        "mic" -> MicTest()
        "vibrate" -> {
            val v = remember { vibrator(ctx) }
            if (!v.hasVibrator()) Missing("لا يوجد محرك اهتزاز")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { v.vibrate(VibrationEffect.createOneShot(600, VibrationEffect.DEFAULT_AMPLITUDE)) }) { Text("قصير") }
                OutlinedButton(onClick = { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 150, 200, 150, 600), -1)) }) { Text("نمط") }
                OutlinedButton(onClick = { v.vibrate(VibrationEffect.createOneShot(2000, 255)) }) { Text("قوي") }
            }
            if (Build.VERSION.SDK_INT >= 26) Text("التحكم بالقوة: ${if (v.hasAmplitudeControl()) "مدعوم" else "غير مدعوم"}", style = MaterialTheme.typography.bodySmall)
        }
        "flash" -> {
            val cm = remember { ctx.getSystemService(CameraManager::class.java) }
            val camId = remember { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
            var on by remember { mutableStateOf(false) }
            DisposableEffect(Unit) { onDispose { if (camId != null) runCatching { cm.setTorchMode(camId, false) } } }
            if (camId == null) Missing("لا يوجد فلاش")
            else Button(onClick = { on = !on; runCatching { cm.setTorchMode(camId, on) } }, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text(if (on) "إطفاء 🔦" else "تشغيل 🔦", fontSize = 20.sp) }
        }
        "cam_back", "cam_front" -> CameraTest(front = id == "cam_front")
        "proximity" -> {
            if (!has(Sensor.TYPE_PROXIMITY)) Missing("لا يوجد حساس قرب")
            val v by rememberSensor(Sensor.TYPE_PROXIMITY)
            val sm = remember { ctx.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_PROXIMITY) }
            val near = v != null && v!![0] < (sm?.maximumRange ?: 5f)
            Text(if (near) "✋ قريب" else "👋 بعيد", fontSize = 40.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text("القراءة: ${v?.get(0) ?: "—"} سم", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        "light" -> {
            if (!has(Sensor.TYPE_LIGHT)) Missing("لا يوجد حساس ضوء")
            val v by rememberSensor(Sensor.TYPE_LIGHT)
            val lux = v?.get(0) ?: 0f
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Gauge(log10(lux + 1), 0f, 4.5f, String.format(Locale.US, "%.0f لوكس", lux), Warn) }
        }
        "motion" -> {
            if (!has(Sensor.TYPE_ACCELEROMETER)) Missing("لا يوجد مقياس تسارع")
            val v by rememberSensor(Sensor.TYPE_ACCELEROMETER)
            val g by rememberSensor(Sensor.TYPE_GYROSCOPE)
            val x = v?.get(0) ?: 0f; val y = v?.get(1) ?: 0f
            val ring = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val c = center; val r = size.minDimension / 2 - 20
                drawCircle(ring, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                drawCircle(ring, r / 3, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                val bx = (c.x - x / 9.81f * r).coerceIn(c.x - r, c.x + r); val by = (c.y + y / 9.81f * r).coerceIn(c.y - r, c.y + r)
                drawCircle(if (abs(x) < .4f && abs(y) < .4f) Good else Accent, 30f, Offset(bx, by))
            }
            Text(String.format(Locale.US, "ميل X: %.1f°  Y: %.1f°", Math.toDegrees(asin((x / 9.81f).coerceIn(-1f, 1f).toDouble())), Math.toDegrees(asin((y / 9.81f).coerceIn(-1f, 1f).toDouble()))))
            Text(if (g != null) "الجيروسكوب: يعمل ✓" else if (has(Sensor.TYPE_GYROSCOPE)) "الجيروسكوب: بانتظار قراءة" else "الجيروسكوب: غير موجود", style = MaterialTheme.typography.bodySmall)
            Text("يصلح أيضاً كميزان ماء: الكرة خضراء عندما يكون الهاتف مستوياً.", style = MaterialTheme.typography.bodySmall)
        }
        "compass" -> {
            if (!has(Sensor.TYPE_MAGNETIC_FIELD)) Missing("لا يوجد حساس مغناطيسي")
            val m by rememberSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val a by rememberSensor(Sensor.TYPE_ACCELEROMETER)
            var heading = 0f
            if (m != null && a != null) {
                val rm = FloatArray(9); val o = FloatArray(3)
                if (SensorManager.getRotationMatrix(rm, null, a, m)) { SensorManager.getOrientation(rm, o); heading = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat() }
            }
            val strength = m?.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) } ?: 0f
            val col = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val r = size.minDimension / 2 - 10
                drawCircle(col.copy(alpha = .2f), r, center, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                val ang = Math.toRadians(-heading.toDouble() - 90)
                drawLine(Bad, center, Offset(center.x + (r * cos(ang)).toFloat(), center.y + (r * sin(ang)).toFloat()), 10f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Text(String.format(Locale.US, "الاتجاه: %.0f° · شدة المجال %.0f µT", heading, strength), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text("الإبرة الحمراء تشير للشمال. الطبيعي 25–65 µT؛ حرّك الهاتف بشكل ∞ للمعايرة.", style = MaterialTheme.typography.bodySmall)
        }
        "buttons" -> {
            val seen = remember { mutableStateListOf<String>() }
            val fr = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            Box(Modifier.fillMaxWidth().height(140.dp).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
                val name = when (e.key) {
                    Key.VolumeUp -> "رفع الصوت"; Key.VolumeDown -> "خفض الصوت"; Key.Back -> null
                    Key.HeadsetHook -> "زر السماعة"; Key.MediaPlayPause -> "تشغيل/إيقاف"
                    else -> "زر ${e.key.keyCode}"
                }
                if (name != null && e.type == KeyEventType.KeyDown) { if (name !in seen) seen.add(name); true } else name != null
            }.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Text(if (seen.isEmpty()) "اضغط زر رفع الصوت ثم خفضه" else seen.joinToString("\n") { "✓ $it" }, textAlign = TextAlign.Center)
            }
            Text("زر التشغيل لا يمكن فحصه من التطبيقات.", style = MaterialTheme.typography.bodySmall)
        }
        "finger" -> {
            val bm = remember { BiometricManager.from(ctx) }
            val can = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            var res by remember { mutableStateOf("") }
            Text(when (can) {
                BiometricManager.BIOMETRIC_SUCCESS -> "البصمة/الوجه متوفرة ومسجلة ✓"
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "لا يوجد حساس بصمة"
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "يوجد حساس لكن لا توجد بصمة مسجلة"
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "الحساس غير متاح حالياً"
                else -> "غير مدعوم"
            })
            if (can == BiometricManager.BIOMETRIC_SUCCESS) Button(onClick = {
                val act = ctx as? FragmentActivity ?: return@Button
                BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { res = "✓ تم التعرف بنجاح" }
                    override fun onAuthenticationFailed() { res = "✗ لم يتم التعرف، حاول مجدداً" }
                    override fun onAuthenticationError(c: Int, s: CharSequence) { res = s.toString() }
                }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("فحص البصمة").setNegativeButtonText("إلغاء")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build())
            }) { Text("ابدأ الفحص") }
            if (res.isNotEmpty()) Text(res, fontWeight = FontWeight.Bold)
        }
        "connect" -> {
            val wifi = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
            val bt = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
            val lm = remember { ctx.getSystemService(LocationManager::class.java) }
            val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
            val tm = remember { ctx.getSystemService(TelephonyManager::class.java) }
            val pm = ctx.packageManager
            fun st(present: Boolean, on: Boolean?) = if (!present) "غير موجود ✗" else when (on) { true -> "موجود · مفعّل ✓"; false -> "موجود · مغلق"; null -> "موجود" }
            ValueRow("📶 واي فاي", st(pm.hasSystemFeature(PackageManager.FEATURE_WIFI), wifi?.isWifiEnabled))
            ValueRow("🔵 بلوتوث", st(bt != null, runCatching { bt?.isEnabled }.getOrNull()))
            ValueRow("📍 GPS", st(pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS), lm.isProviderEnabled(LocationManager.GPS_PROVIDER)))
            ValueRow("💳 NFC", st(nfc != null, nfc?.isEnabled))
            ValueRow("📱 الشريحة", when (tm?.simState) {
                TelephonyManager.SIM_STATE_READY -> "جاهزة ✓ (${tm.networkOperatorName.ifBlank { tm.simOperatorName }})"
                TelephonyManager.SIM_STATE_ABSENT -> "لا توجد شريحة"; TelephonyManager.SIM_STATE_PIN_REQUIRED -> "تحتاج PIN"
                null -> "لا يدعم الاتصال"; else -> "غير جاهزة"
            })
            if (Build.VERSION.SDK_INT >= 30) ValueRow("📶 5 جيجا هرتز واي فاي", if (wifi?.is5GHzBandSupported == true) "مدعوم" else "غير مدعوم")
            ValueRow("🛰️ الأشعة تحت الحمراء", if (pm.hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)) "موجود" else "غير موجود")
        }
        "ports" -> {
            val b by rememberBattery()
            val am = remember { ctx.getSystemService(AudioManager::class.java) }
            var tick by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
            val outs = remember(tick) { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }
            val head = outs.any { it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_USB_HEADSET }
            val btHead = outs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ValueRow("🔌 منفذ الشحن", if (b.plugged != 0) "موصول (${plugName(b.plugged)}) ✓" else "صِل الشاحن للفحص")
            ValueRow("⚡ التيار", b.currentMa?.let { "$it mA" } ?: "—")
            ValueRow("🎧 سماعة سلكية", if (head) "موصولة ✓" else "صِل السماعة للفحص")
            ValueRow("🎧 سماعة بلوتوث", if (btHead) "موصولة ✓" else "غير موصولة")
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun MicTest() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var level by remember { mutableFloatStateOf(0f) }
    var state by remember { mutableStateOf("جاهز") }
    var rec by remember { mutableStateOf<ShortArray?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    if (!granted) { Button(onClick = { perm.launch(Manifest.permission.RECORD_AUDIO) }) { Text("السماح بالميكروفون") }; return }
    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(14.dp))
    Text(state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                val rate = 16000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
                val all = ShortArray(rate * 3); var off = 0
                state = "🔴 يسجّل… تكلم الآن"
                r.startRecording()
                while (off < all.size) {
                    val n = r.read(all, off, minOf(1600, all.size - off)); if (n <= 0) break
                    var peak = 0; for (k in off until off + n) peak = max(peak, abs(all[k].toInt()))
                    level = (peak / 32767f).coerceIn(0f, 1f); off += n
                }
                r.stop(); r.release(); level = 0f
                rec = all
                state = "تم التسجيل · اضغط تشغيل"
            }
        }) { Text("🎙️ سجّل 3 ث") }
        OutlinedButton(enabled = rec != null, onClick = {
            val data = rec ?: return@OutlinedButton
            scope.launch(Dispatchers.IO) {
                state = "▶ يُشغّل…"
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(data.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                t.write(data, 0, data.size); t.play(); delay(3200); t.release()
                withContext(Dispatchers.Main) { state = "هل سمعت صوتك بوضوح؟" }
            }
        }) { Text("▶ تشغيل") }
    }
}

@Composable
private fun CameraTest(front: Boolean) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) @Suppress("DEPRECATION") { bmp = r.data?.extras?.get("data") as? Bitmap }
    }
    Button(onClick = {
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (front) i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extras.LENS_FACING_FRONT", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        runCatching { launcher.launch(i) }
    }) { Text(if (front) "افتح الكاميرا الأمامية" else "افتح الكاميرا الخلفية") }
    if (front) Text("إن فتحت الخلفية، بدّل للأمامية من زر الكاميرا.", style = MaterialTheme.typography.bodySmall)
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(200.dp)) }
}
