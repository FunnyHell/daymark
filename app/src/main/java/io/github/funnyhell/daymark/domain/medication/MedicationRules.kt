package io.github.funnyhell.daymark.domain.medication

import io.github.funnyhell.daymark.domain.common.isPausedOn
import io.github.funnyhell.daymark.domain.common.roundQuantity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class OccurrenceState {
    /** Due time has not arrived yet. */
    UPCOMING,

    /** Due time passed, still inside the missed window, nothing recorded. */
    DUE,

    /** Reminder deferred; nothing recorded about intake. */
    SNOOZED,
    TAKEN,
    SKIPPED,

    /** No TAKEN/SKIPPED recorded and the missed window has elapsed (see [OccurrenceRules.MISSED_AFTER]). */
    MISSED,
}

data class OccurrenceStatus(
    val occurrence: Occurrence,
    val state: OccurrenceState,
    /** The active TAKEN or SKIPPED event, if any. */
    val resolution: MedicationEvent?,
    /** When the reminder should fire again because of a snooze. */
    val snoozedUntil: Instant?,
)

object OccurrenceRules {
    /**
     * An unresolved occurrence becomes MISSED this long after its due time (or after the end of an
     * active snooze, whichever is later). Missed is derived, never stored, and a dose can still be
     * recorded as taken afterwards.
     */
    val MISSED_AFTER: Duration = Duration.ofHours(3)

    fun scheduleOn(schedules: List<MedicationSchedule>, date: LocalDate): MedicationSchedule? =
        schedules.firstOrNull { it.isEffectiveOn(date) }

    /** Occurrences generated for [date]. Paused (or archived) days produce none. */
    fun occurrencesOn(data: MedicationData, date: LocalDate): List<Occurrence> {
        if (data.pauses.isPausedOn(date)) return emptyList()
        val schedule = scheduleOn(data.schedules, date) ?: return emptyList()
        if (date.dayOfWeek !in schedule.days) return emptyList()
        return schedule.doses.sortedBy { it.time }.map { dose ->
            Occurrence(
                medicationId = data.medication.id,
                scheduleId = schedule.id,
                doseId = dose.id,
                date = date,
                time = dose.time,
                doseLabel = dose.doseLabel,
                quantity = dose.quantity,
            )
        }
    }

    fun findOccurrence(data: MedicationData, key: String): Occurrence? {
        val date = parseKeyDate(key) ?: return null
        return occurrencesOn(data, date).firstOrNull { it.key == key }
    }

    fun parseKeyDate(key: String): LocalDate? =
        runCatching { LocalDate.parse(key.substringAfterLast('@')) }.getOrNull()

    fun activeResolution(events: List<MedicationEvent>, key: String): MedicationEvent? =
        events.filter { it.occurrenceKey == key && it.isActive && it.isResolution }.maxByOrNull { it.recordedAt }

    fun status(occurrence: Occurrence, events: List<MedicationEvent>, now: Instant, zone: ZoneId): OccurrenceStatus {
        val resolution = activeResolution(events, occurrence.key)
        if (resolution != null) {
            val state = if (resolution.type == MedicationEventType.TAKEN) OccurrenceState.TAKEN else OccurrenceState.SKIPPED
            return OccurrenceStatus(occurrence, state, resolution, null)
        }
        val due = occurrence.dueAt(zone)
        val lastSnooze = events
            .filter { it.occurrenceKey == occurrence.key && it.isActive && it.type == MedicationEventType.SNOOZED }
            .maxByOrNull { it.recordedAt }
        val snoozedUntil = lastSnooze?.snoozedUntil
        val state = when {
            snoozedUntil != null && now.isBefore(snoozedUntil) -> OccurrenceState.SNOOZED
            now.isBefore(due) -> OccurrenceState.UPCOMING
            now.isBefore(maxOf(due, snoozedUntil ?: due).plus(MISSED_AFTER)) -> OccurrenceState.DUE
            else -> OccurrenceState.MISSED
        }
        return OccurrenceStatus(occurrence, state, null, snoozedUntil?.takeIf { now.isBefore(it) })
    }

    fun statusesOn(data: MedicationData, date: LocalDate, now: Instant, zone: ZoneId): List<OccurrenceStatus> =
        occurrencesOn(data, date).map { status(it, data.events, now, zone) }

    /**
     * Schedule edits take effect today unless a dose of today has already been recorded under the current
     * version; then they take effect tomorrow, so that today's recorded doses keep their meaning and an edited
     * time cannot make an already-taken dose appear due again.
     */
    fun editEffectiveDate(data: MedicationData, today: LocalDate): LocalDate {
        val current = scheduleOn(data.schedules, today) ?: return today
        if (current.effectiveFrom.isAfter(today)) return current.effectiveFrom
        val todaysKeys = current.doses.map { Occurrence.occurrenceKey(it.id, today) }.toSet()
        val recordedToday = data.events.any { it.isActive && it.occurrenceKey in todaysKeys }
        return if (recordedToday) today.plusDays(1) else today
    }
}

/** What to do with a requested TAKEN / SKIPPED / SNOOZED action. */
sealed interface ActionDecision {
    data class Apply(val occurrence: Occurrence) : ActionDecision

    /** The occurrence already has an active TAKEN or SKIPPED; the request is a duplicate or stale. */
    data class AlreadyResolved(val existing: MedicationEvent) : ActionDecision

    data class Rejected(val reason: RejectReason) : ActionDecision
}

enum class RejectReason {
    /** The occurrence is not part of the current schedule (edited, paused, archived, or never existed). */
    UNKNOWN_OCCURRENCE,

    /** Doses for future dates cannot be recorded. */
    FUTURE_DATE,
}

object ActionRules {
    fun decide(data: MedicationData, occurrenceKey: String, today: LocalDate): ActionDecision {
        OccurrenceRules.activeResolution(data.events, occurrenceKey)?.let { return ActionDecision.AlreadyResolved(it) }
        val date = OccurrenceRules.parseKeyDate(occurrenceKey)
            ?: return ActionDecision.Rejected(RejectReason.UNKNOWN_OCCURRENCE)
        if (date.isAfter(today)) return ActionDecision.Rejected(RejectReason.FUTURE_DATE)
        val occurrence = OccurrenceRules.findOccurrence(data, occurrenceKey)
            ?: return ActionDecision.Rejected(RejectReason.UNKNOWN_OCCURRENCE)
        return ActionDecision.Apply(occurrence)
    }
}

data class IntakeConsumption(
    /** Negative or zero delta to record as the INTAKE adjustment. */
    val delta: Double,
    /** True if the stock did not cover the full dose quantity. */
    val insufficient: Boolean,
)

object InventoryRules {
    fun stock(adjustments: List<InventoryAdjustment>): Double = adjustments.sumOf { it.delta }.roundQuantity()

    /**
     * The intake itself is always recorded (it is a fact reported by the user). Stock is consumed only down
     * to zero; if it was insufficient the UI asks the user to update the stock count.
     */
    fun consumption(currentStock: Double, quantity: Double): IntakeConsumption {
        val available = currentStock.coerceAtLeast(0.0)
        val consumed = minOf(quantity, available).roundQuantity()
        return IntakeConsumption(delta = -consumed, insufficient = quantity > available)
    }

    fun isLow(stock: Double, threshold: Double?): Boolean = threshold != null && stock <= threshold

    fun crossedLowThreshold(before: Double, after: Double, threshold: Double?): Boolean =
        !isLow(before, threshold) && isLow(after, threshold)

    /** Delta needed to make the stock equal [counted] (a manual stock count). */
    fun correctionDelta(currentStock: Double, counted: Double): Double = (counted - currentStock).roundQuantity()

    /** Rough number of days the stock covers at the current schedule, or null if unknown. */
    fun daysOfSupply(stock: Double, schedule: MedicationSchedule?): Int? {
        if (schedule == null || schedule.doses.isEmpty() || schedule.days.isEmpty()) return null
        val perWeek = schedule.doses.sumOf { it.quantity } * schedule.days.size
        if (perWeek <= 0.0) return null
        return (stock.coerceAtLeast(0.0) / (perWeek / 7.0)).toInt()
    }
}
