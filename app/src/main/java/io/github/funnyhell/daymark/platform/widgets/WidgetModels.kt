package io.github.funnyhell.daymark.platform.widgets

import android.content.Context
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.common.startOfWeek
import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Rows shown by the habit widget, computed from persisted data with the shared domain rules. */
data class HabitWidgetRow(
    val habitId: String,
    val name: String,
    val progressText: String,
    val done: Boolean,
    /** True if the widget may record a completion directly (see [io.github.funnyhell.daymark.data.repo.HabitRepository.completeIfIncomplete]). */
    val canComplete: Boolean,
    val colorArgb: Long,
)

data class MedicationWidgetRow(
    val medicationId: String,
    val occurrenceKey: String,
    val title: String,
    val subtitle: String,
    val state: OccurrenceState,
    val canTake: Boolean,
    val colorArgb: Long,
    val time: LocalTime,
)

object WidgetContent {
    fun habitRows(context: Context, habits: List<HabitData>, today: LocalDate, firstDayOfWeek: DayOfWeek): List<HabitWidgetRow> =
        habits.filter { it.habit.status == LifecycleStatus.ACTIVE && it.habit.showOnWidget }.mapNotNull { data ->
            val habit = data.habit
            val e = HabitEvaluator(data, firstDayOfWeek)
            if (!e.isScheduled(today)) return@mapNotNull null
            when (habit.type) {
                HabitType.WEEKLY_GOAL -> {
                    val week = e.weekResult(today.startOfWeek(firstDayOfWeek), today)
                    val doneToday = e.progressOn(today) > 0
                    HabitWidgetRow(
                        habit.id, habit.name,
                        context.getString(R.string.progress_week, week.completions.formatQuantity(), week.goal.formatQuantity()),
                        done = week.completions >= week.goal, canComplete = !doneToday, colorArgb = habit.colorArgb,
                    )
                }
                HabitType.ABSTINENCE -> {
                    val streak = e.streaks(today).current
                    HabitWidgetRow(
                        habit.id, habit.name, context.resources.getQuantityString(R.plurals.days_clean, streak, streak),
                        done = e.dayResult(today, today).state == DayState.DONE, canComplete = false, colorArgb = habit.colorArgb,
                    )
                }
                else -> {
                    val day = e.dayResult(today, today)
                    val text = when (habit.type) {
                        HabitType.QUANTITY -> context.getString(R.string.progress_amount, day.progress.formatQuantity(), day.target.formatQuantity(), habit.unit.orEmpty())
                        HabitType.MULTI -> context.getString(R.string.progress_count, day.progress.formatQuantity(), day.target.formatQuantity())
                        else -> context.getString(if (day.state == DayState.DONE) R.string.state_done else R.string.state_not_done)
                    }
                    HabitWidgetRow(
                        habit.id, habit.name, text, done = day.state == DayState.DONE,
                        canComplete = habit.type != HabitType.QUANTITY && day.state != DayState.DONE && day.state != DayState.SKIPPED,
                        colorArgb = habit.colorArgb,
                    )
                }
            }
        }

    fun medicationRows(context: Context, meds: List<MedicationData>, today: LocalDate, now: Instant, zone: ZoneId): List<MedicationWidgetRow> =
        meds.filter { it.medication.status == LifecycleStatus.ACTIVE }.flatMap { data ->
            OccurrenceRules.statusesOn(data, today, now, zone).map { status ->
                val occ = status.occurrence
                val stateText = context.getString(stateLabel(status.state))
                MedicationWidgetRow(
                    medicationId = data.medication.id,
                    occurrenceKey = occ.key,
                    title = data.medication.name,
                    subtitle = "${Formats.time(context, occ.time)} · ${occ.doseLabel.ifBlank { context.getString(R.string.dose_default) }} · $stateText",
                    state = status.state,
                    canTake = status.state != OccurrenceState.TAKEN && status.state != OccurrenceState.SKIPPED,
                    colorArgb = data.medication.colorArgb,
                    time = occ.time,
                )
            }
        }.sortedBy { it.time }

    fun stateLabel(state: OccurrenceState): Int = when (state) {
        OccurrenceState.UPCOMING -> R.string.occ_upcoming
        OccurrenceState.DUE -> R.string.occ_due
        OccurrenceState.SNOOZED -> R.string.occ_snoozed
        OccurrenceState.TAKEN -> R.string.occ_taken
        OccurrenceState.SKIPPED -> R.string.occ_skipped
        OccurrenceState.MISSED -> R.string.occ_missed
    }
}
