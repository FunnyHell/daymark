package io.github.funnyhell.daymark.modules

import io.github.funnyhell.daymark.modules.intervals.BreakPlan
import io.github.funnyhell.daymark.modules.intervals.BreakRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakRulesTest {
    /** 2024-03-04 is a Monday. */
    private val monday = LocalDate.of(2024, 3, 4)
    private val utc = ZoneOffset.UTC
    private fun at(date: LocalDate, h: Int, m: Int = 0): Instant = date.atTime(h, m).toInstant(utc)
    private val plan = BreakPlan(intervalMinutes = 60, from = LocalTime.of(9, 0), until = LocalTime.of(12, 0))

    @Test
    fun breaksEveryIntervalInsideActiveHours() {
        val slots = BreakRules.upcoming(plan, anchor = null, now = at(monday, 8), zone = utc, days = 1)
        assertEquals(listOf(at(monday, 10), at(monday, 11), at(monday, 12)), slots)
    }

    @Test
    fun aBreakTakenMovesTheNextOneAFullIntervalOn() {
        val slots = BreakRules.upcoming(plan, anchor = at(monday, 10, 20), now = at(monday, 10, 20), zone = utc, days = 1)
        assertEquals(listOf(at(monday, 11, 20)), slots)
    }

    @Test
    fun laterPutsTheNextBreakFifteenMinutesAway() {
        val anchor = BreakRules.anchorForLater(plan, at(monday, 10))
        val slots = BreakRules.upcoming(plan, anchor, now = at(monday, 10), zone = utc, days = 1)
        assertEquals(at(monday, 10, 15), slots.first())
    }

    @Test
    fun anAnchorFromAnotherDayDoesNotShiftToday() {
        val slots = BreakRules.upcoming(plan, anchor = at(monday.minusDays(3), 10, 20), now = at(monday, 8), zone = utc, days = 1)
        assertEquals(at(monday, 10), slots.first())
    }

    @Test
    fun weekendsAndInvalidHoursHaveNoBreaks() {
        val saturday = monday.plusDays(5)
        assertTrue(BreakRules.upcoming(plan, null, at(saturday, 8), utc, days = 2).isEmpty())
        assertTrue(BreakRules.upcoming(plan.copy(until = LocalTime.of(8, 0)), null, at(monday, 8), utc).isEmpty())
        assertTrue(BreakRules.upcoming(plan.copy(days = emptySet()), null, at(monday, 8), utc).isEmpty())
    }

    @Test
    fun tomorrowStartsAgainFromTheStartOfActiveHours() {
        val slots = BreakRules.upcoming(plan.copy(days = DayOfWeek.entries.toSet()), anchor = at(monday, 11, 30), now = at(monday, 13), zone = utc, days = 2)
        assertEquals(at(monday.plusDays(1), 10), slots.first { it.isAfter(at(monday, 13)) })
    }
}
