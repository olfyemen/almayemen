package com.alma.toolbox.screentime

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Power

/**
 * Runs only while needed. With the screen off it does nothing (no timers).
 * With the screen on: recounts every minute, shows the small countdown box, and covers apps after the goal.
 */
class ScreenTimeService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private var screenOn = true
    private var baseUsed = 0L
    private var baseAt = 0L
    private var lastBubble = -1L
    private var warned = false
    private var bubbleView: View? = null
    private var lockView: View? = null
    private var lockKind = ""

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action != Intent.ACTION_SCREEN_OFF
            h.removeCallbacks(tick)
            if (screenOn) { baseAt = 0; h.post(tick) } else { hideBubble(); hideLock() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 7301, note("⏳ وقت الشاشة يعمل"), type)
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) })
        screenOn = getSystemService(PowerManager::class.java).isInteractive
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        baseAt = 0
        h.removeCallbacks(tick)
        if (screenOn) h.post(tick)
        return START_STICKY
    }

    private fun note(text: String) = NotificationCompat.Builder(this, Notify.CH_ONGOING).setSmallIcon(R.drawable.ic_notify)
        .setContentTitle(text).setOngoing(true).setSilent(true)
        .setContentIntent(Notify.openApp(this, Tool.SCREEN.id, 73)).build()

    private fun used(): Long {
        val now = System.currentTimeMillis()
        if (baseAt == 0L || now - baseAt > 60_000L) { baseUsed = ScreenTime.todayUsed(this); baseAt = now }
        return baseUsed + (now - baseAt)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!screenOn) return
            val on = ScreenTime.enabled(this@ScreenTimeService)
            val focus = ScreenTime.focusNow(this@ScreenTimeService)
            if (!on && !focus) { stopSelf(); return }
            var fast = false
            val ctx = this@ScreenTimeService
            if (on) {
                val u = used(); val goal = ScreenTime.goalMs(ctx)
                val left = goal - u
                if (lastBubble < 0) lastBubble = u - (u % (if (left <= 3600_000L) 600_000L else 900_000L))
                Notify.nm(ctx).notify(7301, note(if (left > 0) "⏳ المتبقي اليوم ${ScreenTime.clock(left)} من ${ScreenTime.fmt(goal)}" else "⛔ انتهى وقت الشاشة لليوم"))
                if (left > 0) warned = false
                if (!focus && ScreenTime.bubble(ctx) && left > 0 && ScreenTime.bubbleDue(u, lastBubble, goal)) {
                    lastBubble = u; showBubble("⏳ ${ScreenTime.clock(left)}")
                }
                if (left <= 0 && !warned) { warned = true; showBubble("⛔ انتهى وقتك اليوم") }
                if (left <= 0 && ScreenTime.lock(ctx)) fast = true
            }
            if (focus) fast = true
            if (fast && ScreenTime.hasOverlay(ctx)) {
                val fg = ScreenTime.foreground(ctx)
                val allowed = ScreenTime.alwaysAllowed(ctx)
                when {
                    fg != null && focus && fg in ScreenTime.distracting(ctx) && fg !in allowed -> showLock("focus")
                    fg != null && on && ScreenTime.lock(ctx) && ScreenTime.goalMs(ctx) - used() <= 0 && fg !in allowed -> showLock("goal")
                    fg != null -> hideLock()
                }
            } else hideLock()
            h.postDelayed(this, if (fast) 1500L else Power.every(ctx, 60_000L, 120_000L))
        }
    }

    private fun params(full: Boolean) = WindowManager.LayoutParams(
        if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (full) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; if (!full) y = 140 }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showBubble(text: String) {
        if (!ScreenTime.hasOverlay(this)) return
        hideBubble()
        val tv = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10)); alpha = 0f
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xE62A2160.toInt()) }
            elevation = dp(6).toFloat()
        }
        runCatching { wm.addView(tv, params(false)); bubbleView = tv }.onFailure { return }
        tv.animate().alpha(1f).setDuration(350).start()
        h.postDelayed({ if (bubbleView === tv) tv.animate().alpha(0f).setDuration(400).withEndAction { hideBubble() }.start() }, 5000)
    }

    private fun hideBubble() { bubbleView?.let { runCatching { wm.removeView(it) } }; bubbleView = null }

    private fun showLock(kind: String) {
        if (lockView != null && lockKind == kind) return
        hideLock()
        val ctx = this
        val box = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean = if (e.keyCode == KeyEvent.KEYCODE_BACK) true else super.dispatchKeyEvent(e)
        }.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xF51E1B3A.toInt()); setPadding(dp(28), dp(28), dp(28), dp(28)); isClickable = true
        }
        fun label(t: String, size: Float, bold: Boolean = false) = TextView(ctx).apply {
            text = t; setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(6), 0, dp(6))
        }
        fun button(t: String, main: Boolean, act: () -> Unit) = Button(ctx).apply {
            text = t; textSize = 16f; isAllCaps = false
            setTextColor(if (main) Color.WHITE else 0xFF2A2160.toInt())
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (main) 0xFF6C5CE7.toInt() else Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(52)).apply { topMargin = dp(10) }
            setOnClickListener { act() }
        }
        if (kind == "focus") {
            box.addView(label("🍅", 54f))
            box.addView(label("وقت التركيز", 24f, true))
            box.addView(label("هذا التطبيق من المشتتات. ارجع له بعد انتهاء جلسة البومودورو.", 15f))
            box.addView(button("الشاشة الرئيسية", true) { goHome() })
            box.addView(button("إيقاف جلسة التركيز", false) { com.alma.toolbox.pomodoro.PomoEngine.reset(ctx); hideLock() })
        } else {
            box.addView(label("⛔", 54f))
            box.addView(label("انتهى وقت الشاشة لليوم", 24f, true))
            box.addView(label("استخدمت ${ScreenTime.fmt(used())}. اختر مدة إضافية لتكمل، أو ارجع للشاشة الرئيسية.", 15f))
            listOf(15 to "+15 دقيقة", 30 to "+30 دقيقة", 60 to "+ساعة").forEach { (m, t) ->
                box.addView(button(t, m == 15) { ScreenTime.addExtra(ctx, m); warned = false; hideLock(); baseAt = 0 })
            }
            box.addView(button("الشاشة الرئيسية", false) { goHome() })
            box.addView(label("الهاتف والواتساب والتطبيقات المستثناة تعمل دائماً.", 12f))
        }
        runCatching { wm.addView(box, params(true)); lockView = box; lockKind = kind }
    }

    private fun goHome() {
        hideLock()
        runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun hideLock() { lockView?.let { runCatching { wm.removeView(it) } }; lockView = null; lockKind = "" }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        hideBubble(); hideLock()
        super.onDestroy()
    }
}
