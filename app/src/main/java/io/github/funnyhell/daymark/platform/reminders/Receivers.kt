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
        when (intent.action) {
            ACTION_ALARM -> goAsyncWork { context.container.reminders.onAlarm() }
            ACTION_TEST -> context.container.notifier.showTest()
        }
    }

    companion object {
        const val ACTION_ALARM = "io.github.funnyhell.daymark.action.REMINDER_ALARM"
        const val ACTION_TEST = "io.github.funnyhell.daymark.action.TEST_REMINDER"
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
