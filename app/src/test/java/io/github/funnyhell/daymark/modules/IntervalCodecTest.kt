package io.github.funnyhell.daymark.modules

import io.github.funnyhell.daymark.modules.intervals.BreakPlan
import io.github.funnyhell.daymark.modules.intervals.IntervalCodec
import io.github.funnyhell.daymark.modules.intervals.IntervalReminder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalCodecTest {
    @Test
    fun roundTrip() {
        val list = listOf(
            IntervalReminder("a", "Stretch", BreakPlan(60, LocalTime.of(9, 0), LocalTime.of(18, 0), setOf(DayOfWeek.MONDAY)), true, Instant.ofEpochMilli(1000), LocalDate.of(2024, 3, 4), 2),
            IntervalReminder("b", "Water", BreakPlan(30), enabled = false),
        )
        assertEquals(list, IntervalCodec.decode(IntervalCodec.encode(list)))
    }

    @Test
    fun brokenTextGivesAnEmptyListAndBrokenEntriesAreSkipped() {
        assertTrue(IntervalCodec.decode("not json").isEmpty())
        assertTrue(IntervalCodec.decode(null).isEmpty())
        val text = """[{"id":"x","title":"T","intervalMinutes":30,"from":"bad","until":"18:00","days":[]},
            {"id":"y","title":"U","intervalMinutes":30,"from":"09:00","until":"18:00","days":["MONDAY","NOT_A_DAY"],"extra":1}]"""
        val decoded = IntervalCodec.decode(text)
        assertEquals(listOf("y"), decoded.map { it.id })
        assertEquals(setOf(DayOfWeek.MONDAY), decoded.single().plan.days)
    }

    @Test
    fun doneCountsPerDayAndLaterPutsTheNextFifteenMinutesAway() {
        val day = LocalDate.of(2024, 3, 4)
        val r = IntervalReminder("a", "Stretch").done(Instant.parse("2024-03-04T10:00:00Z"), day).done(Instant.parse("2024-03-04T11:00:00Z"), day)
        assertEquals(2, r.countOn(day))
        assertEquals(0, r.countOn(day.plusDays(1)))
        assertEquals(1, r.done(Instant.parse("2024-03-05T10:00:00Z"), day.plusDays(1)).countOn(day.plusDays(1)))
        val later = r.later(Instant.parse("2024-03-04T12:00:00Z"))
        assertEquals(Instant.parse("2024-03-04T12:15:00Z"), later.anchor!!.plus(later.plan.interval))
    }
}
