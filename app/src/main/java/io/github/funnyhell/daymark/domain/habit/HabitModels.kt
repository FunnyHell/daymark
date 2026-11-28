package io.github.funnyhell.daymark.domain.habit

import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.PauseInterval
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class HabitType {
    /** Done once per scheduled day. */
    CHECK,

    /** Done a configurable number of times per scheduled day. */
    MULTI,

    /** Numeric amount with a unit, target per scheduled day. */
    QUANTITY,

    /** A number of completions per week, on any days. */
    WEEKLY_GOAL,

    /** Avoiding a behavior; lapses are recorded explicitly. */
    ABSTINENCE,

    /** Several steps on each scheduled day (a routine: brush teeth ×2, shower); done when every step is done. */
    CHECKLIST,

    /** Several steps over a week, on any days (cleaning: kitchen, bathroom, floors); done when every step is done. */
    WEEKLY_CHECKLIST,
    ;

    /** Evaluated per week (streaks count weeks) and possible on any day. */
    val isWeekly: Boolean get() = this == WEEKLY_GOAL || this == WEEKLY_CHECKLIST

    val isChecklist: Boolean get() = this == CHECKLIST || this == WEEKLY_CHECKLIST
}

/**
 * One step of a checklist habit, done [target] times per day (CHECKLIST) or per week (WEEKLY_CHECKLIST).
 * A step removed from the list keeps its history: it counts on the days from [addedOn] until [removedOn] (exclusive).
 */
data class HabitStep(
    val id: String,
    val habitId: String,
    val title: String,
    val target: Int,
    val sortOrder: Int,
    val addedOn: LocalDate,
    val removedOn: LocalDate?,
) {
    fun activeOn(date: LocalDate): Boolean = !date.isBefore(addedOn) && (removedOn == null || date.isBefore(removedOn))
}

/** Recurrence for day-based habit types (CHECK, MULTI, QUANTITY). */
sealed interface Recurrence {
    data object Daily : Recurrence

    data class Weekdays(val days: Set<DayOfWeek>) : Recurrence {
        init {
            require(days.isNotEmpty()) { "At least one weekday is required" }
        }
    }

    /** Every [interval] days, counted from the schedule version's effective start date. */
    data class EveryNDays(val interval: Int) : Recurrence {
        init {
            require(interval in 2..365) { "Interval must be between 2 and 365 days" }
        }
    }
}

data class Habit(
    val id: String,
    val name: String,
    val description: String?,
    val colorArgb: Long,
    val type: HabitType,
    val status: LifecycleStatus,
    /** Unit for QUANTITY habits, for example "ml" or "min". */
    val unit: String?,
    val startDate: LocalDate,
    val reminderTime: LocalTime?,
    val showOnWidget: Boolean,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A version of a habit's schedule, valid from [effectiveFrom] (inclusive) until [effectiveUntil] (exclusive).
 *
 * [target] meaning by type: CHECK = 1, MULTI = completions per day, QUANTITY = amount per day,
 * WEEKLY_GOAL = completions per week, ABSTINENCE = unused (0).
 */
data class HabitSchedule(
    val id: String,
    val habitId: String,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
    val recurrence: Recurrence,
    val target: Double,
) {
    fun isEffectiveOn(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveUntil == null || date.isBefore(effectiveUntil))
}

enum class HabitEventType {
    /** One completion (amount is normally 1). */
    COMPLETION,

    /** Quantitative progress (amount in the habit's unit). */
    PROGRESS,

    /** Explicit skip of a scheduled day. Neutral for streaks. */
    SKIP,

    /** Abstinence lapse. */
    LAPSE,

    /** A note about a day (what was done and how); does not count as progress. */
    NOTE,
}

/**
 * A recorded habit action. Events are never deleted by normal use; a correction sets [voidedAt],
 * which removes the event from calculations while keeping it visible in history.
 */
data class HabitEvent(
    val id: String,
    val habitId: String,
    val date: LocalDate,
    val type: HabitEventType,
    val amount: Double,
    val note: String?,
    val recordedAt: Instant,
    val voidedAt: Instant?,
    /** For checklist habits: the step this completion belongs to. */
    val stepId: String? = null,
) {
    val isActive: Boolean get() = voidedAt == null
}

/** Everything needed to evaluate a habit without touching the database. */
data class HabitData(
    val habit: Habit,
    val schedules: List<HabitSchedule>,
    val pauses: List<PauseInterval>,
    val events: List<HabitEvent>,
    val steps: List<HabitStep> = emptyList(),
)
