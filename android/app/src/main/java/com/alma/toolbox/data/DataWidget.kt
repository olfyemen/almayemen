package com.alma.toolbox.data

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.widget.RemoteViews
import com.alma.toolbox.R
import com.alma.toolbox.core.Notify

/** Home-screen widget: today's data, this month's mobile data vs the cap, and the WhatsApp switch. */
class DataWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread { try { mgr.updateAppWidget(ids, views(ctx)) } finally { pending.finish() } }.start()
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DataWidget::class.java))
            if (ids.isNotEmpty()) Thread { runCatching { mgr.updateAppWidget(ids, views(ctx)) } }.start()
        }

        private fun views(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_data)
            if (DataGuard.hasUsageAccess(ctx)) {
                val today = DataGuard.deviceBytes(ctx, ConnectivityManager.TYPE_MOBILE, DataGuard.todayStart()) +
                            DataGuard.deviceBytes(ctx, ConnectivityManager.TYPE_WIFI, DataGuard.todayStart())
                rv.setTextViewText(R.id.today, "اليوم ${DataGuard.fmt(today)}")
                val cap = DataGuard.capMb(ctx) * 1024L * 1024L
                val m = DataGuard.mobileMonth(ctx)
                rv.setTextViewText(R.id.month, "بيانات الهاتف هذا الشهر ${DataGuard.fmt(m)}" + if (cap > 0) " من ${DataGuard.fmt(cap)}" else "")
            } else rv.setTextViewText(R.id.today, "افتح الأداة لمنح الإذن")
            val off = DataGuard.waOff(ctx) && DataGuard.enabled(ctx)
            rv.setTextViewText(R.id.wa, if (off) "💬 تشغيل الواتساب" else "🔕 إيقاف الواتساب")
            rv.setOnClickPendingIntent(R.id.wa, Notify.action(ctx, "watoggle/0"))
            rv.setOnClickPendingIntent(R.id.root, Notify.openApp(ctx, "data", 75))
            return rv
        }
    }
}
