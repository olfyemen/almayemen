package com.maher.almaher.ui

import com.maher.almaher.S
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PHONE = "00967777408089"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
    fun open(uri: String, action: String) {
        try {
            ctx.startActivity(Intent(action, Uri.parse(uri)))
        } catch (_: Exception) {
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(S.about) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF0B4F3A), Color(0xFF1E8C5E))))
            ) {
                CalcIcon(Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Text(S.app_name, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(S.version(com.maher.almaher.TOOL_VERSION), color = cs.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("تصميم م/ماهر العلفي", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("ضوران / آنس", fontSize = 18.sp, color = cs.onSurfaceVariant)
                    Text("⁦$PHONE⁩", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("\"نبدأ من حيث انتهى الآخرون\"", fontSize = 18.sp, textAlign = TextAlign.Center)
                    Text("\"يدٌ ويد\"", fontSize = 18.sp, textAlign = TextAlign.Center)
                    Text("نسألكم الدعاء", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.primary, textAlign = TextAlign.Center)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "إهداء لفضل العلفي",
                        fontSize = 19.sp, fontWeight = FontWeight.Bold,
                        color = cs.primary, textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                Button(onClick = { open("tel:$PHONE", Intent.ACTION_DIAL) }) {
                    Icon(Icons.Filled.Call, null)
                    Spacer(Modifier.width(8.dp))
                    Text(S.call)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { open("https://wa.me/${PHONE.removePrefix("00")}", Intent.ACTION_VIEW) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Spacer(Modifier.width(8.dp))
                    Text(S.whatsapp)
                }
            }
        }
    }
}

/** The app icon drawn in code (white calculator, big orange plus) so no drawable resource is needed. */
@Composable
fun CalcIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val k = size.minDimension / 108f
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float, c: Color) = drawRoundRect(
            c, topLeft = androidx.compose.ui.geometry.Offset(x * k, y * k),
            size = androidx.compose.ui.geometry.Size(w * k, h * k),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * k, r * k)
        )
        rr(34f, 27f, 42f, 58f, 7f, Color(0x33000000))
        rr(33f, 25f, 42f, 58f, 7f, Color.White)
        rr(38f, 30f, 32f, 14f, 3f, Color(0xFF0B4F3A))
        rr(56f, 34.5f, 10f, 2f, 0f, Color(0xFF4FC3F7))
        rr(56f, 38.5f, 10f, 2f, 0f, Color(0xFF4FC3F7))
        rr(49.5f, 50f, 9f, 26f, 1.5f, Color(0xFFFF8F00))
        rr(40f, 58.5f, 28f, 9f, 1.5f, Color(0xFFFF8F00))
    }
}