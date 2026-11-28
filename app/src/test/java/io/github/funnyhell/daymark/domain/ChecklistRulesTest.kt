package io.github.funnyhell.daymark.domain

import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.PeriodOutcome
import io.github.funnyhell.daymark.domain.stats.HabitStatistics
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checklists: a day (or week) is done only when every step is; partial progress counts as a share. */
class ChecklistRulesTest {
    private val tue = MONDAY.plusDays(1)
    private val wed = MONDAY.plusDays(2)

    private fun routine(vararg events: io.github.funnyhell.daymark.domain.habit.HabitEvent) = habitData(
        habit = habit(type = HabitType.CHECKLIST),
        // Brush teeth twice, shower once.
        steps = listOf(step("teeth", target = 2, order = 0), step("shower", order = 1)),
        events = events.toList(),
    )

    @Test
    fun dayIsDoneOnlyWhenEveryStepReachesItsTarget() {
        val data = routine(
            event(MONDAY, stepId = "teeth"), event(MONDAY, stepId = "teeth"), event(MONDAY, stepId = "shower"),
            // Tuesday: teeth three times (counts as two) and no shower.
            event(tue, stepId = "teeth"), event(tue, stepId = "teeth"), event(tue, stepId = "teeth"),
        )
        val e = HabitEvaluator(data)
        assertEquals(DayState.DONE, e.dayResult(MONDAY, wed).state)
        val tuesday = e.dayResult(tue, wed)
        assertEquals(DayState.PARTIAL, tuesday.state)
        assertEquals(2.0 / 3.0, tuesday.fraction, 1e-9)
        assertEquals(3.0, e.targetOn(tue), 0.0)
        // Wednesday is today and still open; Tuesday broke the streak.
        assertEquals(0, e.streaks(wed).current)
        assertEquals(1, e.streaks(wed).best)
    }

    @Test
    fun partialDaysCountPartlyInPeriodProgress() {
        val data = routine(event(MONDAY, stepId = "teeth"), event(MONDAY, stepId = "shower"))
        // Monday: 2 of 3 step-marks done; one day in the period.
        assertEquals(2.0 / 3.0, HabitStatistics.periodProgress(listOf(data), MONDAY, MONDAY, DayOfWeek.MONDAY)!!, 1e-9)
        assertEquals(listOf(2.0 / 3.0), HabitStatistics.dayFractions(listOf(data), MONDAY, DayOfWeek.MONDAY))
    }

    @Test
    fun stepsAddedOrRemovedLaterDoNotChangeEarlierDays() {
        val data = habitData(
            habit = habit(type = HabitType.CHECKLIST),
            steps = listOf(step("a"), step("b", until = tue), step("c", from = tue)),
            events = listOf(event(MONDAY, stepId = "a"), event(MONDAY, stepId = "b"), event(tue, stepId = "a")),
        )
        val e = HabitEvaluator(data)
        assertEquals(DayState.DONE, e.dayResult(MONDAY, wed).state)
        assertEquals(listOf("a", "c"), e.stepsOn(tue).map { it.id })
        assertEquals(DayState.PARTIAL, e.dayResult(tue, wed).state)
    }

    @Test
    fun weeklyChecklistIsDoneWhenAllStepsAreDoneOnAnyDaysOfTheWeek() {
        val cleaning = habitData(
            habit = habit(type = HabitType.WEEKLY_CHECKLIST),
            steps = listOf(step("kitchen"), step("bathroom"), step("floors", target = 2)),
            events = listOf(
                event(MONDAY, stepId = "kitchen"), event(wed, stepId = "bathroom"),
                event(wed, stepId = "floors"), event(MONDAY.plusDays(5), stepId = "floors"),
                // Next week: only the kitchen.
                event(MONDAY.plusDays(7), stepId = "kitchen"),
            ),
        )
        val e = HabitEvaluator(cleaning, DayOfWeek.MONDAY)
        val week1 = e.weekResult(MONDAY, MONDAY.plusDays(14))
        assertEquals(PeriodOutcome.SUCCESS, week1.outcome)
        assertEquals(4.0, week1.goal, 0.0)
        val week2 = e.weekResult(MONDAY.plusDays(7), MONDAY.plusDays(14))
        assertEquals(PeriodOutcome.FAILURE, week2.outcome)
        assertEquals(1.0, week2.completions, 0.0)
        // Progress within the week, as Home shows it on Wednesday.
        assertEquals(listOf(1, 1, 1), e.stepProgress(wed).map { it.done })
        assertTrue(e.stepProgress(MONDAY.plusDays(6)).all { it.complete })
        // Streaks count weeks for a weekly checklist.
        assertEquals(1, e.streaks(MONDAY.plusDays(13)).best)
    }

    @Test
    fun notesDoNotCountAsProgress() {
        val data = habitData(events = listOf(event(MONDAY, HabitEventType.NOTE, amount = 0.0)))
        assertEquals(0.0, HabitEvaluator(data).progressOn(MONDAY), 0.0)
    }
}
