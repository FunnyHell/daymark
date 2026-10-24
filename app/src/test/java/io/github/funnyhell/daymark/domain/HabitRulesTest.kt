package io.github.funnyhell.daymark.domain

import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.PeriodOutcome
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.domain.habit.StreakCalculator
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitRulesTest {

    private fun eval(data: io.github.funnyhell.daymark.domain.habit.HabitData) = HabitEvaluator(data, DayOfWeek.MONDAY)

    @Test
    fun dailyHabit_doneMissedPendingStates() {
        val data = habitData(events = listOf(event(MONDAY), event(MONDAY.plusDays(2))))
        val today = MONDAY.plusDays(3)
        val e = eval(data)
        assertEquals(DayState.DONE, e.dayResult(MONDAY, today).state)
        assertEquals(DayState.MISSED, e.dayResult(MONDAY.plusDays(1), today).state)
        assertEquals(DayState.DONE, e.dayResult(MONDAY.plusDays(2), today).state)
        assertEquals(DayState.PENDING, e.dayResult(today, today).state)
        assertEquals(DayState.FUTURE, e.dayResult(today.plusDays(1), today).state)
        assertEquals(DayState.BEFORE_START, e.dayResult(MONDAY.minusDays(1), today).state)
    }

    @Test
    fun pendingTodayDoesNotBreakStreak() {
        val data = habitData(events = (0..4L).map { event(MONDAY.plusDays(it)) })
        val streak = eval(data).streaks(MONDAY.plusDays(5))
        assertEquals(5, streak.current)
        assertEquals(5, streak.best)
    }

    @Test
    fun missedDayBreaksCurrentStreakButKeepsBest() {
        val events = listOf(0L, 1L, 2L, 4L).map { event(MONDAY.plusDays(it)) }
        val streak = eval(habitData(events = events)).streaks(MONDAY.plusDays(4))
        assertEquals(1, streak.current)
        assertEquals(3, streak.best)
    }

    @Test
    fun voidedEventDoesNotCount() {
        val data = habitData(events = listOf(event(MONDAY, voided = true)))
        assertEquals(DayState.MISSED, eval(data).dayResult(MONDAY, MONDAY.plusDays(1)).state)
    }

    @Test
    fun weekdayRecurrence_unscheduledDaysAreNeutral() {
        val rec = Recurrence.Weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        val data = habitData(
            schedules = listOf(schedule(recurrence = rec)),
            events = listOf(event(MONDAY), event(MONDAY.plusDays(2)), event(MONDAY.plusDays(4))),
        )
        val e = eval(data)
        val today = MONDAY.plusDays(6)
        assertEquals(DayState.NOT_SCHEDULED, e.dayResult(MONDAY.plusDays(1), today).state)
        assertEquals(3, e.streaks(today).current)
        val rate = e.completionRate(MONDAY, today, today)
        assertEquals(3, rate.successes)
        assertEquals(0, rate.failures)
    }

    @Test
    fun everyNDays_countsFromScheduleStart() {
        val data = habitData(schedules = listOf(schedule(recurrence = Recurrence.EveryNDays(3))))
        val e = eval(data)
        assertTrue(e.recurrenceMatches(MONDAY))
        assertFalse(e.recurrenceMatches(MONDAY.plusDays(1)))
        assertFalse(e.recurrenceMatches(MONDAY.plusDays(2)))
        assertTrue(e.recurrenceMatches(MONDAY.plusDays(3)))
        assertTrue(e.recurrenceMatches(MONDAY.plusDays(6)))
    }

    @Test
    fun pausedDaysAreNeutralAndDoNotCreateFalseStreak() {
        val data = habitData(
            pauses = listOf(pause(MONDAY.plusDays(2), MONDAY.plusDays(5))),
            events = listOf(event(MONDAY), event(MONDAY.plusDays(1)), event(MONDAY.plusDays(5))),
        )
        val e = eval(data)
        val today = MONDAY.plusDays(6)
        assertEquals(DayState.PAUSED, e.dayResult(MONDAY.plusDays(3), today).state)
        assertFalse(e.isScheduled(MONDAY.plusDays(3)))
        // Two days before the pause + one after; pause neither breaks nor extends the streak.
        assertEquals(3, e.streaks(today).current)
        val rate = e.completionRate(MONDAY, MONDAY.plusDays(5), today)
        assertEquals(3, rate.successes)
        assertEquals(0, rate.failures)
    }

    @Test
    fun openPauseCoversFutureDays() {
        val data = habitData(pauses = listOf(pause(MONDAY.plusDays(1), null)))
        val e = eval(data)
        assertEquals(DayState.PAUSED, e.dayResult(MONDAY.plusDays(10), MONDAY.plusDays(20)).state)
    }

    @Test
    fun scheduleChangeDoesNotRewritePastDays() {
        // Daily for the first week, then Mondays only.
        val first = schedule(from = MONDAY, until = MONDAY.plusDays(7), recurrence = Recurrence.Daily)
        val second = schedule(from = MONDAY.plusDays(7), recurrence = Recurrence.Weekdays(setOf(DayOfWeek.MONDAY)))
        val data = habitData(schedules = listOf(first, second), events = listOf(event(MONDAY)))
        val e = eval(data)
        val today = MONDAY.plusDays(10)
        // Tuesday of week 1 stays missed under the old schedule.
        assertEquals(DayState.MISSED, e.dayResult(MONDAY.plusDays(1), today).state)
        // Tuesday of week 2 is unscheduled under the new schedule.
        assertEquals(DayState.NOT_SCHEDULED, e.dayResult(MONDAY.plusDays(8), today).state)
    }

    @Test
    fun multiTimesPerDay_partialIsNotDone() {
        val data = habitData(
            habit = habit(type = HabitType.MULTI),
            schedules = listOf(schedule(target = 3.0)),
            events = listOf(event(MONDAY), event(MONDAY), event(MONDAY.plusDays(1)), event(MONDAY.plusDays(1)), event(MONDAY.plusDays(1))),
        )
        val e = eval(data)
        val today = MONDAY.plusDays(2)
        val d0 = e.dayResult(MONDAY, today)
        assertEquals(DayState.PARTIAL, d0.state)
        assertEquals(2.0, d0.progress, 0.0)
        assertEquals(DayState.DONE, e.dayResult(MONDAY.plusDays(1), today).state)
        assertEquals(1, e.streaks(today).current)
    }

    @Test
    fun quantitative_sumsProgressAgainstTarget() {
        val data = habitData(
            habit = habit(type = HabitType.QUANTITY),
            schedules = listOf(schedule(target = 2000.0)),
            events = listOf(
                event(MONDAY, HabitEventType.PROGRESS, 500.0),
                event(MONDAY, HabitEventType.PROGRESS, 1500.0),
                event(MONDAY.plusDays(1), HabitEventType.PROGRESS, 750.0),
                event(MONDAY.plusDays(1), HabitEventType.PROGRESS, 750.0, voided = true),
            ),
        )
        val e = eval(data)
        val today = MONDAY.plusDays(1)
        assertEquals(DayState.DONE, e.dayResult(MONDAY, today).state)
        val d1 = e.dayResult(today, today)
        assertEquals(DayState.PENDING, d1.state)
        assertEquals(750.0, d1.progress, 0.0)
        assertEquals(0.375, d1.fraction, 1e-9)
        assertEquals(2750.0, e.totalProgress(), 0.0)
    }

    @Test
    fun skippedDayIsNeutral() {
        val data = habitData(events = listOf(event(MONDAY), event(MONDAY.plusDays(1), HabitEventType.SKIP), event(MONDAY.plusDays(2))))
        val e = eval(data)
        val today = MONDAY.plusDays(3)
        assertEquals(DayState.SKIPPED, e.dayResult(MONDAY.plusDays(1), today).state)
        assertEquals(2, e.streaks(today).current)
    }

    @Test
    fun weeklyGoal_successFailureAndInProgress() {
        val habit = habit(type = HabitType.WEEKLY_GOAL)
        val events = listOf(
            // Week 1: 3 of 3.
            event(MONDAY), event(MONDAY.plusDays(2)), event(MONDAY.plusDays(4)),
            // Week 2: 1 of 3.
            event(MONDAY.plusDays(8)),
            // Week 3 (current): 1 so far.
            event(MONDAY.plusDays(14)),
        )
        val data = habitData(habit = habit, schedules = listOf(schedule(target = 3.0)), events = events)
        val e = eval(data)
        val today = MONDAY.plusDays(15)
        val weeks = e.weeks(MONDAY, today)
        assertEquals(listOf(PeriodOutcome.SUCCESS, PeriodOutcome.FAILURE, PeriodOutcome.IN_PROGRESS), weeks.map { it.outcome })
        assertEquals(1.0, weeks[2].completions, 0.0)
        val streak = e.streaks(today)
        assertEquals(0, streak.current)
        assertEquals(1, streak.best)
    }

    @Test
    fun weeklyGoal_reachedEarlyInCurrentWeekIsSuccess() {
        val data = habitData(
            habit = habit(type = HabitType.WEEKLY_GOAL),
            schedules = listOf(schedule(target = 2.0)),
            events = listOf(event(MONDAY), event(MONDAY.plusDays(1))),
        )
        assertEquals(PeriodOutcome.SUCCESS, eval(data).weekResult(MONDAY, MONDAY.plusDays(1)).outcome)
    }

    @Test
    fun weeklyGoal_partlyPausedWeekWithoutGoalIsNeutral() {
        val data = habitData(
            habit = habit(type = HabitType.WEEKLY_GOAL),
            schedules = listOf(schedule(target = 3.0)),
            pauses = listOf(pause(MONDAY.plusDays(3), MONDAY.plusDays(7))),
            events = listOf(event(MONDAY)),
        )
        assertEquals(PeriodOutcome.NEUTRAL, eval(data).weekResult(MONDAY, MONDAY.plusDays(10)).outcome)
    }

    @Test
    fun weeklyGoal_firstPartialWeekIsNeutralIfNotMet() {
        val data = habitData(
            habit = habit(type = HabitType.WEEKLY_GOAL, start = MONDAY.plusDays(4)),
            schedules = listOf(schedule(from = MONDAY.plusDays(4), target = 3.0)),
        )
        assertEquals(PeriodOutcome.NEUTRAL, eval(data).weekResult(MONDAY, MONDAY.plusDays(10)).outcome)
    }

    @Test
    fun weeklyGoal_respectsSundayWeekStart() {
        val data = habitData(
            habit = habit(type = HabitType.WEEKLY_GOAL, start = MONDAY.minusDays(1)),
            schedules = listOf(schedule(from = MONDAY.minusDays(1), target = 1.0)),
            events = listOf(event(MONDAY.minusDays(1))),
        )
        val sundayWeeks = HabitEvaluator(data, DayOfWeek.SUNDAY).weeks(MONDAY.minusDays(1), MONDAY.plusDays(7))
        assertEquals(MONDAY.minusDays(1), sundayWeeks.first().weekStart)
        assertEquals(PeriodOutcome.SUCCESS, sundayWeeks.first().outcome)
    }

    @Test
    fun abstinence_lapsesSplitRunsWithoutErasingHistory() {
        val data = habitData(
            habit = habit(type = HabitType.ABSTINENCE),
            events = listOf(event(MONDAY.plusDays(3), HabitEventType.LAPSE), event(MONDAY.plusDays(6), HabitEventType.LAPSE)),
        )
        val e = eval(data)
        val today = MONDAY.plusDays(9)
        val runs = e.cleanRuns(today)
        assertEquals(listOf(3, 2, 3), runs.map { it.days })
        assertEquals(listOf(true, true, false), runs.map { it.endedByLapse })
        val streak = e.streaks(today)
        assertEquals(3, streak.current)
        assertEquals(3, streak.best)
        assertEquals(2, e.lapses().size)
        assertEquals(DayState.LAPSED, e.dayResult(MONDAY.plusDays(3), today).state)
    }

    @Test
    fun abstinence_correctedLapseRestoresStreak() {
        val data = habitData(
            habit = habit(type = HabitType.ABSTINENCE),
            events = listOf(event(MONDAY.plusDays(2), HabitEventType.LAPSE, voided = true)),
        )
        assertEquals(5, eval(data).streaks(MONDAY.plusDays(4)).current)
    }

    @Test
    fun abstinence_pausedDaysDoNotCount() {
        val data = habitData(
            habit = habit(type = HabitType.ABSTINENCE),
            pauses = listOf(pause(MONDAY.plusDays(1), MONDAY.plusDays(4))),
        )
        // Monday + days 4,5 = 3 clean days counted.
        assertEquals(3, eval(data).streaks(MONDAY.plusDays(5)).current)
    }

    @Test
    fun completionRate_nullWhenNothingEvaluated() {
        val data = habitData(pauses = listOf(pause(MONDAY, null)))
        assertNull(eval(data).completionRate(MONDAY, MONDAY.plusDays(5), MONDAY.plusDays(5)).rate)
    }

    @Test
    fun streakCalculator_neutralUnitsAreSkipped() {
        val s = StreakCalculator.summarize(
            listOf(PeriodOutcome.SUCCESS, PeriodOutcome.NEUTRAL, PeriodOutcome.SUCCESS, PeriodOutcome.FAILURE, PeriodOutcome.SUCCESS, PeriodOutcome.IN_PROGRESS),
        )
        assertEquals(1, s.current)
        assertEquals(2, s.best)
    }
}
