package io.github.funnyhell.daymark.platform.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.platform.goAsyncWork
import io.github.funnyhell.daymark.platform.widgets.WidgetUpdater

/** Fired by the single reminder alarm. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        goAsyncWork { context.container.reminders.onAlarm() }
    }

    companion object {
        const val ACTION_ALARM = "io.github.funnyhell.daymark.action.REMINDER_ALARM"
    }
}

/**
 * Rebuilds the alarm after events that clear or invalidate it: reboot, app update, clock or time-zone change,
 * and a change of the exact-alarm permission.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        goAsyncWork {
            context.container.reminders.runCheck()
            WidgetUpdater.updateAll(context)
        }
    }
}
