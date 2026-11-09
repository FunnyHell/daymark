package io.github.funnyhell.daymark.domain

import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.Occurrence
import io.github.funnyhell.daymark.domain.reminder.ReminderKind
import io.github.funnyhell.daymark.domain.reminder.ReminderPlanner
import java.time.DayOfWeek
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPlannerTest {

    private fun plan(meds: List<io.github.funnyhell.daymark.domain.medication.MedicationData> = emptyList(), habits: List<io.github.funnyhell.daymark.domain.habit.HabitData> = emptyList(), now: java.time.Instant) =
        ReminderPlanner.plan(meds, habits, now, UTC, DayOfWeek.MONDAY)

    @Test
    fun plansUnresolvedDosesForTodayAndTomorrow() {
        val p = plan(meds = listOf(medData()), now = at(MONDAY, 7))
        assertEquals(4, p.size)
        assertEquals(at(MONDAY, 8), p.first().at)
        assertEquals("d1@2024-03-04", p.first().occurrenceKey)
    }

    @Test
    fun resolvedAndMissedDosesAreNotReminded() {
        val taken = medEvent(Occurrence.occurrenceKey("d1", MONDAY), MedicationEventType.TAKEN)
        val p = plan(meds = listOf(medData(events = listOf(taken))), now = at(MONDAY, 7))
        assertTrue(p.none { it.occurrenceKey == "d1@2024-03-04" })
        val late = plan(meds = listOf(medData()), now = at(MONDAY, 12))
        assertTrue(late.none { it.occurrenceKey == "d1@2024-03-04" })
    }

    @Test
    fun snoozedDoseIsRemindedAtSnoozeEnd() {
        val snooze = medEvent(Occurrence.occurrenceKey("d1", MONDAY), MedicationEventType.SNOOZED, at(MONDAY, 8), at(MONDAY, 8, 30))
        val p = plan(meds = listOf(medData(events = listOf(snooze))), now = at(MONDAY, 8, 1))
        assertEquals(at(MONDAY, 8, 30), p.first { it.occurrenceKey == "d1@2024-03-04" }.at)
    }

    @Test
    fun snoozedReminderComesBackWhenTheSnoozeEnds() {
        // Snoozed at 08:05 until 08:35; the alarm fires at 08:35 and the last check was at 08:05.
        val snooze = medEvent(Occurrence.occurrenceKey("d1", MONDAY), MedicationEventType.SNOOZED, at(MONDAY, 8, 5), at(MONDAY, 8, 35))
        val data = medData(events = listOf(snooze))
        val due = ReminderPlanner.dueBetween(plan(meds = listOf(data), now = at(MONDAY, 8, 35)), at(MONDAY, 8, 5), at(MONDAY, 8, 35))
        assertEquals(listOf("d1@2024-03-04"), due.map { it.occurrenceKey })
        // A snooze that runs past the dose's missed window still reminds when it ends.
        val late = medEvent(Occurrence.occurrenceKey("d1", MONDAY), MedicationEventType.SNOOZED, at(MONDAY, 10, 50), at(MONDAY, 11, 20))
        val lateDue = ReminderPlanner.dueBetween(
            plan(meds = listOf(medData(events = listOf(late))), now = at(MONDAY, 11, 20)), at(MONDAY, 10, 50), at(MONDAY, 11, 20),
        )
        assertEquals(listOf("d1@2024-03-04"), lateDue.map { it.occurrenceKey })
    }

    @Test
    fun pausedMedicationHasNoReminders() {
        val data = medData(pauses = listOf(pause(MONDAY, null)), medication = medication(status = LifecycleStatus.PAUSED))
        assertTrue(plan(meds = listOf(data), now = at(MONDAY, 7)).isEmpty())
    }

    @Test
    fun habitReminderOnlyWhenScheduledAndNotDone() {
        val h = habit(reminder = LocalTime.of(21, 0))
        val done = habitData(habit = h, events = listOf(event(MONDAY)))
        val p = plan(habits = listOf(done), now = at(MONDAY, 7))
        assertEquals(listOf(MONDAY.plusDays(1)), p.map { it.date })
        assertEquals(ReminderKind.HABIT, p.single().kind)
        val abst = habitData(habit = habit(type = HabitType.ABSTINENCE, reminder = LocalTime.of(21, 0)))
        assertTrue(plan(habits = listOf(abst), now = at(MONDAY, 7)).isEmpty())
    }

    @Test
    fun dueBetweenUsesCatchUpWindow() {
        val p = plan(meds = listOf(medData()), now = at(MONDAY, 7))
        // Device was off from 06:00 until 21:00: the 08:00 dose is older than the catch-up window.
        val due = ReminderPlanner.dueBetween(p, at(MONDAY, 6), at(MONDAY, 21))
        assertEquals(listOf("d2@2024-03-04"), due.map { it.occurrenceKey })
        assertEquals(at(MONDAY, 20), ReminderPlanner.nextAfter(p, at(MONDAY, 8)))
    }
}
