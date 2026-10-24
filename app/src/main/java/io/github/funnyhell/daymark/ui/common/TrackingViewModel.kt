package io.github.funnyhell.daymark.ui.common

import android.app.Application
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.HabitActionResult
import io.github.funnyhell.daymark.data.repo.HabitRejectReason
import io.github.funnyhell.daymark.data.repo.MedicationActionResult
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.RejectReason
import java.time.LocalDate

/**
 * Shared habit and dose actions used by several screens. All of them go through the repositories, which validate
 * against the database; the UI only reports the outcome.
 */
abstract class TrackingViewModel(application: Application) : AppViewModel(application) {

    fun takeDose(medicationId: String, occurrenceKey: String, name: String) =
        recordDose(medicationId, occurrenceKey, name, MedicationEventType.TAKEN)

    fun skipDose(medicationId: String, occurrenceKey: String, name: String) =
        recordDose(medicationId, occurrenceKey, name, MedicationEventType.SKIPPED)

    private fun recordDose(medicationId: String, key: String, name: String, type: MedicationEventType) = launchSafely {
        when (val r = container.medications.recordDose(medicationId, key, type, ActionSource.APP)) {
            is MedicationActionResult.Recorded -> {
                val base = string(if (type == MedicationEventType.TAKEN) R.string.msg_taken else R.string.msg_skipped, name)
                val text = if (r.insufficientStock) base + " " + string(R.string.msg_insufficient_stock) else base
                say(text, string(R.string.undo)) { undoMedicationEvent(r.eventId, silent = true) }
            }
            is MedicationActionResult.AlreadyResolved -> say(string(R.string.msg_already_recorded))
            is MedicationActionResult.Rejected -> say(string(if (r.reason == RejectReason.FUTURE_DATE) R.string.msg_future_dose else R.string.msg_stale_dose))
            MedicationActionResult.AlreadySnoozed -> Unit
            MedicationActionResult.NotFound -> say(string(R.string.msg_not_found))
        }
    }

    fun snoozeDose(medicationId: String, occurrenceKey: String) = launchSafely {
        when (val r = container.medications.snooze(medicationId, occurrenceKey, ActionSource.APP)) {
            is MedicationActionResult.Recorded, MedicationActionResult.AlreadySnoozed -> say(string(R.string.msg_snoozed))
            is MedicationActionResult.AlreadyResolved -> say(string(R.string.msg_already_recorded))
            is MedicationActionResult.Rejected -> say(string(R.string.msg_stale_dose))
            MedicationActionResult.NotFound -> say(string(R.string.msg_not_found))
        }
    }

    fun undoMedicationEvent(eventId: String, silent: Boolean = false) = launchSafely {
        val changed = container.medications.voidEvent(eventId)
        if (!silent || !changed) say(string(if (changed) R.string.msg_corrected else R.string.msg_already_corrected))
    }

    fun completeHabit(habitId: String, date: LocalDate? = null) = launchSafely {
        report(container.habits.addCompletion(habitId, date ?: container.time.today()))
    }

    fun addHabitProgress(habitId: String, amount: Double, date: LocalDate? = null, note: String? = null) = launchSafely {
        report(container.habits.addProgress(habitId, amount, date ?: container.time.today(), note))
    }

    fun skipHabit(habitId: String, date: LocalDate? = null) = launchSafely {
        report(container.habits.skip(habitId, date ?: container.time.today()))
    }

    fun recordLapse(habitId: String, date: LocalDate? = null, note: String? = null) = launchSafely {
        report(container.habits.recordLapse(habitId, date ?: container.time.today(), note))
    }

    fun undoHabitEvent(eventId: String, silent: Boolean = false) = launchSafely {
        val changed = container.habits.voidEvent(eventId)
        if (!silent || !changed) say(string(if (changed) R.string.msg_corrected else R.string.msg_already_corrected))
    }

    private fun report(result: HabitActionResult) {
        when (result) {
            is HabitActionResult.Recorded -> say(string(R.string.msg_recorded), string(R.string.undo)) { undoHabitEvent(result.eventId, silent = true) }
            HabitActionResult.AlreadyDone -> say(string(R.string.msg_already_done))
            is HabitActionResult.Rejected -> say(
                string(
                    when (result.reason) {
                        HabitRejectReason.FUTURE_DATE -> R.string.msg_future_day
                        HabitRejectReason.BEFORE_START -> R.string.msg_before_start
                        HabitRejectReason.PAUSED_DAY -> R.string.msg_paused_day
                        HabitRejectReason.ARCHIVED -> R.string.msg_archived
                        HabitRejectReason.INVALID_AMOUNT -> R.string.error_number
                        HabitRejectReason.NOT_FOUND, HabitRejectReason.WRONG_TYPE -> R.string.msg_not_found
                    },
                ),
            )
        }
    }
}
