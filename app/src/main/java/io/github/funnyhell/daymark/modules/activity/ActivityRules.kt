package io.github.funnyhell.daymark.modules.activity

import java.time.LocalDate
import java.time.ZoneId

object ActivityRules {
    /**
     * Days on which the linked habit should be marked: the start days of workouts not handled yet. A workout that
     * was handled (marked once) is never used again, so a mark the user removed by hand stays removed.
     */
    fun daysToMark(workouts: List<Workout>, handled: Set<String>, zone: ZoneId): Map<LocalDate, List<String>> =
        workouts.filter { it.id !in handled }
            .groupBy({ it.start.atZone(zone).toLocalDate() }, { it.id })
}
