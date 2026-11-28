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
                        if (data.habit.type.isWeekly && e.isScheduled(date)) WeekCell(date, WeekMark.OPEN, toggleTo = true)
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
                HabitType.CHECKLIST, HabitType.WEEKLY_CHECKLIST -> {
                    val steps = e.stepProgress(today)
                    val complete = steps.isNotEmpty() && steps.all { it.complete }
                    HabitWidgetRow(
                        habit.id, habit.name,
                        context.getString(R.string.progress_steps, steps.count { it.complete }, steps.size),
                        done = complete, checked = complete, colorArgb = habit.colorArgb,
                    )
                }
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

/** Example content for a widget's settings preview when there is nothing real to show yet. */
object WidgetSamples {
    fun habits(context: Context): List<HabitWidgetRow> = listOf(
        HabitWidgetRow("sample-1", context.getString(R.string.sample_habit_1), context.getString(R.string.state_done), done = true, checked = true, colorArgb = 0xFF2E7D32),
        HabitWidgetRow("sample-2", context.getString(R.string.sample_habit_2), context.getString(R.string.state_not_done), done = false, checked = false, colorArgb = 0xFF2F5FD0),
        HabitWidgetRow("sample-3", context.getString(R.string.sample_habit_3), context.getString(R.string.progress_count, "1", "3"), done = false, checked = false, colorArgb = 0xFFC25E00),
    )

    fun medications(context: Context): List<MedicationWidgetRow> {
        fun row(n: Int, name: Int, time: LocalTime, state: OccurrenceState, color: Long) = MedicationWidgetRow(
            medicationId = "sample-$n", occurrenceKey = "sample-$n", title = context.getString(name),
            subtitle = "${Formats.time(context, time)} · ${context.getString(R.string.dose_default)} · ${context.getString(WidgetContent.stateLabel(state))}",
            state = state, canTake = state != OccurrenceState.TAKEN, taken = state == OccurrenceState.TAKEN, colorArgb = color, time = time,
        )
        return listOf(
            row(1, R.string.sample_med_1, LocalTime.of(8, 0), OccurrenceState.TAKEN, 0xFF6A4BC4),
            row(2, R.string.sample_med_2, LocalTime.of(8, 0), OccurrenceState.DUE, 0xFF2E7D32),
            row(3, R.string.sample_med_1, LocalTime.of(20, 0), OccurrenceState.UPCOMING, 0xFF6A4BC4),
        )
    }

    fun week(context: Context, today: LocalDate): List<WeekGridRow> {
        fun row(n: Int, name: Int, color: Long, streak: Int, marks: List<WeekMark>) = WeekGridRow(
            "sample-$n", context.getString(name), color, streak,
            marks.mapIndexed { i, mark -> WeekCell(today.minusDays((6 - i).toLong()), mark, toggleTo = null) },
        )
        val d = WeekMark.DONE
        val o = WeekMark.OPEN
        return listOf(
            row(1, R.string.sample_habit_1, 0xFF2E7D32, 3, listOf(d, o, d, o, d, d, d)),
            row(2, R.string.sample_habit_2, 0xFF2F5FD0, 0, listOf(d, d, o, d, d, o, o)),
            row(3, R.string.sample_habit_3, 0xFFC25E00, 0, listOf(o, d, d, o, WeekMark.PARTIAL, d, o)),
        )
    }
}
