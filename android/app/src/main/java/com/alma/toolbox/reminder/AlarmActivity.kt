package com.alma.toolbox.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.AlmaTheme
import com.alma.toolbox.ui.Bad
import com.alma.toolbox.ui.fmtTime
import kotlinx.coroutines.delay
import java.time.LocalTime

class AlarmActivity : ComponentActivity() {
    private val stopped = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { finish() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stores.init(this)
        ContextCompat.registerReceiver(this, stopped, IntentFilter(AlarmSoundService.ACTION_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val id = intent.getLongExtra("id", 0L)
        val r = Stores.reminders.value.items.firstOrNull { it.id == id }
        setContent {
            AlmaTheme {
                var now by remember { mutableStateOf(LocalTime.now()) }
                LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(1000) } }
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("⏰", fontSize = 80.sp)
                    Text(fmtTime(now.hour * 60 + now.minute), fontSize = 54.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(12.dp))
                    Text(r?.title?.ifBlank { "منبه" } ?: "منبه", fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    if (!r?.note.isNullOrBlank()) Text(r!!.note, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(40.dp))
                    Button(onClick = { stop(); finish() }, modifier = Modifier.fillMaxWidth().height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = Bad)) {
                        Text("إيقاف", fontSize = 22.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    val sm = r?.snoozeMin ?: 10
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(sm, 5, 15, 30).distinct().take(3).forEach { m ->
                            OutlinedButton(onClick = { stop(); ReminderScheduler.snooze(this@AlarmActivity, id, m); finish() }, modifier = Modifier.weight(1f).height(56.dp)) { Text("غفوة $m د") }
                        }
                    }
                }
            }
        }
    }

    private fun stop() = startService(Intent(this, AlarmSoundService::class.java).setAction(AlarmSoundService.ACTION_STOP))

    override fun onDestroy() { unregisterReceiver(stopped); super.onDestroy() }
}
