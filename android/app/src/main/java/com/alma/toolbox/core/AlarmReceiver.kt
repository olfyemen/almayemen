package com.alma.toolbox.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alma.toolbox.periodic.PeriodicScheduler
import com.alma.toolbox.pomodoro.PomoEngine
import com.alma.toolbox.reminder.ReminderScheduler
import com.alma.toolbox.silent.SilentEngine

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Stores.init(ctx)
        val uri = intent.data ?: return
        val id = uri.lastPathSegment?.toLongOrNull() ?: 0L
        when (uri.host) {
            "periodic" -> PeriodicScheduler.fire(ctx, id)
            "silent" -> SilentEngine.apply(ctx)
            "reminder" -> ReminderScheduler.fire(ctx, id, pre = false, snooze = false)
            "reminderpre" -> ReminderScheduler.fire(ctx, id, pre = true, snooze = false)
            "snooze" -> ReminderScheduler.fire(ctx, id, pre = false, snooze = true)
            "pomodoro" -> PomoEngine.phaseEnded(ctx)
            "adhan" -> com.alma.toolbox.prayer.AdhanScheduler.fire(ctx, pre = false)
            "occasions" -> com.alma.toolbox.daily.Occasions.check(ctx)
            "backup" -> Thread { com.alma.toolbox.core.Backup.runAuto(ctx) }.start()
            "ledgerremind" -> com.alma.toolbox.ledger.LedgerRemind.fire(ctx)
            "iqama" -> com.alma.toolbox.prayer.AdhanScheduler.iqama(ctx)
            "adhanpre" -> com.alma.toolbox.prayer.AdhanScheduler.fire(ctx, pre = true)
            // notification actions
            "action" -> {
                when (uri.pathSegments.getOrNull(0)) {
                    "snooze" -> ReminderScheduler.snooze(ctx, id, uri.getQueryParameter("m")?.toIntOrNull() ?: 10)
                    "done" -> ReminderScheduler.done(ctx, id)
                    "disable" -> ReminderScheduler.disable(ctx, id)
                    "pomostop" -> PomoEngine.reset(ctx)
                    "pomopause" -> PomoEngine.toggle(ctx)
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Stores.init(ctx)
        Alarms.rescheduleAll(ctx)
        com.alma.toolbox.transfer.TransferHub.cleanTemp(ctx)
    }
}
