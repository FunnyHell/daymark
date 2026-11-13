package io.github.funnyhell.daymark.platform

import io.github.funnyhell.daymark.domain.MONDAY
import io.github.funnyhell.daymark.domain.event
import io.github.funnyhell.daymark.domain.habit
import io.github.funnyhell.daymark.domain.habitData
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.pause
import io.github.funnyhell.daymark.platform.widgets.WeekMark
import io.github.funnyhell.daymark.platform.widgets.WidgetContent
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the week widget shows for each day and what a tap on it asks for. */
class WeekGridTest {
    private val today = MONDAY.plusDays(8)

    private fun grid(data: io.github.funnyhell.daymark.domain.habit.HabitData) =
        WidgetContent.weekGrid(listOf(data), today, DayOfWeek.MONDAY).single()

    @Test
    fun checkHabit_showsDoneMissedAndTodayOpen() {
        val row = grid(habitData(events = listOf(event(today.minusDays(1)), event(today.minusDays(3)))))
        assertEquals((6 downTo 0).map { today.minusDays(it.toLong()) }, row.cells.map { it.date })
        assertEquals(
            listOf(WeekMark.OPEN, WeekMark.OPEN, WeekMark.OPEN, WeekMark.DONE, WeekMark.OPEN, WeekMark.DONE, WeekMark.OPEN),
            row.cells.map { it.mark },
        )
        // A done day asks to be unmarked, every other day to be marked.
        assertEquals(listOf(true, true, true, false, true, false, true), row.cells.map { it.toggleTo })
    }

    @Test
    fun abstinence_isDoneUnlessASlipIsRecorded() {
        val data = habitData(
            habit = habit(type = HabitType.ABSTINENCE),
            events = listOf(event(today.minusDays(2), HabitEventType.LAPSE, amount = 0.0)),
        )
        val row = grid(data)
        assertEquals(WeekMark.SLIP, row.cells[4].mark)
        assertEquals(true, row.cells[4].toggleTo)
        assertEquals(WeekMark.DONE, row.cells[6].mark)
        assertEquals(false, row.cells[6].toggleTo)
        assertEquals(2, row.streak)
    }

    @Test
    fun daysBeforeStartAndPausedDaysCannotBeChanged() {
        val data = habitData(habit = habit(start = today.minusDays(3)), pauses = listOf(pause(today.minusDays(1), today)))
        val row = grid(data)
        assertEquals(listOf(null, null, null, true, true, null, true), row.cells.map { it.toggleTo })
        assertEquals(WeekMark.OFF, row.cells[0].mark)
        assertEquals(WeekMark.OFF, row.cells[5].mark)
    }

    @Test
    fun hiddenAndInactiveHabitsAreLeftOut() {
        val hidden = habitData(habit = habit().copy(showOnWidget = false))
        assertEquals(0, WidgetContent.weekGrid(listOf(hidden), today, DayOfWeek.MONDAY).size)
    }
}
