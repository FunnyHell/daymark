package io.github.funnyhell.daymark.ui.habits

import android.content.Context
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.common.startOfWeek
import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitEvent
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitSchedule
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.LocalDate

/** Today's view of one habit, derived from persisted data with the domain rules. */
data class HabitTodayItem(
    val habitId: String,
    val name: String,
    val colorArgb: Long,
    val type: HabitType,
    val unit: String?,
    val fraction: Float,
    val ringLabel: String,
    val statusText: String,
    val done: Boolean,
    val streakText: String?,
    /** Latest active event of today that "undo" would correct. */
    val undoEventId: String?,
    val skipped: Boolean,
    /** State of the tap-to-toggle ring: tapping sets the opposite. Null when the ring is not a toggle. */
    val checked: Boolean? = null,
)

object HabitPresentation {
    fun todayItem(context: Context, data: HabitData, today: LocalDate, firstDayOfWeek: DayOfWeek): HabitTodayItem? {
        val habit = data.habit
        if (habit.status != LifecycleStatus.ACTIVE) return null
        val e = HabitEvaluator(data, firstDayOfWeek)
        if (!e.isScheduled(today)) return null
        val todays = e.eventsOn(today).sortedBy { it.recordedAt }
        val streak = e.streaks(today)
        return when (habit.type) {
            HabitType.WEEKLY_GOAL -> {
                val week = e.weekResult(today.startOfWeek(firstDayOfWeek), today)
                val doneThisWeek = week.completions >= week.goal
                HabitTodayItem(
                    habit.id, habit.name, habit.colorArgb, habit.type, habit.unit,
                    fraction = if (week.goal > 0) (week.completions / week.goal).toFloat() else 0f,
                    ringLabel = "${week.completions.formatQuantity()}/${week.goal.formatQuantity()}",
                    statusText = context.getString(R.string.progress_week, week.completions.formatQuantity(), week.goal.formatQuantity()),
                    done = doneThisWeek,
                    streakText = streak.current.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.streak_weeks, it, it) },
                    undoEventId = todays.lastOrNull { it.type == HabitEventType.COMPLETION }?.id,
                    skipped = false,
                    checked = e.progressOn(today) > 0.0,
                )
            }
            HabitType.ABSTINENCE -> {
                val lapsedToday = e.dayResult(today, today).state == DayState.LAPSED
                HabitTodayItem(
                    habit.id, habit.name, habit.colorArgb, habit.type, habit.unit,
                    fraction = if (lapsedToday) 0f else 1f,
                    ringLabel = streak.current.toString(),
                    statusText = if (lapsedToday) context.getString(R.string.abstinence_lapsed_today)
                    else context.resources.getQuantityString(R.plurals.days_clean, streak.current, streak.current),
                    done = !lapsedToday,
                    streakText = context.getString(R.string.best_streak_days, streak.best),
                    undoEventId = todays.lastOrNull { it.type == HabitEventType.LAPSE }?.id,
                    skipped = false,
                )
            }
            else -> {
                val day = e.dayResult(today, today)
                val done = day.state == DayState.DONE
                val status = when (habit.type) {
                    HabitType.QUANTITY -> context.getString(R.string.progress_amount, day.progress.formatQuantity(), day.target.formatQuantity(), habit.unit.orEmpty())
                    HabitType.MULTI -> context.getString(R.string.progress_count, day.progress.formatQuantity(), day.target.formatQuantity())
                    else -> context.getString(if (done) R.string.state_done else if (day.state == DayState.SKIPPED) R.string.state_skipped else R.string.state_not_done)
                }
                HabitTodayItem(
                    habit.id, habit.name, habit.colorArgb, habit.type, habit.unit,
                    fraction = day.fraction.toFloat(),
                    ringLabel = when (habit.type) {
                        HabitType.MULTI -> "${day.progress.formatQuantity()}/${day.target.formatQuantity()}"
                        HabitType.QUANTITY -> "${(day.fraction * 100).toInt()}%"
                        else -> ""
                    },
                    statusText = if (day.state == DayState.SKIPPED && habit.type != HabitType.CHECK) "$status · ${context.getString(R.string.state_skipped)}" else status,
                    done = done,
                    streakText = streak.current.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.streak_days, it, it) },
                    undoEventId = todays.lastOrNull { it.type == HabitEventType.COMPLETION || it.type == HabitEventType.PROGRESS || it.type == HabitEventType.SKIP }?.id,
                    skipped = day.state == DayState.SKIPPED,
                    checked = done.takeIf { habit.type != HabitType.QUANTITY && day.state != DayState.SKIPPED },
                )
            }
        }
    }

    fun typeLabel(context: Context, type: HabitType): String = context.getString(
        when (type) {
            HabitType.CHECK -> R.string.type_check
            HabitType.MULTI -> R.string.type_multi
            HabitType.QUANTITY -> R.string.type_quantity
            HabitType.WEEKLY_GOAL -> R.string.type_weekly
            HabitType.ABSTINENCE -> R.string.type_abstinence
        },
    )

    /** Plain-language description of the current schedule, e.g. "Mon, Wed, Fri · 3 times a day". */
    fun scheduleText(context: Context, data: HabitData, today: LocalDate): String {
        val habit = data.habit
        val schedule: HabitSchedule = HabitEvaluator(data).scheduleOn(maxOf(today, habit.startDate)) ?: data.schedules.lastOrNull()
            ?: return ""
        val target = schedule.target.formatQuantity()
        return when (habit.type) {
            HabitType.WEEKLY_GOAL -> context.getString(R.string.schedule_weekly, target)
            HabitType.ABSTINENCE -> context.getString(R.string.schedule_abstinence, Formats.dateMedium(context, habit.startDate))
            else -> {
                val rec = recurrenceText(context, schedule.recurrence)
                when (habit.type) {
                    HabitType.MULTI -> context.getString(R.string.schedule_with_target, rec, context.getString(R.string.times_per_day, target))
                    HabitType.QUANTITY -> context.getString(R.string.schedule_with_target, rec, "$target ${habit.unit.orEmpty()}")
                    else -> rec
                }
            }
        }
    }

    fun recurrenceText(context: Context, recurrence: Recurrence): String = when (recurrence) {
        Recurrence.Daily -> context.getString(R.string.recurrence_daily)
        is Recurrence.Weekdays -> recurrence.days.sorted().joinToString(", ") { Formats.dayShort(context, it) }
        is Recurrence.EveryNDays -> context.getString(R.string.recurrence_every_n, recurrence.interval)
    }

    fun eventText(context: Context, data: HabitData, event: HabitEvent): String = when (event.type) {
        HabitEventType.COMPLETION -> context.getString(R.string.event_completion)
        HabitEventType.PROGRESS -> context.getString(R.string.event_progress, event.amount.formatQuantity(), data.habit.unit.orEmpty())
        HabitEventType.SKIP -> context.getString(R.string.event_skip)
        HabitEventType.LAPSE -> context.getString(R.string.event_lapse)
    }

    fun dayStateText(context: Context, state: DayState): String = context.getString(
        when (state) {
            DayState.FUTURE -> R.string.day_future
            DayState.BEFORE_START -> R.string.day_before_start
            DayState.PAUSED -> R.string.day_paused
            DayState.NOT_SCHEDULED -> R.string.day_not_scheduled
            DayState.DONE -> R.string.day_done
            DayState.PARTIAL -> R.string.day_partial
            DayState.SKIPPED -> R.string.day_skipped
            DayState.PENDING -> R.string.day_pending
            DayState.MISSED -> R.string.day_missed
            DayState.LAPSED -> R.string.day_lapsed
        },
    )
}
