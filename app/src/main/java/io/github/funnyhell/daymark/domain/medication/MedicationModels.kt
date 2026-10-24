package io.github.funnyhell.daymark.domain.medication

import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.PauseInterval
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class Medication(
    val id: String,
    val name: String,
    /** Free text such as strength or formulation, entered by the user. */
    val strength: String?,
    val notes: String?,
    val colorArgb: Long,
    val status: LifecycleStatus,
    val inventoryEnabled: Boolean,
    /** User-defined stock unit, for example "tablets" or "ml". */
    val inventoryUnit: String,
    val lowStockThreshold: Double?,
    val lowStockAlerts: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * A version of a medication schedule, valid from [effectiveFrom] (inclusive) to [effectiveUntil] (exclusive).
 * [days] lists the weekdays with doses; all seven days means every day.
 */
data class MedicationSchedule(
    val id: String,
    val medicationId: String,
    val effectiveFrom: LocalDate,
    val effectiveUntil: LocalDate?,
    val days: Set<DayOfWeek>,
    val doses: List<ScheduledDose>,
) {
    fun isEffectiveOn(date: LocalDate): Boolean =
        !date.isBefore(effectiveFrom) && (effectiveUntil == null || date.isBefore(effectiveUntil))
}

/** One configured dose time. [quantity] is what one intake consumes from inventory, in the inventory unit. */
data class ScheduledDose(
    val id: String,
    val scheduleId: String,
    val time: LocalTime,
    val doseLabel: String,
    val quantity: Double,
)

/**
 * A concrete scheduled dose on a date. Occurrences are generated on demand from schedule versions;
 * [key] is stable for the same dose and date and is what notifications, widgets and events refer to.
 */
data class Occurrence(
    val medicationId: String,
    val scheduleId: String,
    val doseId: String,
    val date: LocalDate,
    val time: LocalTime,
    val doseLabel: String,
    val quantity: Double,
) {
    val key: String get() = occurrenceKey(doseId, date)

    /** Floating local time: the dose is due at [time] in whatever zone the device is in on [date]. */
    fun dueAt(zone: ZoneId): Instant = LocalDateTime.of(date, time).atZone(zone).toInstant()

    companion object {
        fun occurrenceKey(doseId: String, date: LocalDate): String = "$doseId@$date"
    }
}

enum class MedicationEventType { TAKEN, SKIPPED, SNOOZED }

enum class ActionSource { APP, NOTIFICATION, WIDGET, IMPORT }

/**
 * A recorded medication action. TAKEN and SKIPPED resolve an occurrence; SNOOZED only defers its reminder.
 * A correction sets [voidedAt] and keeps the event in history.
 */
data class MedicationEvent(
    val id: String,
    val medicationId: String,
    val occurrenceKey: String?,
    val doseId: String?,
    val scheduledDate: LocalDate?,
    val scheduledTime: LocalTime?,
    val doseLabel: String?,
    val type: MedicationEventType,
    val quantity: Double,
    val snoozedUntil: Instant?,
    val source: ActionSource,
    val recordedAt: Instant,
    val voidedAt: Instant?,
) {
    val isActive: Boolean get() = voidedAt == null
    val isResolution: Boolean get() = type == MedicationEventType.TAKEN || type == MedicationEventType.SKIPPED
}

enum class AdjustmentType {
    /** First stock entry when inventory tracking is set up. */
    INITIAL,

    /** A package with a user-defined quantity was added. */
    PACKAGE_ADDED,

    /** User corrected the counted stock. */
    MANUAL_CORRECTION,

    /** Consumption caused by a TAKEN event. */
    INTAKE,

    /** Reverses the INTAKE adjustment of a corrected TAKEN event. */
    INTAKE_REVERSAL,
}

/** Stock is always the sum of adjustments; there is no separately stored total that could diverge. */
data class InventoryAdjustment(
    val id: String,
    val medicationId: String,
    val delta: Double,
    val type: AdjustmentType,
    val note: String?,
    val eventId: String?,
    val recordedAt: Instant,
)

data class MedicationData(
    val medication: Medication,
    val schedules: List<MedicationSchedule>,
    val pauses: List<PauseInterval>,
    val events: List<MedicationEvent>,
    val adjustments: List<InventoryAdjustment>,
)
