package com.alma.toolbox.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.alma.toolbox.ui.*
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File

private fun wifiEsc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:")

object Qr {
    fun encode(text: String, size: Int = 800): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun decode(bmp: Bitmap): Result? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val bb = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px)))
        return runCatching { MultiFormatReader().decode(bb, mapOf(DecodeHintType.TRY_HARDER to true)) }.getOrNull()
    }

    fun kind(t: String) = when {
        t.startsWith("http://", true) || t.startsWith("https://", true) -> "رابط"
        t.startsWith("WIFI:", true) -> "شبكة واي فاي"
        t.startsWith("tel:", true) -> "رقم هاتف"
        t.startsWith("BEGIN:VCARD", true) -> "جهة اتصال"
        t.startsWith("mailto:", true) -> "بريد"
        else -> "نص"
    }
}

@Composable
fun QrScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ToolScaffold("رمز QR 🔳", onBack) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
                Tab(tab == 0, { tab = 0 }, text = { Text("قراءة") }); Tab(tab == 1, { tab = 1 }, text = { Text("إنشاء") })
            }
            if (tab == 0) QrRead() else QrMake()
        }
    }
}

@Composable
private fun QrRead() {
    val ctx = LocalContext.current
    val results = remember { mutableStateListOf<Pair<String, String>>() }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { results.add(0, it to (r.formatName ?: "")) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val bmp = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            val r = bmp?.let { Qr.decode(it) }
            if (r == null) Toast.makeText(ctx, "لم يُعثر على رمز في الصورة", Toast.LENGTH_SHORT).show() else results.add(0, r.text to r.barcodeFormat.name)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            scan.launch(ScanOptions().setPrompt("وجّه الكاميرا نحو الرمز").setBeepEnabled(true).setOrientationLocked(false))
        }, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("📷 مسح بالكاميرا") }
        OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) { Text("🖼️ قراءة من صورة") }
        Text("يقرأ QR والباركود بأنواعه.", style = MaterialTheme.typography.bodySmall)
        results.forEach { (text, fmt) ->
            SectionCard {
                Row { Pill(Qr.kind(text), Accent); Spacer(Modifier.width(6.dp)); Pill(fmt, MaterialTheme.colorScheme.outline) }
                Text(text, fontWeight = FontWeight.SemiBold)
                Row {
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("qr", text))
                        Toast.makeText(ctx, "تم النسخ", Toast.LENGTH_SHORT).show()
                    }) { Text("نسخ") }
                    if (Qr.kind(text) in listOf("رابط", "رقم هاتف", "بريد")) TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text))) } }) { Text("فتح") }
                    TextButton(onClick = { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }) { Text("مشاركة") }
                }
            }
        }
    }
}

@Composable
private fun QrMake() {
    val ctx = LocalContext.current
    var kind by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val content = when (kind) {
        1 -> if (ssid.isBlank()) "" else "WIFI:T:${if (pass.isBlank()) "nopass" else "WPA"};S:${wifiEsc(ssid)};P:${wifiEsc(pass)};;"
        2 -> if (text.isBlank()) "" else "tel:" + text.filter { it.isDigit() || it == '+' }
        else -> text
    }
    val bmp = remember(content) { if (content.isBlank()) null else runCatching { Qr.encode(content) }.getOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("نص أو رابط", "واي فاي", "رقم هاتف").forEachIndexed { i, l -> FilterChip(kind == i, { kind = i }, label = { Text(l) }) }
        }
        when (kind) {
            1 -> {
                OutlinedTextField(ssid, { ssid = it }, label = { Text("اسم الشبكة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            else -> OutlinedTextField(text, { text = it }, label = { Text(if (kind == 2) "رقم الهاتف" else "النص أو الرابط") }, modifier = Modifier.fillMaxWidth())
        }
        if (bmp != null) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(bmp.asImageBitmap(), null, Modifier.size(260.dp).background(androidx.compose.ui.graphics.Color.White))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val f = File(File(ctx.cacheDir, "shared").apply { mkdirs() }, "qr.png")
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                }) { Text("مشاركة") }
                OutlinedButton(onClick = {
                    val ok = runCatching {
                        val cv = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "QR_${System.currentTimeMillis()}.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/حقيبة الما")
                        }
                        val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                        ctx.contentResolver.openOutputStream(u)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }.isSuccess
                    Toast.makeText(ctx, if (ok) "تم الحفظ في الصور ✓" else "تعذر الحفظ", Toast.LENGTH_SHORT).show()
                }) { Text("حفظ في الصور") }
            }
        }
    }
}
