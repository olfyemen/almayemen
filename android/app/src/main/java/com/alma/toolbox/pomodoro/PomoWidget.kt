package com.alma.toolbox.pomodoro

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.widget.RemoteViews
import com.alma.toolbox.R
import com.alma.toolbox.Tool
import com.alma.toolbox.core.Notify
import com.alma.toolbox.core.Stores
import java.time.LocalDate

class PomoWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Stores.init(ctx)
        mgr.updateAppWidget(ids, views(ctx))
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PomoWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx))
        }

        private fun views(ctx: Context): RemoteViews {
            val d = Stores.pomodoro.value
            val rv = RemoteViews(ctx.packageName, R.layout.widget_pomo)
            rv.setChronometer(R.id.timer, SystemClock.elapsedRealtime() + d.left(), null, d.running)
            rv.setChronometerCountDown(R.id.timer, true)
            rv.setTextViewText(R.id.phase, "🍅 " + phaseName(d.phase) + if (d.task.isNotBlank()) " · ${d.task}" else "")
            rv.setTextColor(R.id.phase, when (d.phase) { "work" -> Color.rgb(229, 72, 77); "short" -> Color.rgb(22, 163, 74); else -> Color.rgb(0, 163, 196) })
            rv.setTextViewText(R.id.toggle, if (d.running) "إيقاف مؤقت" else "ابدأ")
            val today = d.log[LocalDate.now().toString()]?.count ?: 0
            rv.setTextViewText(R.id.today, "اليوم: $today 🍅")
            rv.setOnClickPendingIntent(R.id.toggle, Notify.action(ctx, "pomopause/0"))
            rv.setOnClickPendingIntent(R.id.reset, Notify.action(ctx, "pomostop/0"))
            rv.setOnClickPendingIntent(R.id.root, Notify.openApp(ctx, Tool.POMODORO.id, 7))
            return rv
        }
    }
}
