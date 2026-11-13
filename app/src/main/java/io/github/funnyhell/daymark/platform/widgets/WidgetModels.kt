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
    /**
     * State of the tap-to-toggle control (today marked or not); tapping requests the opposite through
     * [io.github.funnyhell.daymark.data.repo.HabitRepository.setDone]. Null when the widget cannot toggle the habit
     * (amounts, skipped days).
     */
    val checked: Boolean?,
    val colorArgb: Long,
)

data class MedicationWidgetRow(
    val medicationId: String,
    val occurrenceKey: String,
    val title: String,
    val subtitle: String,
    val state: OccurrenceState,
    val canTake: Boolean,
    val taken: Boolean,
    val colorArgb: Long,
    val time: LocalTime,
)

/** How one day of a habit is drawn in the week widget. */
enum class WeekMark { DONE, PARTIAL, OPEN, SLIP, OFF }

data class WeekCell(
    val date: LocalDate,
    val mark: WeekMark,
    /** The state a tap asks for through HabitRepository.setDone; null when the day cannot be changed. */
    val toggleTo: Boolean?,
)

data class WeekGridRow(val habitId: String, val name: String, val colorArgb: Long, val streak: Int, val cells: List<WeekCell>)

object WidgetContent {
    /** The last [days] days (today last) of every active habit shown on widgets. */
    fun weekGrid(habits: List<HabitData>, today: LocalDate, firstDayOfWeek: DayOfWeek, days: Int = 7): List<WeekGridRow> =
        habits.filter { it.habit.status == LifecycleStatus.ACTIVE && it.habit.showOnWidget }.map { data ->
            val e = HabitEvaluator(data, firstDayOfWeek)
            val cells = (days - 1 downTo 0).map { back ->
                val date = today.minusDays(back.toLong())
                val day = e.dayResult(date, today)
                when (day.state) {
                    DayState.DONE -> WeekCell(date, WeekMark.DONE, toggleTo = false)
                    DayState.LAPSED -> WeekCell(date, WeekMark.SLIP, toggleTo = true)
                    DayState.PARTIAL -> WeekCell(date, WeekMark.PARTIAL, toggleTo = true)
                    DayState.PENDING, DayState.MISSED ->
                        WeekCell(date, if (day.progress > 0.0) WeekMark.PARTIAL else WeekMark.OPEN, toggleTo = true)
                    DayState.SKIPPED -> WeekCell(date, WeekMark.OFF, toggleTo = true)
                    // A weekly goal can be done on any day of its schedule; other habits only on their own days.
                    DayState.NOT_SCHEDULED ->
                        if (data.habit.type == HabitType.WEEKLY_GOAL && e.isScheduled(date)) WeekCell(date, WeekMark.OPEN, toggleTo = true)
                        else WeekCell(date, WeekMark.OFF, toggleTo = null)
                    DayState.FUTURE, DayState.BEFORE_START, DayState.PAUSED -> WeekCell(date, WeekMark.OFF, toggleTo = null)
                }
            }
            WeekGridRow(data.habit.id, data.habit.name, data.habit.colorArgb, e.streaks(today).current, cells)
        }

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
                        done = week.completions >= week.goal, checked = doneToday, colorArgb = habit.colorArgb,
                    )
                }
                HabitType.ABSTINENCE -> {
                    val streak = e.streaks(today).current
                    HabitWidgetRow(
                        habit.id, habit.name, context.resources.getQuantityString(R.plurals.days_clean, streak, streak),
                        // A day without a slip counts as done; unchecking records a slip, checking again removes it.
                        done = e.dayResult(today, today).state == DayState.DONE,
                        checked = e.dayResult(today, today).state == DayState.DONE, colorArgb = habit.colorArgb,
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
                        checked = (day.state == DayState.DONE).takeIf { habit.type != HabitType.QUANTITY && day.state != DayState.SKIPPED },
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
                    taken = status.state == OccurrenceState.TAKEN,
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
