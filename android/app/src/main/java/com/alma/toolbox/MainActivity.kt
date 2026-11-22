package com.alma.toolbox

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.alma.toolbox.core.Alarms
import com.alma.toolbox.core.Stores
import com.alma.toolbox.ui.AlmaTheme

class MainActivity : FragmentActivity() {
    companion object { const val EXTRA_TOOL = "tool" }

    /** Tool requested by a shortcut / notification, consumed by the nav host. */
    val pendingTool = mutableStateOf<String?>(null)

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Stores.init(this)
        enableEdgeToEdge()
        pendingTool.value = intent?.getStringExtra(EXTRA_TOOL)
        handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        Shortcuts.publishDynamic(this)
        Alarms.rescheduleAll(this)
        setContent { AlmaTheme { AppNav(this) } }
    }

    /** Text shared from another app (e.g. a notes app) becomes a note. */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND || i.type?.startsWith("text/") != true) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val n = com.alma.toolbox.daily.NotesImport.addText(text, i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
        if (n != null) {
            android.widget.Toast.makeText(this, "أُضيفت إلى الملاحظات ✓", android.widget.Toast.LENGTH_SHORT).show()
            pendingTool.value = Tool.NOTES.id
        }
        i.action = null
    }

    private var leftAt = 0L

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) leftAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // re-lock the ledger after a minute away (short trips to WhatsApp / contacts keep it open)
        if (leftAt > 0 && System.currentTimeMillis() - leftAt > 60_000) { com.alma.toolbox.ledger.LedgerLock.lock(); com.alma.toolbox.memories.MemLock.lock() }
        leftAt = 0
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TOOL)?.let { pendingTool.value = it }
        handleShare(intent)
    }
}
