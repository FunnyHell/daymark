package io.github.funnyhell.daymark.domain

import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.Occurrence
import io.github.funnyhell.daymark.domain.stats.DayActivity
import io.github.funnyhell.daymark.domain.stats.HabitStatistics
import io.github.funnyhell.daymark.domain.stats.MedicationStatistics
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsTest {

    @Test
    fun dailyActivity_countsOnlyDueDays() {
        val mwf = habitData(
            schedules = listOf(schedule(recurrence = Recurrence.Weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))),
            events = listOf(event(MONDAY)),
        )
        val activity = HabitStatistics.dailyActivity(listOf(mwf), MONDAY, MONDAY.plusDays(3), MONDAY.plusDays(3), DayOfWeek.MONDAY)
        assertEquals(DayActivity(1, 1), activity[MONDAY])
        assertNull(activity[MONDAY.plusDays(1)])
        assertEquals(DayActivity(0, 1), activity[MONDAY.plusDays(2)])
    }

    @Test
    fun dailyActivity_excludesAbstinenceAndPausedDays() {
        val abst = habitData(habit = habit(type = HabitType.ABSTINENCE))
        val paused = habitData(pauses = listOf(pause(MONDAY, null)))
        assertEquals(emptyMap<Any, Any>(), HabitStatistics.dailyActivity(listOf(abst, paused), MONDAY, MONDAY.plusDays(2), MONDAY.plusDays(2), DayOfWeek.MONDAY))
    }

    @Test
    fun summary_combinesHabits() {
        val a = habitData(events = listOf(event(MONDAY), event(MONDAY.plusDays(1))))
        val b = habitData(events = listOf(event(MONDAY)))
        val s = HabitStatistics.summary(listOf(a, b), MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2), DayOfWeek.MONDAY)
        assertEquals(3, s.successes)
        assertEquals(1, s.failures)
        assertEquals(0.75, s.rate!!, 1e-9)
    }

    @Test
    fun quantitySeries_sumsPerDay() {
        val q = habitData(
            habit = habit(type = HabitType.QUANTITY),
            schedules = listOf(schedule(target = 10.0)),
            events = listOf(event(MONDAY, io.github.funnyhell.daymark.domain.habit.HabitEventType.PROGRESS, 4.0), event(MONDAY, io.github.funnyhell.daymark.domain.habit.HabitEventType.PROGRESS, 3.0)),
        )
        assertEquals(listOf(MONDAY to 7.0, MONDAY.plusDays(1) to 0.0), HabitStatistics.quantitySeries(q, MONDAY, MONDAY.plusDays(1)))
    }

    @Test
    fun adherence_countsTakenSkippedMissedAndOpen() {
        val events = listOf(
            medEvent(Occurrence.occurrenceKey("d1", MONDAY), MedicationEventType.TAKEN),
            medEvent(Occurrence.occurrenceKey("d2", MONDAY), MedicationEventType.SKIPPED),
            medEvent(Occurrence.occurrenceKey("d1", MONDAY.plusDays(1)), MedicationEventType.TAKEN, voided = true),
        )
        val a = MedicationStatistics.adherence(listOf(medData(events = events)), MONDAY, MONDAY.plusDays(1), at(MONDAY.plusDays(1), 12), UTC)
        assertEquals(1, a.taken)
        assertEquals(1, a.skipped)
        assertEquals(1, a.missed) // 08:00 on day 2, corrected taken → missed by 12:00
        assertEquals(1, a.open) // 20:00 on day 2
        assertEquals(1.0 / 3, a.takenRate!!, 1e-9)
    }

    @Test
    fun dayFractions_oneSectorPerDueHabit() {
        val check = habitData(events = listOf(event(MONDAY)))
        val multi = habitData(habit = habit(type = HabitType.MULTI), schedules = listOf(schedule(target = 4.0)), events = listOf(event(MONDAY)))
        val weekly = habitData(habit = habit(type = HabitType.WEEKLY_GOAL), schedules = listOf(schedule(target = 2.0)), events = listOf(event(MONDAY)))
        val quit = habitData(habit = habit(type = HabitType.ABSTINENCE))
        val paused = habitData(pauses = listOf(pause(MONDAY, null)))
        val fractions = HabitStatistics.dayFractions(listOf(check, multi, weekly, quit, paused), MONDAY, DayOfWeek.MONDAY)
        assertEquals(listOf(1.0, 0.25, 0.5, 1.0), fractions)
    }
}
