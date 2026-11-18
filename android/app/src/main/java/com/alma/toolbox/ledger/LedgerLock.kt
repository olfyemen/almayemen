package com.alma.toolbox.ledger

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.Bad
import java.security.MessageDigest

object LedgerLock {
    /** Unlocked for this app session; cleared when the app goes to the background. */
    var unlocked by mutableStateOf(false)
    fun hash(pin: String): String = MessageDigest.getInstance("SHA-256").digest(("alma-ledger:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    fun lock() { unlocked = false }
}

fun bioAvailable(ctx: android.content.Context) =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun askBiometric(ctx: android.content.Context, title: String, onOk: () -> Unit) {
    val act = ctx as? FragmentActivity ?: return
    BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onOk()
    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setNegativeButtonText("استخدام الرمز")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build())
}

/** Shows [content] only after the optional ledger PIN / fingerprint is passed. */
@Composable
fun LedgerGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val d by Stores.ledger.state.collectAsStateWithLifecycle()
    if (d.pinHash.isEmpty() || LedgerLock.unlocked) { content(); return }
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val useBio = d.lockBio && bioAvailable(ctx)
    LaunchedEffect(Unit) { if (useBio) askBiometric(ctx, "فتح سجل الحسابات") { LedgerLock.unlocked = true } }
    fun press(c: String) {
        error = false
        pin = if (c == "⌫") pin.dropLast(1) else (pin + c).take(8)
        if (pin.length >= 4 && LedgerLock.hash(pin) == d.pinHash) LedgerLock.unlocked = true
        else if (pin.length >= d.pinLength.coerceAtLeast(4) && c != "⌫") { error = true; pin = "" }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🔒", fontSize = 56.sp)
        Text("سجل الحسابات مقفل", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (error) "رمز خاطئ، حاول مجدداً" else "أدخل الرمز", color = if (error) Bad else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(d.pinLength.coerceAtLeast(4)) { i ->
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Spacer(Modifier.height(24.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (useBio) "👆" else "", "0", "⌫"))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(6.dp)) {
                r.forEach { k ->
                    Box(Modifier.size(72.dp).clip(CircleShape).background(if (k.isEmpty()) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(enabled = k.isNotEmpty()) { if (k == "👆") askBiometric(ctx, "فتح سجل الحسابات") { LedgerLock.unlocked = true } else press(k) },
                        contentAlignment = Alignment.Center) { Text(k, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        TextButton(onClick = onBack) { Text("رجوع") }
    }
}

/** Set / change / remove the PIN. */
@Composable
fun PinSetupDialog(d: LedgerData, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf("") }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val has = d.pinHash.isNotEmpty()
    val num = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
    val pw = androidx.compose.ui.text.input.PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (has) "تغيير رمز القفل" else "قفل سجل الحسابات") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (has) OutlinedTextField(cur, { cur = it.filter(Char::isDigit).take(8) }, label = { Text("الرمز الحالي") }, singleLine = true, keyboardOptions = num, visualTransformation = pw)
                OutlinedTextField(p1, { p1 = it.filter(Char::isDigit).take(8) }, label = { Text("رمز جديد (4–8 أرقام)") }, singleLine = true, keyboardOptions = num, visualTransformation = pw)
                OutlinedTextField(p2, { p2 = it.filter(Char::isDigit).take(8) }, label = { Text("تأكيد الرمز") }, singleLine = true, keyboardOptions = num, visualTransformation = pw)
                if (msg.isNotEmpty()) Text(msg, color = Bad)
                Text("⚠ إن نسيت الرمز لن تستطيع فتح الحسابات، فاحتفظ بنسخة احتياطية.", style = MaterialTheme.typography.bodySmall)
                if (has) TextButton(onClick = {
                    if (LedgerLock.hash(cur) != d.pinHash) msg = "الرمز الحالي خاطئ"
                    else { Stores.ledger.update { it.copy(pinHash = "", pinLength = 4) }; onDismiss() }
                }) { Text("إزالة القفل", color = Bad) }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    has && LedgerLock.hash(cur) != d.pinHash -> msg = "الرمز الحالي خاطئ"
                    p1.length < 4 -> msg = "الرمز 4 أرقام على الأقل"
                    p1 != p2 -> msg = "الرمزان غير متطابقين"
                    else -> {
                        Stores.ledger.update { it.copy(pinHash = LedgerLock.hash(p1), pinLength = p1.length, lockBio = bioAvailable(ctx) && it.lockBio) }
                        LedgerLock.unlocked = true; onDismiss()
                    }
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
