package io.github.funnyhell.daymark.platform.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.data.repo.DoseRef
import io.github.funnyhell.daymark.data.repo.HabitActionResult
import io.github.funnyhell.daymark.data.repo.MedicationActionResult
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.platform.goAsyncWork
import java.time.LocalDate

/**
 * Handles notification action buttons. Every action is validated by the repository against persisted state, so
 * repeated taps, duplicate deliveries and stale notifications (edited or paused schedules) cannot record twice.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        goAsyncWork { handle(context, action, intent) }
    }

    private suspend fun handle(context: Context, action: String, intent: Intent) {
        val c = context.container
        when (action) {
            ACTION_TAKEN, ACTION_SKIP, ACTION_SNOOZE -> {
                val medId = intent.getStringExtra(EXTRA_MEDICATION_ID) ?: return
                val key = intent.getStringExtra(EXTRA_OCCURRENCE_KEY) ?: return
                val result = when (action) {
                    ACTION_TAKEN -> c.medications.recordDose(medId, key, MedicationEventType.TAKEN, ActionSource.NOTIFICATION)
                    ACTION_SKIP -> c.medications.recordDose(medId, key, MedicationEventType.SKIPPED, ActionSource.NOTIFICATION)
                    else -> c.medications.snooze(medId, key, ActionSource.NOTIFICATION)
                }
                Log.i(TAG, "Medication action $action for $key: $result")
                // Successful and duplicate actions clear the notification through the repository's change event;
                // a stale or unknown occurrence is cleared here.
                if (result is MedicationActionResult.Rejected || result is MedicationActionResult.NotFound) {
                    c.notifier.cancelMedication(key)
                }
            }
            ACTION_TAKE_ALL, ACTION_SKIP_ALL, ACTION_SNOOZE_ALL -> {
                val doses = intent.getStringArrayExtra(EXTRA_ITEMS).orEmpty().map { DoseRef(it.substringBefore('|'), it.substringAfter('|')) }
                // The alarm for the snoozed doses is set before the notification goes away.
                val results = when (action) {
                    ACTION_TAKE_ALL -> c.medications.recordDoses(doses, MedicationEventType.TAKEN, ActionSource.NOTIFICATION)
                    ACTION_SKIP_ALL -> c.medications.recordDoses(doses, MedicationEventType.SKIPPED, ActionSource.NOTIFICATION)
                    else -> c.medications.snoozeAll(doses, ActionSource.NOTIFICATION)
                }
                Log.i(TAG, "Group action $action: $results")
                intent.getStringExtra(EXTRA_TAG)?.let(c.notifier::cancelTag)
            }
            ACTION_HABITS_DONE -> {
                val items = intent.getStringArrayExtra(EXTRA_ITEMS)?.toList().orEmpty()
                for (item in items) {
                    val date = runCatching { LocalDate.parse(item.substringAfter('|')) }.getOrNull() ?: continue
                    c.habits.completeIfIncomplete(item.substringBefore('|'), date)
                }
                intent.getStringExtra(EXTRA_TAG)?.let(c.notifier::cancelTag)
            }
            ACTION_HABIT_DONE -> {
                val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
                val date = intent.getStringExtra(EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
                val result = c.habits.completeIfIncomplete(habitId, date)
                Log.i(TAG, "Habit action for $habitId on $date: $result")
                if (result !is HabitActionResult.Recorded) c.notifier.cancelHabit(habitId, date)
            }
        }
    }

    companion object {
        private const val TAG = "DaymarkActions"
        const val ACTION_TAKEN = "io.github.funnyhell.daymark.action.MED_TAKEN"
        const val ACTION_SKIP = "io.github.funnyhell.daymark.action.MED_SKIP"
        const val ACTION_SNOOZE = "io.github.funnyhell.daymark.action.MED_SNOOZE"
        const val ACTION_HABIT_DONE = "io.github.funnyhell.daymark.action.HABIT_DONE"
        const val ACTION_TAKE_ALL = "io.github.funnyhell.daymark.action.MED_TAKE_ALL"
        const val ACTION_SNOOZE_ALL = "io.github.funnyhell.daymark.action.MED_SNOOZE_ALL"
        const val ACTION_SKIP_ALL = "io.github.funnyhell.daymark.action.MED_SKIP_ALL"
        const val ACTION_HABITS_DONE = "io.github.funnyhell.daymark.action.HABITS_DONE"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_TAG = "tag"
        const val EXTRA_MEDICATION_ID = "medication_id"
        const val EXTRA_OCCURRENCE_KEY = "occurrence_key"
        const val EXTRA_HABIT_ID = "habit_id"
        const val EXTRA_DATE = "date"
    }
}
