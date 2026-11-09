package io.github.funnyhell.daymark.platform.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.funnyhell.daymark.MainActivity
import io.github.funnyhell.daymark.data.repo.HabitRepository
import io.github.funnyhell.daymark.data.repo.MedicationRepository
import io.github.funnyhell.daymark.data.repo.SettingsRepository
import io.github.funnyhell.daymark.domain.common.TimeSource
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.reminder.PlannedReminder
import io.github.funnyhell.daymark.domain.reminder.ReminderKind
import io.github.funnyhell.daymark.domain.reminder.ReminderPlanner
import io.github.funnyhell.daymark.platform.notifications.Notifier
import io.github.funnyhell.daymark.platform.widgets.WidgetUpdater
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single scheduling component. It keeps exactly one AlarmManager alarm: the earliest of the next reminder and
 * the next local midnight (to refresh widgets). Everything is recomputed from the database, so the alarm can be
 * rebuilt at any time — after a reboot, a time-zone change, an app update or a schedule edit.
 */
class ReminderEngine(
    private val context: Context,
    private val habits: HabitRepository,
    private val medications: MedicationRepository,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
    private val time: TimeSource,
) {
    private val mutex = Mutex()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private suspend fun plan(now: Instant): List<PlannedReminder> {
        val s = settings.current()
        return ReminderPlanner.plan(
            medications = medications.all(),
            habits = habits.all(),
            now = now,
            zone = time.zone(),
            firstDayOfWeek = s.firstDayOfWeek,
            medicationRemindersEnabled = s.medicationReminders,
            habitRemindersEnabled = s.habitReminders,
        )
    }

    /** Posts reminders that came due since the last check, then arms the next alarm. */
    suspend fun runCheck() = mutex.withLock {
        val now = time.now()
        val last = settings.lastReminderCheck()
        val plan = plan(now)
        if (last != null) postAll(ReminderPlanner.dueBetween(plan, last, now))
        settings.setLastReminderCheck(now)
        arm(plan, now, now)
    }

    /** Re-arms the alarm after data changes without posting anything. */
    suspend fun reschedule() = mutex.withLock {
        val now = time.now()
        val last = settings.lastReminderCheck() ?: now.also { settings.setLastReminderCheck(it) }
        arm(plan(now), now, last)
    }

    /** Reminders due in the same minute are shown as one notification per kind. */
    private suspend fun postAll(due: List<PlannedReminder>) {
        for ((_, sameMinute) in due.groupBy { it.at.epochSecond / 60 }) {
            val meds = sameMinute.filter { it.kind == ReminderKind.MEDICATION }.mapNotNull { r ->
                val data = medications.get(r.ownerId) ?: return@mapNotNull null
                val occurrence = r.occurrenceKey?.let { OccurrenceRules.findOccurrence(data, it) } ?: return@mapNotNull null
                data to occurrence
            }
            when {
                meds.size > 1 -> notifier.showMedicationGroup(meds, sameMinute.first().at)
                meds.size == 1 -> notifier.showMedication(meds[0].first, meds[0].second)
            }
            val habitItems = sameMinute.filter { it.kind == ReminderKind.HABIT }.mapNotNull { r ->
                habits.get(r.ownerId)?.let { it to r.date }
            }
            when {
                habitItems.size > 1 -> notifier.showHabitGroup(habitItems, sameMinute.first().at)
                habitItems.size == 1 -> notifier.showHabit(habitItems[0].first, habitItems[0].second)
            }
        }
    }

    private fun arm(plan: List<PlannedReminder>, now: Instant, lastCheck: Instant) {
        val nextReminder = ReminderPlanner.nextAfter(plan, lastCheck)
        val nextMidnight = time.today().plusDays(1).atStartOfDay(time.zone()).toInstant().plusSeconds(60)
        // A reminder that is already due but not yet processed (late alarm) fires almost immediately.
        val target = listOfNotNull(nextReminder, nextMidnight).min().let { if (it.isBefore(now)) now.plusSeconds(1) else it }
        val isReminder = nextReminder != null && !nextReminder.isAfter(target)
        schedule(alarmIntent(context), target, userVisible = isReminder)
        Log.i(TAG, "Next alarm at $target (in ${Duration.between(now, target).toMinutes()} min, reminder=$isReminder)")
    }

    /**
     * Reminders use an alarm-clock alarm: Android and Samsung's battery management do not defer it while the
     * screen is off or the phone is in Doze (other exact alarms can be held back). The status bar shows the alarm
     * icon while one is set. The midnight refresh uses an ordinary exact alarm.
     */
    private fun schedule(pi: PendingIntent, target: Instant, userVisible: Boolean) {
        val at = target.toEpochMilli()
        try {
            when {
                canScheduleExact() && userVisible ->
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showAppIntent(context)), pi)
                canScheduleExact() -> alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                // Without the exact-alarm permission Android may deliver the alarm several minutes late.
                else -> alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Posts a test reminder after [delay] through the same alarm path as real reminders. */
    fun scheduleTest(delay: Duration = Duration.ofMinutes(1)) {
        schedule(testIntent(context), time.now().plus(delay), userVisible = true)
    }

    /** Called by the alarm: show due reminders and refresh widgets (states and day rollover). */
    suspend fun onAlarm() {
        runCheck()
        WidgetUpdater.updateAll(context)
    }

    companion object {
        private const val TAG = "DaymarkReminders"

        fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderAlarmReceiver::class.java).setAction(ReminderAlarmReceiver.ACTION_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun testIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, ReminderAlarmReceiver::class.java).setAction(ReminderAlarmReceiver.ACTION_TEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun showAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
