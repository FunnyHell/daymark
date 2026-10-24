package io.github.funnyhell.daymark.domain.common

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/** Lifecycle of a habit or medication. History is kept in every state. */
enum class LifecycleStatus { ACTIVE, PAUSED, ARCHIVED }

/**
 * A pause (or archive) interval for a habit or medication.
 *
 * [start] is inclusive, [end] is exclusive (the date tracking resumed). An open interval has `end == null`.
 */
data class PauseInterval(
    val id: String,
    val ownerId: String,
    val start: LocalDate,
    val end: LocalDate?,
) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(start) && (end == null || date.isBefore(end))
}

fun List<PauseInterval>.isPausedOn(date: LocalDate): Boolean = any { it.contains(date) }

fun LocalDate.startOfWeek(firstDayOfWeek: DayOfWeek): LocalDate =
    with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

/** Rounds to three decimals so that repeated fractional sums (for example 0.1 tablets) do not drift. */
fun Double.roundQuantity(): Double = (this * 1000.0).roundToLong() / 1000.0

/** Formats a quantity without a trailing ".0" for whole numbers. */
fun Double.formatQuantity(): String {
    val rounded = roundQuantity()
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
