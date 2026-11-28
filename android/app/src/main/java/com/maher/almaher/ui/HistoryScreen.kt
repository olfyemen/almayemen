package com.maher.almaher.ui

import com.maher.almaher.S
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maher.almaher.CalcViewModel
import com.maher.almaher.HISTORY_MAX
import com.maher.almaher.calc.Fmt
import com.maher.almaher.data.HistoryItem
import com.maher.almaher.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US)

private fun line(item: HistoryItem, st: Settings): String {
    val e = Fmt.format(item.expr, st)
    return if (item.result.isEmpty()) e else "$e = ${Fmt.format(item.result, st)}"
}

private fun shareHistory(ctx: Context, items: List<HistoryItem>, st: Settings) {
    val text = buildString {
        appendLine(S.share_title)
        appendLine("────────────")
        items.forEach {
            appendLine(Fmt.digits(dateFmt.format(Date(it.time)), st.arabicDigits))
            appendLine(line(it, st))
            appendLine()
        }
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, S.share_title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, S.share))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(vm: CalcViewModel, onBack: () -> Unit, onPick: (HistoryItem) -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val st = vm.settings
    var confirm by remember { mutableStateOf(false) }
    val copied = S.copied

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(S.history) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
                actions = {
                    IconButton(onClick = { shareHistory(ctx, vm.history, st) }, enabled = vm.history.isNotEmpty()) {
                        Icon(Icons.Filled.Share, S.share)
                    }
                    IconButton(onClick = { confirm = true }, enabled = vm.history.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, S.clear_history)
                    }
                }
            )
        }
    ) { pad ->
        if (vm.history.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(S.history_empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize()) {
                item {
                    Text(
                        Fmt.digits(S.historyCount(vm.history.size, HISTORY_MAX), st.arabicDigits),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    Text(
                        S.history_hint,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                items(vm.history, key = { it.time.toString() + it.expr }) { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPick(item) },
                                onLongClick = {
                                    clipboard.setText(AnnotatedString(line(item, st)))
                                    Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show()
                                }
                            )
                            .padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.deleteHistoryItem(item) }) {
                            Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    Fmt.digits(dateFmt.format(Date(item.time)), st.arabicDigits),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(Fmt.format(item.expr, st), fontSize = 20.sp, textAlign = TextAlign.End)
                                if (item.result.isNotEmpty()) {
                                    Text(
                                        "= " + Fmt.format(item.result, st),
                                        fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(S.clear_history) },
            text = { Text(S.clear_history_confirm) },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirm = false }) { Text(S.delete) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(S.cancel) }
            }
        )
    }
}
