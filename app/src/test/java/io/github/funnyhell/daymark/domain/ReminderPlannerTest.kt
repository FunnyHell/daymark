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
    fun doseSnoozedPastMidnightComesBackTheNextDay() {
        // The 20:00 dose of Monday is snoozed at 23:50; the reminder is due at 00:20 on Tuesday.
        val tuesday = MONDAY.plusDays(1)
        val snooze = medEvent(Occurrence.occurrenceKey("d2", MONDAY), MedicationEventType.SNOOZED, at(MONDAY, 23, 50), at(tuesday, 0, 20))
        val data = medData(events = listOf(snooze))
        val armed = plan(meds = listOf(data), now = at(tuesday, 0, 1))
        assertEquals(at(tuesday, 0, 20), ReminderPlanner.nextAfter(armed, at(MONDAY, 23, 50)))
        val due = ReminderPlanner.dueBetween(plan(meds = listOf(data), now = at(tuesday, 0, 20)), at(MONDAY, 23, 50), at(tuesday, 0, 20))
        assertEquals(listOf("d2@$MONDAY"), due.map { it.occurrenceKey })
        // Long after the snooze ended the old dose is no longer planned.
        assertTrue(plan(meds = listOf(data), now = at(tuesday, 9)).none { it.occurrenceKey == "d2@$MONDAY" })
    }

    private fun planRepeating(meds: List<io.github.funnyhell.daymark.domain.medication.MedicationData>, now: java.time.Instant) =
        ReminderPlanner.plan(meds, emptyList(), now, UTC, DayOfWeek.MONDAY, medicationRepeat = java.time.Duration.ofMinutes(5))

    @Test
    fun unmarkedDoseIsRemindedAgainEveryFiveMinutesUntilTheMissedWindowEnds() {
        val key = "d1@2024-03-04"
        val times = planRepeating(listOf(medData()), now = at(MONDAY, 7)).filter { it.occurrenceKey == key }.map { it.at }
        assertEquals(at(MONDAY, 8), times.first())
        assertEquals(at(MONDAY, 8, 5), times[1])
        assertEquals(at(MONDAY, 10, 55), times.last())
        assertEquals(36, times.size)
        // The alarm after the 08:00 reminder is the 08:05 repeat; at 08:05 that repeat is due.
        val plan = planRepeating(listOf(medData()), now = at(MONDAY, 8, 1))
        assertEquals(at(MONDAY, 8, 5), ReminderPlanner.nextAfter(plan, at(MONDAY, 8)))
        val due = ReminderPlanner.dueBetween(planRepeating(listOf(medData()), now = at(MONDAY, 8, 5)), at(MONDAY, 8), at(MONDAY, 8, 5))
        assertEquals(listOf(key), due.map { it.occurrenceKey })
        assertTrue(due.single().repeat)
    }

    @Test
    fun takenOrSkippedDoseIsNotRepeated() {
        for (type in listOf(MedicationEventType.TAKEN, MedicationEventType.SKIPPED)) {
            val data = medData(events = listOf(medEvent("d1@2024-03-04", type, at(MONDAY, 8, 2))))
            assertTrue(planRepeating(listOf(data), now = at(MONDAY, 8, 3)).none { it.occurrenceKey == "d1@2024-03-04" })
        }
    }

    @Test
    fun snoozeRestartsTheRepeatsFromTheSnoozeEnd() {
        // Snoozed at 08:02 for 30 minutes: nothing until 08:32, then 08:37, 08:42...
        val snooze = medEvent("d1@2024-03-04", MedicationEventType.SNOOZED, at(MONDAY, 8, 2), at(MONDAY, 8, 32))
        val plan = planRepeating(listOf(medData(events = listOf(snooze))), now = at(MONDAY, 8, 3)).filter { it.occurrenceKey == "d1@2024-03-04" }
        assertEquals(at(MONDAY, 8, 32), ReminderPlanner.nextAfter(plan, at(MONDAY, 8, 2)))
        assertEquals(listOf(at(MONDAY, 8, 32), at(MONDAY, 8, 37), at(MONDAY, 8, 42)), plan.take(3).map { it.at })
    }

    @Test
    fun withoutRepeatEachDoseIsRemindedOnce() {
        val p = plan(meds = listOf(medData()), now = at(MONDAY, 7))
        assertEquals(1, p.count { it.occurrenceKey == "d1@2024-03-04" })
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
