package io.github.funnyhell.daymark.domain.stats

import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.startOfWeek
import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.PeriodOutcome
import io.github.funnyhell.daymark.domain.habit.RateSummary
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Habits done vs. habits that were due on one day (day-based types only). */
data class DayActivity(val done: Int, val due: Int)

object HabitStatistics {
    /**
     * Combined completion rate of all habits for a period, using each habit's own rules
     * (days for day-based habits, weeks for weekly goals, clean days for abstinence).
     */
    fun summary(habits: List<HabitData>, from: LocalDate, to: LocalDate, today: LocalDate, firstDayOfWeek: DayOfWeek): RateSummary =
        habits.fold(RateSummary(0, 0)) { acc, data ->
            acc + HabitEvaluator(data, firstDayOfWeek).completionRate(from, to, today)
        }

    /**
     * Per-day activity across day-based habits (CHECK, MULTI, QUANTITY) and weekly-goal completions.
     * A day-based habit counts as due on DONE, PARTIAL, MISSED and PENDING days; unscheduled, skipped and paused
     * days are not due. A weekly-goal completion counts as one done and due item on its day.
     * Abstinence habits are excluded because every clean day would count as activity.
     */
    fun dailyActivity(habits: List<HabitData>, from: LocalDate, to: LocalDate, today: LocalDate, firstDayOfWeek: DayOfWeek): Map<LocalDate, DayActivity> {
        val result = mutableMapOf<LocalDate, DayActivity>()
        for (data in habits) {
            if (data.habit.type == HabitType.ABSTINENCE) continue
            val e = HabitEvaluator(data, firstDayOfWeek)
            for (day in e.days(from, minOf(to, today), today)) {
                val (done, due) = when {
                    data.habit.type.isWeekly -> if (day.state == DayState.DONE) 1 to 1 else 0 to 0
                    day.state == DayState.DONE -> 1 to 1
                    day.state in setOf(DayState.PARTIAL, DayState.MISSED, DayState.PENDING) -> 0 to 1
                    else -> 0 to 0
                }
                if (due == 0) continue
                val prev = result[day.date] ?: DayActivity(0, 0)
                result[day.date] = DayActivity(prev.done + done, prev.due + due)
            }
        }
        return result
    }

    /**
     * Progress of each habit that is due on [date], in 0..1: one sector per habit in the "today" ring and in the
     * calendar widget. Day-based habits use their day progress; weekly goals use the week's progress up to [date];
     * abstinence habits count as complete unless a slip is recorded that day. Paused and unscheduled days are left
     * out; for today only active habits are shown.
     */
    fun dayFractions(habits: List<HabitData>, date: LocalDate, firstDayOfWeek: DayOfWeek, today: LocalDate = date): List<Double> =
        habits.filter { date != today || it.habit.status == LifecycleStatus.ACTIVE }.mapNotNull { data ->
            val e = HabitEvaluator(data, firstDayOfWeek)
            if (date.isAfter(today) || !e.isScheduled(date)) return@mapNotNull null
            when {
                data.habit.type == HabitType.ABSTINENCE -> if (e.dayResult(date, today).state == DayState.LAPSED) 0.0 else 1.0
                data.habit.type.isWeekly -> {
                    val week = e.weekResult(date.startOfWeek(firstDayOfWeek), date)
                    if (week.goal <= 0.0) null else (week.completions / week.goal).coerceIn(0.0, 1.0)
                }
                else -> {
                    val day = e.dayResult(date, today)
                    if (day.state == DayState.SKIPPED) null else day.fraction
                }
            }
        }

    /**
     * Average progress of the habits over [from]..[today] (inclusive), in 0..1, or null when nothing was due.
     * Each habit contributes one value so that a frequent habit does not outweigh a weekly one:
     * - day-based habits: mean day progress over their due days, today included (partial days count partly);
     * - weekly goals and weekly checklists: mean of min(1, done / goal) over the weeks touching the period, the
     *   current week included, so a goal not yet reached this week lowers the value (a weekly checklist's goal is
     *   all of its steps);
     * - checklists: like day-based habits, a day's progress is the share of its steps done;
     * - abstinence: share of clean days.
     * Paused, skipped and unscheduled days are not counted; archived habits are excluded.
     */
    fun periodProgress(habits: List<HabitData>, from: LocalDate, today: LocalDate, firstDayOfWeek: DayOfWeek): Double? {
        val perHabit = habits.filter { it.habit.status != LifecycleStatus.ARCHIVED }.mapNotNull { data ->
            val e = HabitEvaluator(data, firstDayOfWeek)
            val values: List<Double> = when {
                data.habit.type.isWeekly -> e.weeks(from, today)
                    .filter { it.outcome != PeriodOutcome.NEUTRAL && it.goal > 0.0 }
                    .map { (it.completions / it.goal).coerceIn(0.0, 1.0) }
                data.habit.type == HabitType.ABSTINENCE -> e.days(maxOf(from, data.habit.startDate), today, today)
                    .filter { it.state == DayState.DONE || it.state == DayState.LAPSED }
                    .map { if (it.state == DayState.DONE) 1.0 else 0.0 }
                else -> e.days(maxOf(from, data.habit.startDate), today, today)
                    .filter { it.state in setOf(DayState.DONE, DayState.PARTIAL, DayState.MISSED, DayState.PENDING) }
                    .map { it.fraction }
            }
            values.takeIf { it.isNotEmpty() }?.average()
        }
        return perHabit.takeIf { it.isNotEmpty() }?.average()
    }

    /** Daily summed amounts of a quantitative habit, oldest first. */
    fun quantitySeries(data: HabitData, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>> {
        val e = HabitEvaluator(data)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { it to e.progressOn(it) }.toList()
    }
}

data class Adherence(val taken: Int, val skipped: Int, val missed: Int, val open: Int) {
    val evaluated: Int get() = taken + skipped + missed

    /** Share of evaluated doses recorded as taken; open (upcoming, due, snoozed) doses are not evaluated yet. */
    val takenRate: Double? get() = if (evaluated == 0) null else taken.toDouble() / evaluated

    operator fun plus(o: Adherence) = Adherence(taken + o.taken, skipped + o.skipped, missed + o.missed, open + o.open)
}

object MedicationStatistics {
    /** Counts scheduled occurrences in [from]..[to] by state. Paused days have no occurrences and are not counted. */
    fun adherence(meds: List<MedicationData>, from: LocalDate, to: LocalDate, now: Instant, zone: ZoneId): Adherence {
        var result = Adherence(0, 0, 0, 0)
        for (data in meds) {
            var date = from
            while (!date.isAfter(to)) {
                for (s in OccurrenceRules.statusesOn(data, date, now, zone)) {
                    result += when (s.state) {
                        OccurrenceState.TAKEN -> Adherence(1, 0, 0, 0)
                        OccurrenceState.SKIPPED -> Adherence(0, 1, 0, 0)
                        OccurrenceState.MISSED -> Adherence(0, 0, 1, 0)
                        else -> Adherence(0, 0, 0, 1)
                    }
                }
                date = date.plusDays(1)
            }
        }
        return result
    }
}
