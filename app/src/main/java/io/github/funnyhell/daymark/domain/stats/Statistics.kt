package io.github.funnyhell.daymark.domain.stats

import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
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
                    data.habit.type == HabitType.WEEKLY_GOAL -> if (day.state == DayState.DONE) 1 to 1 else 0 to 0
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
