package io.github.funnyhell.daymark.domain.habit

import io.github.funnyhell.daymark.domain.common.isPausedOn
import io.github.funnyhell.daymark.domain.common.roundQuantity
import io.github.funnyhell.daymark.domain.common.startOfWeek
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * State of a single calendar day for a habit. See docs/DECISIONS.md (ADR-013) for the rules.
 *
 * For streaks and completion rates: [DONE] is a success, [MISSED] and [PARTIAL] are failures,
 * [LAPSED] is an abstinence failure, everything else is neutral.
 */
enum class DayState {
    FUTURE,
    BEFORE_START,
    PAUSED,
    NOT_SCHEDULED,
    DONE,
    PARTIAL,
    SKIPPED,
    PENDING,
    MISSED,
    LAPSED,
}

data class DayResult(
    val date: LocalDate,
    val state: DayState,
    val progress: Double,
    val target: Double,
) {
    val fraction: Double get() = if (target <= 0.0) 0.0 else (progress / target).coerceIn(0.0, 1.0)
}

enum class PeriodOutcome { SUCCESS, FAILURE, NEUTRAL, IN_PROGRESS }

/** Result of a weekly-goal week. */
data class WeekResult(
    val weekStart: LocalDate,
    val completions: Double,
    val goal: Double,
    val outcome: PeriodOutcome,
)

data class StreakSummary(val current: Int, val best: Int)

/** A run of consecutive clean days for an abstinence habit. */
data class CleanRun(val start: LocalDate, val endInclusive: LocalDate, val days: Int, val endedByLapse: Boolean)

/**
 * Pure evaluation of a habit's recorded events against its schedule versions and pause intervals.
 * Nothing here is persisted; every value is derived again from records when needed.
 */
class HabitEvaluator(
    private val data: HabitData,
    private val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
) {
    private val habit get() = data.habit
    private val activeEvents = data.events.filter { it.isActive }
    private val eventsByDate = activeEvents.groupBy { it.date }

    fun scheduleOn(date: LocalDate): HabitSchedule? = data.schedules.firstOrNull { it.isEffectiveOn(date) }

    fun isPaused(date: LocalDate): Boolean = data.pauses.isPausedOn(date)

    /** True if the recurrence (ignoring pauses) asks for activity on [date]. */
    fun recurrenceMatches(date: LocalDate): Boolean {
        if (date.isBefore(habit.startDate)) return false
        val schedule = scheduleOn(date) ?: return false
        return when (val r = schedule.recurrence) {
            Recurrence.Daily -> true
            is Recurrence.Weekdays -> date.dayOfWeek in r.days
            is Recurrence.EveryNDays -> {
                val anchor = maxOf(schedule.effectiveFrom, habit.startDate)
                ChronoUnit.DAYS.between(anchor, date) % r.interval == 0L
            }
        }
    }

    /** True if the day is an active scheduled day (scheduled and not paused). */
    fun isScheduled(date: LocalDate): Boolean = when (habit.type) {
        HabitType.WEEKLY_GOAL, HabitType.ABSTINENCE ->
            !date.isBefore(habit.startDate) && scheduleOn(date) != null && !isPaused(date)
        else -> recurrenceMatches(date) && !isPaused(date)
    }

    fun eventsOn(date: LocalDate): List<HabitEvent> = eventsByDate[date].orEmpty()

    /** Progress on a day: number of completions, or the summed amount for QUANTITY habits. */
    fun progressOn(date: LocalDate): Double = eventsOn(date)
        .filter { it.type == HabitEventType.COMPLETION || it.type == HabitEventType.PROGRESS }
        .sumOf { it.amount }
        .roundQuantity()

    fun targetOn(date: LocalDate): Double = when (habit.type) {
        HabitType.CHECK -> 1.0
        HabitType.ABSTINENCE -> 0.0
        HabitType.WEEKLY_GOAL -> 1.0
        else -> scheduleOn(date)?.target ?: scheduleOn(habit.startDate)?.target ?: 1.0
    }

    fun dayResult(date: LocalDate, today: LocalDate): DayResult {
        val progress = progressOn(date)
        val target = targetOn(date)
        val state = when {
            date.isAfter(today) -> DayState.FUTURE
            date.isBefore(habit.startDate) -> DayState.BEFORE_START
            isPaused(date) -> DayState.PAUSED
            habit.type == HabitType.ABSTINENCE -> abstinenceState(date)
            habit.type == HabitType.WEEKLY_GOAL ->
                if (scheduleOn(date) == null) DayState.NOT_SCHEDULED
                else if (progress > 0.0) DayState.DONE else DayState.NOT_SCHEDULED
            !recurrenceMatches(date) -> DayState.NOT_SCHEDULED
            progress >= target -> DayState.DONE
            eventsOn(date).any { it.type == HabitEventType.SKIP } -> DayState.SKIPPED
            date == today -> DayState.PENDING
            progress > 0.0 -> DayState.PARTIAL
            else -> DayState.MISSED
        }
        return DayResult(date, state, progress, target)
    }

    private fun abstinenceState(date: LocalDate): DayState = when {
        scheduleOn(date) == null -> DayState.NOT_SCHEDULED
        eventsOn(date).any { it.type == HabitEventType.LAPSE } -> DayState.LAPSED
        else -> DayState.DONE
    }

    fun days(from: LocalDate, to: LocalDate, today: LocalDate): List<DayResult> {
        if (to.isBefore(from)) return emptyList()
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map { dayResult(it, today) }.toList()
    }

    // ---- Weekly goals ----

    fun weekResult(weekStart: LocalDate, today: LocalDate): WeekResult {
        val weekEnd = weekStart.plusDays(6)
        val lastEvaluated = minOf(weekEnd, today)
        val schedule = scheduleOn(lastEvaluated) ?: scheduleOn(weekStart)
        val goal = schedule?.target ?: 0.0
        val completions = generateSequence(weekStart) { it.plusDays(1) }.take(7)
            .filter { !it.isAfter(today) }
            .sumOf { progressOn(it) }
            .roundQuantity()
        val days = generateSequence(weekStart) { it.plusDays(1) }.take(7).toList()
        val trackedDays = days.filter { !it.isBefore(habit.startDate) && scheduleOn(it) != null }
        val hasPausedOrMissingDays = days.any { it.isBefore(habit.startDate) || scheduleOn(it) == null || isPaused(it) }
        val outcome = when {
            weekStart.isAfter(today) -> PeriodOutcome.NEUTRAL
            trackedDays.isEmpty() || trackedDays.all { isPaused(it) } -> PeriodOutcome.NEUTRAL
            goal > 0.0 && completions >= goal -> PeriodOutcome.SUCCESS
            !weekEnd.isBefore(today) -> PeriodOutcome.IN_PROGRESS
            hasPausedOrMissingDays -> PeriodOutcome.NEUTRAL
            else -> PeriodOutcome.FAILURE
        }
        return WeekResult(weekStart, completions, goal, outcome)
    }

    fun weeks(from: LocalDate, today: LocalDate): List<WeekResult> {
        val first = maxOf(from, habit.startDate).startOfWeek(firstDayOfWeek)
        val last = today.startOfWeek(firstDayOfWeek)
        return generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(last) }
            .map { weekResult(it, today) }.toList()
    }

    // ---- Streaks and rates ----

    /** Outcomes per evaluation unit (days, or weeks for WEEKLY_GOAL) from the start date to today. */
    fun outcomes(today: LocalDate): List<PeriodOutcome> = when (habit.type) {
        HabitType.WEEKLY_GOAL -> weeks(habit.startDate, today).map { it.outcome }
        else -> days(habit.startDate, today, today).map { dayOutcome(it) }
    }

    fun dayOutcome(result: DayResult): PeriodOutcome = when (result.state) {
        DayState.DONE -> PeriodOutcome.SUCCESS
        DayState.MISSED, DayState.PARTIAL, DayState.LAPSED -> PeriodOutcome.FAILURE
        DayState.PENDING -> PeriodOutcome.IN_PROGRESS
        else -> PeriodOutcome.NEUTRAL
    }

    fun streaks(today: LocalDate): StreakSummary = StreakCalculator.summarize(outcomes(today))

    /**
     * Completion rate for units that ended inside [from]..[to] (inclusive):
     * successes / (successes + failures). Neutral and in-progress units are excluded.
     * Returns null when nothing was evaluated.
     */
    fun completionRate(from: LocalDate, to: LocalDate, today: LocalDate): RateSummary {
        val outcomes = when (habit.type) {
            HabitType.WEEKLY_GOAL -> weeks(from, today)
                .filter { !it.weekStart.plusDays(6).isBefore(from) && !it.weekStart.isAfter(to) }
                .map { it.outcome }
            else -> days(maxOf(from, habit.startDate), minOf(to, today), today).map { dayOutcome(it) }
        }
        return RateSummary(
            successes = outcomes.count { it == PeriodOutcome.SUCCESS },
            failures = outcomes.count { it == PeriodOutcome.FAILURE },
        )
    }

    /** Clean runs between lapses for abstinence habits, oldest first. Paused days are not counted. */
    fun cleanRuns(today: LocalDate): List<CleanRun> {
        if (habit.type != HabitType.ABSTINENCE) return emptyList()
        val runs = mutableListOf<CleanRun>()
        var runStart: LocalDate? = null
        var runEnd: LocalDate? = null
        var count = 0
        for (day in days(habit.startDate, today, today)) {
            when (day.state) {
                DayState.DONE -> {
                    if (runStart == null) runStart = day.date
                    runEnd = day.date
                    count++
                }
                DayState.LAPSED -> {
                    if (runStart != null && runEnd != null) runs += CleanRun(runStart, runEnd, count, endedByLapse = true)
                    runStart = null
                    runEnd = null
                    count = 0
                }
                else -> Unit
            }
        }
        if (runStart != null && runEnd != null) runs += CleanRun(runStart, runEnd, count, endedByLapse = false)
        return runs
    }

    fun lapses(): List<HabitEvent> = activeEvents.filter { it.type == HabitEventType.LAPSE }.sortedBy { it.recordedAt }

    /** Total recorded completions (count) or amount (QUANTITY), excluding corrected events. */
    fun totalProgress(): Double = activeEvents
        .filter { it.type == HabitEventType.COMPLETION || it.type == HabitEventType.PROGRESS }
        .sumOf { it.amount }
        .roundQuantity()
}

data class RateSummary(val successes: Int, val failures: Int) {
    val evaluated: Int get() = successes + failures
    val rate: Double? get() = if (evaluated == 0) null else successes.toDouble() / evaluated
    operator fun plus(other: RateSummary) = RateSummary(successes + other.successes, failures + other.failures)
}

object StreakCalculator {
    /**
     * Current streak: successes counted backwards from the latest unit, skipping neutral and in-progress
     * units, until the first failure. Best streak: the longest such run anywhere in history.
     */
    fun summarize(outcomes: List<PeriodOutcome>): StreakSummary {
        var best = 0
        var run = 0
        for (o in outcomes) {
            when (o) {
                PeriodOutcome.SUCCESS -> {
                    run++
                    if (run > best) best = run
                }
                PeriodOutcome.FAILURE -> run = 0
                PeriodOutcome.NEUTRAL, PeriodOutcome.IN_PROGRESS -> Unit
            }
        }
        return StreakSummary(current = run, best = best)
    }
}
