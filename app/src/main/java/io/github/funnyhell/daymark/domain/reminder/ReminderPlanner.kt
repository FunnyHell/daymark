package io.github.funnyhell.daymark.domain.reminder

import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.PeriodOutcome
import io.github.funnyhell.daymark.domain.common.startOfWeek
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class ReminderKind { MEDICATION, HABIT }

data class PlannedReminder(
    val kind: ReminderKind,
    val at: Instant,
    val date: LocalDate,
    /** Medication id or habit id. */
    val ownerId: String,
    /** Occurrence key for medication reminders. */
    val occurrenceKey: String?,
    /** A repeated reminder of a dose that is still not marked. */
    val repeat: Boolean = false,
) {
    /** Stable identity used for notification ids. */
    val stableId: String get() = occurrenceKey ?: "$ownerId@$date"
}

/**
 * Computes which reminders should exist, from persisted records only. Platform code turns the result into
 * a single alarm for the next reminder and posts notifications for reminders that came due.
 */
object ReminderPlanner {
    /** After a reboot or a late alarm, reminders that came due this long ago are still shown. */
    val CATCH_UP: Duration = Duration.ofHours(2)

    fun plan(
        medications: List<MedicationData>,
        habits: List<HabitData>,
        now: Instant,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek,
        medicationRemindersEnabled: Boolean = true,
        habitRemindersEnabled: Boolean = true,
        horizonDays: Long = 2,
        /**
         * When set, an open dose is reminded again at this interval after its reminder time (the dose time or
         * the end of a snooze), until it is recorded or its missed window ends.
         */
        medicationRepeat: Duration? = null,
    ): List<PlannedReminder> {
        val today = now.atZone(zone).toLocalDate()
        val dates = (0 until horizonDays).map { today.plusDays(it) }
        val result = mutableListOf<PlannedReminder>()
        if (medicationRemindersEnabled) {
            for (data in medications) {
                if (data.medication.status != LifecycleStatus.ACTIVE) continue
                // A dose of an earlier day can still be snoozed (late in the evening, or from a notification
                // left overnight); its reminder has to come back although the day itself is over.
                val snoozedEarlier = data.events
                    .filter { it.isActive && it.type == MedicationEventType.SNOOZED && it.snoozedUntil?.isAfter(now.minus(CATCH_UP)) == true }
                    .mapNotNull { it.occurrenceKey?.let(OccurrenceRules::parseKeyDate) }
                    .filter { it.isBefore(today) }
                for (date in (snoozedEarlier + dates).distinct()) {
                    for (status in OccurrenceRules.statusesOn(data, date, now, zone)) {
                        val at = when (status.state) {
                            OccurrenceState.TAKEN, OccurrenceState.SKIPPED, OccurrenceState.MISSED -> continue
                            else -> OccurrenceRules.reminderAt(status.occurrence, data.events, zone)
                        }
                        result += PlannedReminder(ReminderKind.MEDICATION, at, date, data.medication.id, status.occurrence.key)
                        if (medicationRepeat != null && !medicationRepeat.isZero && !medicationRepeat.isNegative) {
                            val until = at.plus(OccurrenceRules.MISSED_AFTER)
                            val earliest = now.minus(CATCH_UP)
                            var next = at.plus(medicationRepeat)
                            while (next.isBefore(until)) {
                                if (!next.isBefore(earliest)) {
                                    result += PlannedReminder(ReminderKind.MEDICATION, next, date, data.medication.id, status.occurrence.key, repeat = true)
                                }
                                next = next.plus(medicationRepeat)
                            }
                        }
                    }
                }
            }
        }
        if (habitRemindersEnabled) {
            for (data in habits) {
                val habit = data.habit
                val time = habit.reminderTime ?: continue
                if (habit.status != LifecycleStatus.ACTIVE || habit.type == HabitType.ABSTINENCE) continue
                val evaluator = HabitEvaluator(data, firstDayOfWeek)
                for (date in dates) {
                    if (!evaluator.isScheduled(date)) continue
                    val needed = if (habit.type == HabitType.WEEKLY_GOAL) {
                        evaluator.weekResult(date.startOfWeek(firstDayOfWeek), maxOf(date, today)).outcome != PeriodOutcome.SUCCESS
                    } else {
                        evaluator.progressOn(date) < evaluator.targetOn(date)
                    }
                    if (!needed) continue
                    val at = LocalDateTime.of(date, time).atZone(zone).toInstant()
                    result += PlannedReminder(ReminderKind.HABIT, at, date, habit.id, null)
                }
            }
        }
        return result.sortedBy { it.at }
    }

    /** Reminders that came due in (`after`, `until`], limited to the catch-up window. */
    fun dueBetween(plan: List<PlannedReminder>, after: Instant?, until: Instant): List<PlannedReminder> {
        val floor = until.minus(CATCH_UP)
        val start = if (after == null || after.isBefore(floor)) floor else after
        return plan.filter { it.at.isAfter(start) && !it.at.isAfter(until) }
    }

    fun nextAfter(plan: List<PlannedReminder>, after: Instant): Instant? =
        plan.filter { it.at.isAfter(after) }.minOfOrNull { it.at }
}
