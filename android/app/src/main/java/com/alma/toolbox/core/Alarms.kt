package com.alma.toolbox.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.alma.toolbox.MainActivity

/** Every alarm is identified by a uri like alma://periodic/123 */
object Alarms {
    private fun pi(ctx: Context, uri: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? {
        val i = Intent(ctx, AlarmReceiver::class.java).setData(Uri.parse(uri))
        return PendingIntent.getBroadcast(ctx, 0, i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    fun set(ctx: Context, uri: String, at: Long, alarmClock: Boolean = false) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, uri)!!
        try {
            if (alarmClock && canExact(ctx)) {
                val show = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), p)
            } else if (canExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun cancel(ctx: Context, uri: String) {
        val p = pi(ctx, uri, PendingIntent.FLAG_NO_CREATE) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(p)
        p.cancel()
    }

    /** Re-schedules everything (boot, time change, app update). */
    fun rescheduleAll(ctx: Context) {
        com.alma.toolbox.periodic.PeriodicScheduler.scheduleAll(ctx)
        com.alma.toolbox.silent.SilentEngine.apply(ctx)
        com.alma.toolbox.reminder.ReminderScheduler.scheduleAll(ctx)
        com.alma.toolbox.pomodoro.PomoEngine.restore(ctx)
        com.alma.toolbox.prayer.AdhanScheduler.schedule(ctx)
        com.alma.toolbox.daily.Occasions.schedule(ctx)
        com.alma.toolbox.ledger.LedgerRemind.schedule(ctx)
        Backup.schedule(ctx)
        com.alma.toolbox.ledger.LedgerDue.schedule(ctx)
        com.alma.toolbox.data.DataGuard.scheduleCap(ctx)
        com.alma.toolbox.screentime.ScreenTime.sync(ctx)
    }
}
