package io.github.funnyhell.daymark.domain

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
import io.github.funnyhell.daymark.domain.medication.Medication
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.MedicationEvent
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.MedicationSchedule
import io.github.funnyhell.daymark.domain.medication.ScheduledDose
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private val counter = AtomicInteger()
fun newId(prefix: String) = "$prefix-${counter.incrementAndGet()}"

val EPOCH: Instant = Instant.parse("2024-01-01T00:00:00Z")
val UTC: ZoneOffset = ZoneOffset.UTC

/** 2024-03-04 is a Monday. */
val MONDAY: LocalDate = LocalDate.of(2024, 3, 4)

fun habit(
    type: HabitType = HabitType.CHECK,
    start: LocalDate = MONDAY,
    status: LifecycleStatus = LifecycleStatus.ACTIVE,
    reminder: LocalTime? = null,
) = Habit(
    id = "h1", name = "Habit", description = null, colorArgb = 0, type = type, status = status, unit = null,
    startDate = start, reminderTime = reminder, showOnWidget = true, sortOrder = 0, createdAt = EPOCH, updatedAt = EPOCH,
)

fun schedule(
    from: LocalDate = MONDAY,
    until: LocalDate? = null,
    recurrence: Recurrence = Recurrence.Daily,
    target: Double = 1.0,
) = HabitSchedule(newId("hs"), "h1", from, until, recurrence, target)

fun event(date: LocalDate, type: HabitEventType = HabitEventType.COMPLETION, amount: Double = 1.0, voided: Boolean = false, stepId: String? = null) =
    HabitEvent(newId("he"), "h1", date, type, amount, null, EPOCH, if (voided) EPOCH else null, stepId)

fun step(id: String, target: Int = 1, order: Int = 0, from: LocalDate = MONDAY, until: LocalDate? = null) =
    HabitStep(id, "h1", id, target, order, from, until)

fun pause(start: LocalDate, end: LocalDate?) = PauseInterval(newId("p"), "h1", start, end)

fun habitData(
    habit: Habit = habit(),
    schedules: List<HabitSchedule> = listOf(schedule(from = habit.startDate)),
    pauses: List<PauseInterval> = emptyList(),
    events: List<HabitEvent> = emptyList(),
    steps: List<HabitStep> = emptyList(),
) = HabitData(habit, schedules, pauses, events, steps)

val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()

fun medication(status: LifecycleStatus = LifecycleStatus.ACTIVE, threshold: Double? = null) = Medication(
    id = "m1", name = "Med", strength = null, notes = null, colorArgb = 0, status = status, inventoryEnabled = true,
    inventoryUnit = "tablets", lowStockThreshold = threshold, lowStockAlerts = true, createdAt = EPOCH, updatedAt = EPOCH,
)

fun medSchedule(
    id: String = "s1",
    from: LocalDate = MONDAY,
    until: LocalDate? = null,
    days: Set<DayOfWeek> = ALL_DAYS,
    times: List<Pair<String, LocalTime>> = listOf("d1" to LocalTime.of(8, 0), "d2" to LocalTime.of(20, 0)),
    quantity: Double = 1.0,
) = MedicationSchedule(id, "m1", from, until, days, times.map { (doseId, t) -> ScheduledDose(doseId, id, t, "1 tablet", quantity) })

fun medEvent(
    key: String,
    type: MedicationEventType,
    recordedAt: Instant = EPOCH,
    snoozedUntil: Instant? = null,
    voided: Boolean = false,
) = MedicationEvent(
    id = newId("me"), medicationId = "m1", occurrenceKey = key, doseId = key.substringBefore('@'),
    scheduledDate = LocalDate.parse(key.substringAfter('@')), scheduledTime = null, doseLabel = null, type = type,
    quantity = 1.0, snoozedUntil = snoozedUntil, source = ActionSource.APP, recordedAt = recordedAt,
    voidedAt = if (voided) recordedAt else null,
)

fun medData(
    schedules: List<MedicationSchedule> = listOf(medSchedule()),
    pauses: List<PauseInterval> = emptyList(),
    events: List<MedicationEvent> = emptyList(),
    medication: Medication = medication(),
) = MedicationData(medication, schedules, pauses, events, emptyList())

fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).toInstant(UTC)
