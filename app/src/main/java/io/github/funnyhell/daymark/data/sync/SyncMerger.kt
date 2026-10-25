package io.github.funnyhell.daymark.data.sync

import io.github.funnyhell.daymark.data.backup.Snapshot
import io.github.funnyhell.daymark.data.db.DeletedRecordEntity
import io.github.funnyhell.daymark.data.db.HabitEntity
import io.github.funnyhell.daymark.data.db.HabitEventEntity
import io.github.funnyhell.daymark.data.db.HabitScheduleEntity
import io.github.funnyhell.daymark.data.db.InventoryAdjustmentEntity
import io.github.funnyhell.daymark.data.db.MedicationEntity
import io.github.funnyhell.daymark.data.db.MedicationEventEntity
import io.github.funnyhell.daymark.data.db.MedicationScheduleEntity
import io.github.funnyhell.daymark.data.db.OWNER_HABIT
import io.github.funnyhell.daymark.data.db.OWNER_MEDICATION
import io.github.funnyhell.daymark.data.db.PauseEntity
import io.github.funnyhell.daymark.data.db.ScheduledDoseEntity
import io.github.funnyhell.daymark.domain.medication.AdjustmentType
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import java.time.Instant

/**
 * Merges the data of two devices (see docs/DECISIONS.md, ADR-030). The result depends only on the two inputs,
 * not on their order, so both devices end up with the same data after exchanging snapshots, and merging a
 * result again with either input changes nothing.
 *
 * - Deleted habits and medications stay deleted on both devices (a deletion wins over a concurrent edit).
 * - A habit or medication together with its schedule versions, doses and pause intervals is taken from the
 *   device where it was changed last (`updatedAt`; every edit, pause, resume and archive updates it).
 * - Recorded events and stock changes are history: both devices' records are kept. A correction (void) on
 *   either device applies to both.
 * - If both devices recorded a dose of the same occurrence as taken or skipped, the earliest record stays and
 *   the later one is voided; a voided "taken" gets its stock back exactly once.
 */
object SyncMerger {
    fun merge(a: Snapshot, b: Snapshot): Snapshot {
        val deletions = (a.deletions + b.deletions).groupBy { it.id }
            .map { (_, same) -> same.minWith(compareBy<DeletedRecordEntity>({ it.deletedAt }, { it.kind })) }
        val deleted = deletions.map { it.id }.toSet()

        val habits = mutableListOf<HabitEntity>()
        val habitSchedules = mutableListOf<HabitScheduleEntity>()
        val pauses = mutableListOf<PauseEntity>()
        for (id in ((a.habits + b.habits).map { it.id }.toSet() - deleted)) {
            val winner = newer(habitAggregate(a, id), habitAggregate(b, id)) ?: continue
            habits += winner.habit
            habitSchedules += winner.schedules
            pauses += winner.pauses
        }

        val medications = mutableListOf<MedicationEntity>()
        val medicationSchedules = mutableListOf<MedicationScheduleEntity>()
        val doses = mutableListOf<ScheduledDoseEntity>()
        for (id in ((a.medications + b.medications).map { it.id }.toSet() - deleted)) {
            val winner = newer(medicationAggregate(a, id), medicationAggregate(b, id)) ?: continue
            medications += winner.medication
            medicationSchedules += winner.schedules
            doses += winner.doses
            pauses += winner.pauses
        }

        val habitIds = habits.map { it.id }.toSet()
        val medicationIds = medications.map { it.id }.toSet()
        val habitEvents = unionById(a.habitEvents + b.habitEvents, { it.id }) { x, y -> x.copy(voidedAt = earliest(x.voidedAt, y.voidedAt)) }
            .filter { it.habitId in habitIds }
        val unionEvents = unionById(a.medicationEvents + b.medicationEvents, { it.id }) { x, y -> x.copy(voidedAt = earliest(x.voidedAt, y.voidedAt)) }
            .filter { it.medicationId in medicationIds }
        val unionAdjustments = unionById(a.adjustments + b.adjustments, { it.id }) { x, _ -> x }
            .filter { it.medicationId in medicationIds }
        val (medicationEvents, adjustments) = resolveDuplicateDoses(unionEvents, unionAdjustments)

        return Snapshot(
            habits = habits.sortedBy { it.id },
            habitSchedules = habitSchedules.sortedBy { it.id },
            habitEvents = habitEvents.sortedBy { it.id },
            pauses = pauses.sortedBy { it.id },
            medications = medications.sortedBy { it.id },
            medicationSchedules = medicationSchedules.sortedBy { it.id },
            doses = doses.sortedBy { it.id },
            medicationEvents = medicationEvents.sortedBy { it.id },
            adjustments = adjustments.sortedBy { it.id },
            deletions = deletions.sortedBy { it.id },
        )
    }

    /** Number of records added, removed or changed when going from [before] to [after]. */
    fun changes(before: Snapshot, after: Snapshot): Int =
        changed(before.habits, after.habits) { it.id } +
            changed(before.habitSchedules, after.habitSchedules) { it.id } +
            changed(before.habitEvents, after.habitEvents) { it.id } +
            changed(before.pauses, after.pauses) { it.id } +
            changed(before.medications, after.medications) { it.id } +
            changed(before.medicationSchedules, after.medicationSchedules) { it.id } +
            changed(before.doses, after.doses) { it.id } +
            changed(before.medicationEvents, after.medicationEvents) { it.id } +
            changed(before.adjustments, after.adjustments) { it.id }

    private fun <T> changed(before: List<T>, after: List<T>, id: (T) -> String): Int {
        val old = before.associateBy(id)
        val new = after.associateBy(id)
        return (old.keys + new.keys).count { old[it] != new[it] }
    }

    private interface Aggregate {
        val updatedAt: Instant

        /** Deterministic text of the whole aggregate, used only to break a tie between equal timestamps. */
        val key: String
    }

    private class HabitAggregate(
        val habit: HabitEntity,
        val schedules: List<HabitScheduleEntity>,
        val pauses: List<PauseEntity>,
    ) : Aggregate {
        override val updatedAt: Instant get() = habit.updatedAt
        override val key: String get() = "$habit|$schedules|$pauses"
    }

    private class MedicationAggregate(
        val medication: MedicationEntity,
        val schedules: List<MedicationScheduleEntity>,
        val doses: List<ScheduledDoseEntity>,
        val pauses: List<PauseEntity>,
    ) : Aggregate {
        override val updatedAt: Instant get() = medication.updatedAt
        override val key: String get() = "$medication|$schedules|$doses|$pauses"
    }

    private fun habitAggregate(s: Snapshot, id: String): HabitAggregate? {
        val habit = s.habits.firstOrNull { it.id == id } ?: return null
        return HabitAggregate(
            habit,
            s.habitSchedules.filter { it.habitId == id }.sortedBy { it.id },
            s.pauses.filter { it.ownerType == OWNER_HABIT && it.ownerId == id }.sortedBy { it.id },
        )
    }

    private fun medicationAggregate(s: Snapshot, id: String): MedicationAggregate? {
        val medication = s.medications.firstOrNull { it.id == id } ?: return null
        val schedules = s.medicationSchedules.filter { it.medicationId == id }.sortedBy { it.id }
        val scheduleIds = schedules.map { it.id }.toSet()
        return MedicationAggregate(
            medication,
            schedules,
            s.doses.filter { it.scheduleId in scheduleIds }.sortedBy { it.id },
            s.pauses.filter { it.ownerType == OWNER_MEDICATION && it.ownerId == id }.sortedBy { it.id },
        )
    }

    private fun <T : Aggregate> newer(x: T?, y: T?): T? = when {
        x == null -> y
        y == null -> x
        x.updatedAt.isAfter(y.updatedAt) -> x
        y.updatedAt.isAfter(x.updatedAt) -> y
        else -> if (x.key >= y.key) x else y
    }

    private fun <T> unionById(items: List<T>, id: (T) -> String, combine: (T, T) -> T): List<T> =
        items.groupBy(id).map { (_, same) -> same.reduce(combine) }

    private fun earliest(x: Instant?, y: Instant?): Instant? = when {
        x == null -> y
        y == null -> x
        else -> minOf(x, y)
    }

    private val resolutions = setOf(MedicationEventType.TAKEN.name, MedicationEventType.SKIPPED.name)

    private fun resolveDuplicateDoses(
        events: List<MedicationEventEntity>,
        adjustments: List<InventoryAdjustmentEntity>,
    ): Pair<List<MedicationEventEntity>, List<InventoryAdjustmentEntity>> {
        val voided = mutableMapOf<String, MedicationEventEntity>()
        events.filter { it.voidedAt == null && it.occurrenceKey != null && it.type in resolutions }
            .groupBy { it.medicationId to it.occurrenceKey }
            .values
            .filter { it.size > 1 }
            .forEach { same ->
                same.sortedWith(compareBy({ it.recordedAt }, { it.id })).drop(1).forEach { later ->
                    voided[later.id] = later.copy(voidedAt = later.recordedAt)
                }
            }
        val resultEvents = events.map { voided[it.id] ?: it }

        val reversals = voided.values.filter { it.type == MedicationEventType.TAKEN.name }.mapNotNull { event ->
            val intake = adjustments.firstOrNull { it.eventId == event.id && it.type == AdjustmentType.INTAKE.name } ?: return@mapNotNull null
            if (adjustments.any { it.eventId == event.id && it.type == AdjustmentType.INTAKE_REVERSAL.name }) return@mapNotNull null
            InventoryAdjustmentEntity(
                id = "sync-reversal-${event.id}",
                medicationId = event.medicationId,
                delta = -intake.delta,
                type = AdjustmentType.INTAKE_REVERSAL.name,
                note = null,
                eventId = event.id,
                recordedAt = event.recordedAt,
            )
        }
        // Both devices may have reversed the same dose on their own; one record per event and type remains.
        val resultAdjustments = (adjustments + reversals)
            .groupBy { if (it.eventId == null) "id:${it.id}" else "event:${it.eventId}:${it.type}" }
            .map { (_, same) -> same.minBy { it.id } }
        return resultEvents to resultAdjustments
    }
}
