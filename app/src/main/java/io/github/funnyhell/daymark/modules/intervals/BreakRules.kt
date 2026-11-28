package io.github.funnyhell.daymark.modules.intervals

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** When an interval reminder is due: every [intervalMinutes] between [from] and [until] on [days]. */
data class BreakPlan(
    val intervalMinutes: Int = 60,
    val from: LocalTime = LocalTime.of(9, 0),
    val until: LocalTime = LocalTime.of(18, 0),
    val days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
) {
    val interval: Duration get() = Duration.ofMinutes(intervalMinutes.toLong().coerceAtLeast(MIN_INTERVAL_MINUTES))

    companion object {
        const val MIN_INTERVAL_MINUTES = 5L
        val INTERVAL_CHOICES = listOf(15, 30, 45, 60, 90, 120, 180)
    }
}

object BreakRules {
    /** Reminders that came due this long ago are still offered (late alarm, phone off). */
    val CATCH_UP: Duration = Duration.ofHours(2)

    /** How long "Later" puts off a break. */
    val LATER: Duration = Duration.ofMinutes(15)

    /**
     * Reminder times on the days from today, today included, for [days] days. Inside the active hours of a day the
     * first one comes one interval after the start, or one interval after [anchor] (the last time it was done, see
     * [anchorAfterDone] and [anchorForLater]) when that falls inside the same hours. None is later than [BreakPlan.until].
     */
    fun upcoming(plan: BreakPlan, anchor: Instant?, now: Instant, zone: ZoneId, days: Int = 2): List<Instant> {
        if (!plan.until.isAfter(plan.from) || plan.days.isEmpty()) return emptyList()
        val interval = plan.interval
        val earliest = now.minus(CATCH_UP)
        val today = now.atZone(zone).toLocalDate()
        val result = mutableListOf<Instant>()
        for (offset in 0 until days) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek !in plan.days) continue
            val start = date.atTime(plan.from).atZone(zone).toInstant()
            val end = date.atTime(plan.until).atZone(zone).toInstant()
            val base = anchor?.takeIf { it.isBefore(end) && it.plus(interval).isAfter(start) } ?: start
            var next = base.plus(interval)
            while (!next.isAfter(end)) {
                if (!next.isBefore(earliest)) result += next
                next = next.plus(interval)
            }
        }
        return result
    }

    /** A break taken at [at]: the next one is a full interval later. */
    fun anchorAfterDone(at: Instant): Instant = at

    /** "Later" at [at]: the next break comes after [LATER] instead of a full interval. */
    fun anchorForLater(plan: BreakPlan, at: Instant): Instant = at.plus(LATER).minus(plan.interval)
}
