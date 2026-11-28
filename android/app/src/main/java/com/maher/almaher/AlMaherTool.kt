package com.maher.almaher

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.maher.almaher.ui.AboutScreen
import com.maher.almaher.ui.CalculatorScreen
import com.maher.almaher.ui.HistoryScreen
import com.maher.almaher.ui.SettingsScreen

/** Keeps one calculator state for the whole app process (survives leaving and reopening the tool). */
private object ToolState {
    var vm: CalcViewModel? = null
    fun get(app: Application) = vm ?: CalcViewModel(app).also { vm = it }
}

/**
 * حاسبة الماهر as a tool inside another app — the only function the host needs to call.
 *
 * Everything is included: simple and scientific modes, the 200-operation history, UN (10 undo steps),
 * ANS, ♻️ recurring operation, all settings (digits, separators, sizes, key height, colours,
 * sound, vibration) and the About page.
 *
 * @param onBack called when the user leaves the tool (back arrow at the top or the system back key).
 */
@Composable
fun AlMaherCalculatorTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm = remember { ToolState.get(ctx.applicationContext as Application) }
    val fb = remember { Feedback(ctx.applicationContext) }
    var screen by rememberSaveable { mutableStateOf("calc") }

    // save the line being typed when the app goes to the background or the tool is closed
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.persist() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.persist() }
    }

    BackHandler { if (screen == "calc") onBack() else screen = if (screen == "about") "settings" else "calc" }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (screen) {
            "history" -> HistoryScreen(vm, onBack = { screen = "calc" }) { vm.loadFromHistory(it); screen = "calc" }
            "settings" -> SettingsScreen(vm, onAbout = { screen = "about" }) { screen = "calc" }
            "about" -> AboutScreen { screen = "settings" }
            else -> CalculatorScreen(
                vm, fb,
                onHistory = { screen = "history" },
                onSettings = { screen = "settings" },
                onBack = onBack,
            )
        }
    }
}
