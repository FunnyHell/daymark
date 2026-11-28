package io.github.funnyhell.daymark.data.db

import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.PauseInterval
import io.github.funnyhell.daymark.domain.habit.Habit
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvent
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitSchedule
import io.github.funnyhell.daymark.domain.habit.HabitStep
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.AdjustmentType
import io.github.funnyhell.daymark.domain.medication.InventoryAdjustment
import io.github.funnyhell.daymark.domain.medication.Medication
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.MedicationEvent
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.MedicationSchedule
import io.github.funnyhell.daymark.domain.medication.ScheduledDose
import java.time.DayOfWeek

const val OWNER_HABIT = "HABIT"
const val OWNER_MEDICATION = "MEDICATION"

object Weekdays {
    fun toMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
    fun fromMask(mask: Int): Set<DayOfWeek> = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
    val ALL: Int = toMask(DayOfWeek.entries.toSet())
}

fun HabitEntity.toDomain() = Habit(
    id = id, name = name, description = description, colorArgb = colorArgb, type = HabitType.valueOf(type),
    status = LifecycleStatus.valueOf(status), unit = unit, startDate = startDate, reminderTime = reminderTime,
    showOnWidget = showOnWidget, sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt,
)

fun Habit.toEntity() = HabitEntity(
    id = id, name = name, description = description, colorArgb = colorArgb, type = type.name, status = status.name,
    unit = unit, startDate = startDate, reminderTime = reminderTime, showOnWidget = showOnWidget,
    sortOrder = sortOrder, createdAt = createdAt, updatedAt = updatedAt,
)

fun HabitScheduleEntity.toDomain() = HabitSchedule(
    id = id, habitId = habitId, effectiveFrom = effectiveFrom, effectiveUntil = effectiveUntil,
    recurrence = when (recurrenceKind) {
        KIND_WEEKDAYS -> Recurrence.Weekdays(Weekdays.fromMask(weekdayMask))
        KIND_EVERY_N_DAYS -> Recurrence.EveryNDays(intervalDays)
        else -> Recurrence.Daily
    },
    target = target,
)

fun HabitSchedule.toEntity() = HabitScheduleEntity(
    id = id, habitId = habitId, effectiveFrom = effectiveFrom, effectiveUntil = effectiveUntil,
    recurrenceKind = when (recurrence) {
        Recurrence.Daily -> KIND_DAILY
        is Recurrence.Weekdays -> KIND_WEEKDAYS
        is Recurrence.EveryNDays -> KIND_EVERY_N_DAYS
    },
    weekdayMask = (recurrence as? Recurrence.Weekdays)?.let { Weekdays.toMask(it.days) } ?: 0,
    intervalDays = (recurrence as? Recurrence.EveryNDays)?.interval ?: 0,
    target = target,
)

const val KIND_DAILY = "DAILY"
const val KIND_WEEKDAYS = "WEEKDAYS"
const val KIND_EVERY_N_DAYS = "EVERY_N_DAYS"

fun HabitEventEntity.toDomain() = HabitEvent(
    id = id, habitId = habitId, date = date, type = HabitEventType.valueOf(type), amount = amount, note = note,
    recordedAt = recordedAt, voidedAt = voidedAt, stepId = stepId,
)

fun HabitStepEntity.toDomain() = HabitStep(id, habitId, title, target, sortOrder, addedOn, removedOn)

fun HabitStep.toEntity() = HabitStepEntity(id, habitId, title, target, sortOrder, addedOn, removedOn)

fun HabitEvent.toEntity() = HabitEventEntity(
    id = id, habitId = habitId, date = date, type = type.name, amount = amount, note = note,
    recordedAt = recordedAt, voidedAt = voidedAt, stepId = stepId,
)

fun PauseEntity.toDomain() = PauseInterval(id = id, ownerId = ownerId, start = start, end = end)

fun MedicationEntity.toDomain() = Medication(
    id = id, name = name, strength = strength, notes = notes, colorArgb = colorArgb,
    status = LifecycleStatus.valueOf(status), inventoryEnabled = inventoryEnabled, inventoryUnit = inventoryUnit,
    lowStockThreshold = lowStockThreshold, lowStockAlerts = lowStockAlerts, createdAt = createdAt, updatedAt = updatedAt,
)

fun Medication.toEntity() = MedicationEntity(
    id = id, name = name, strength = strength, notes = notes, colorArgb = colorArgb, status = status.name,
    inventoryEnabled = inventoryEnabled, inventoryUnit = inventoryUnit, lowStockThreshold = lowStockThreshold,
    lowStockAlerts = lowStockAlerts, createdAt = createdAt, updatedAt = updatedAt,
)

fun ScheduledDoseEntity.toDomain() = ScheduledDose(id = id, scheduleId = scheduleId, time = time, doseLabel = doseLabel, quantity = quantity)

fun ScheduledDose.toEntity() = ScheduledDoseEntity(id = id, scheduleId = scheduleId, time = time, doseLabel = doseLabel, quantity = quantity)

fun MedicationScheduleEntity.toDomain(doses: List<ScheduledDoseEntity>) = MedicationSchedule(
    id = id, medicationId = medicationId, effectiveFrom = effectiveFrom, effectiveUntil = effectiveUntil,
    days = Weekdays.fromMask(weekdayMask), doses = doses.filter { it.scheduleId == id }.map { it.toDomain() }.sortedBy { it.time },
)

fun MedicationEventEntity.toDomain() = MedicationEvent(
    id = id, medicationId = medicationId, occurrenceKey = occurrenceKey, doseId = doseId, scheduledDate = scheduledDate,
    scheduledTime = scheduledTime, doseLabel = doseLabel, type = MedicationEventType.valueOf(type), quantity = quantity,
    snoozedUntil = snoozedUntil, source = ActionSource.valueOf(source), recordedAt = recordedAt, voidedAt = voidedAt, note = note,
)

fun MedicationEvent.toEntity() = MedicationEventEntity(
    id = id, medicationId = medicationId, occurrenceKey = occurrenceKey, doseId = doseId, scheduledDate = scheduledDate,
    scheduledTime = scheduledTime, doseLabel = doseLabel, type = type.name, quantity = quantity,
    snoozedUntil = snoozedUntil, source = source.name, recordedAt = recordedAt, voidedAt = voidedAt, note = note,
)

fun InventoryAdjustmentEntity.toDomain() = InventoryAdjustment(
    id = id, medicationId = medicationId, delta = delta, type = AdjustmentType.valueOf(type), note = note,
    eventId = eventId, recordedAt = recordedAt,
)

fun InventoryAdjustment.toEntity() = InventoryAdjustmentEntity(
    id = id, medicationId = medicationId, delta = delta, type = type.name, note = note, eventId = eventId,
    recordedAt = recordedAt,
)

fun assembleHabits(
    habits: List<HabitEntity>,
    schedules: List<HabitScheduleEntity>,
    pauses: List<PauseEntity>,
    events: List<HabitEventEntity>,
    steps: List<HabitStepEntity> = emptyList(),
): List<HabitData> {
    val st = steps.groupBy { it.habitId }
    val s = schedules.groupBy { it.habitId }
    val p = pauses.filter { it.ownerType == OWNER_HABIT }.groupBy { it.ownerId }
    val e = events.groupBy { it.habitId }
    return habits.map { h ->
        HabitData(
            habit = h.toDomain(),
            schedules = s[h.id].orEmpty().map { it.toDomain() },
            pauses = p[h.id].orEmpty().map { it.toDomain() },
            events = e[h.id].orEmpty().map { it.toDomain() },
            steps = st[h.id].orEmpty().map { it.toDomain() },
        )
    }
}

fun assembleMedications(
    medications: List<MedicationEntity>,
    schedules: List<MedicationScheduleEntity>,
    doses: List<ScheduledDoseEntity>,
    pauses: List<PauseEntity>,
    events: List<MedicationEventEntity>,
    adjustments: List<InventoryAdjustmentEntity>,
): List<MedicationData> {
    val s = schedules.groupBy { it.medicationId }
    val d = doses.groupBy { it.scheduleId }
    val p = pauses.filter { it.ownerType == OWNER_MEDICATION }.groupBy { it.ownerId }
    val e = events.groupBy { it.medicationId }
    val a = adjustments.groupBy { it.medicationId }
    return medications.map { m ->
        MedicationData(
            medication = m.toDomain(),
            schedules = s[m.id].orEmpty().map { it.toDomain(d[it.id].orEmpty()) },
            pauses = p[m.id].orEmpty().map { it.toDomain() },
            events = e[m.id].orEmpty().map { it.toDomain() },
            adjustments = a[m.id].orEmpty().map { it.toDomain() },
        )
    }
}
