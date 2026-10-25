package io.github.funnyhell.daymark.data.sync

import io.github.funnyhell.daymark.data.backup.Snapshot
import io.github.funnyhell.daymark.data.db.DELETED_HABIT
import io.github.funnyhell.daymark.data.db.DeletedRecordEntity
import io.github.funnyhell.daymark.data.db.HabitEntity
import io.github.funnyhell.daymark.data.db.HabitEventEntity
import io.github.funnyhell.daymark.data.db.HabitScheduleEntity
import io.github.funnyhell.daymark.data.db.InventoryAdjustmentEntity
import io.github.funnyhell.daymark.data.db.KIND_DAILY
import io.github.funnyhell.daymark.data.db.MedicationEntity
import io.github.funnyhell.daymark.data.db.MedicationEventEntity
import io.github.funnyhell.daymark.data.db.OWNER_HABIT
import io.github.funnyhell.daymark.data.db.PauseEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergerTest {
    private val day = LocalDate.of(2024, 3, 4)
    private fun at(seconds: Long): Instant = Instant.ofEpochSecond(1_709_539_200 + seconds)

    private fun habit(id: String, updated: Long, name: String = "Read") =
        HabitEntity(id, name, null, 0, "CHECK", "ACTIVE", null, day, null, true, 0, at(0), at(updated))

    private fun schedule(id: String, habitId: String, target: Double = 1.0) =
        HabitScheduleEntity(id, habitId, day, null, KIND_DAILY, 0, 0, target)

    private fun habitEvent(id: String, habitId: String, voided: Long? = null) =
        HabitEventEntity(id, habitId, day, "COMPLETION", 1.0, null, at(100), voided?.let(::at))

    private fun medication(id: String, updated: Long = 0) =
        MedicationEntity(id, "Vitamin D", null, null, 0, "ACTIVE", true, "tablets", null, true, at(0), at(updated))

    private fun dose(id: String, medId: String, recorded: Long, type: String = "TAKEN") =
        MedicationEventEntity(id, medId, "$medId@2024-03-04T08:00", "d1", day, LocalTime.of(8, 0), "1 tablet", type, 1.0, null, "APP", at(recorded), null)

    private fun adjustment(id: String, medId: String, delta: Double, type: String, eventId: String? = null) =
        InventoryAdjustmentEntity(id, medId, delta, type, null, eventId, at(0))

    private fun snapshot(
        habits: List<HabitEntity> = emptyList(),
        schedules: List<HabitScheduleEntity> = emptyList(),
        habitEvents: List<HabitEventEntity> = emptyList(),
        pauses: List<PauseEntity> = emptyList(),
        medications: List<MedicationEntity> = emptyList(),
        medicationEvents: List<MedicationEventEntity> = emptyList(),
        adjustments: List<InventoryAdjustmentEntity> = emptyList(),
        deletions: List<DeletedRecordEntity> = emptyList(),
    ) = Snapshot(habits, schedules, habitEvents, pauses, medications, emptyList(), emptyList(), medicationEvents, adjustments, deletions)

    /** Both devices compute the same result, and repeating the sync changes nothing. */
    private fun assertConverges(a: Snapshot, b: Snapshot): Snapshot {
        val ab = SyncMerger.merge(a, b)
        assertEquals(ab, SyncMerger.merge(b, a))
        assertEquals(ab, SyncMerger.merge(ab, a))
        assertEquals(ab, SyncMerger.merge(ab, b))
        assertEquals(0, SyncMerger.changes(ab, SyncMerger.merge(ab, b)))
        return ab
    }

    @Test
    fun independentRecords_areCombined() {
        val a = snapshot(habits = listOf(habit("h1", 0)), schedules = listOf(schedule("s1", "h1")), habitEvents = listOf(habitEvent("e1", "h1")))
        val b = snapshot(habits = listOf(habit("h2", 0)), schedules = listOf(schedule("s2", "h2")), habitEvents = listOf(habitEvent("e2", "h2")))
        val merged = assertConverges(a, b)
        assertEquals(listOf("h1", "h2"), merged.habits.map { it.id })
        assertEquals(listOf("e1", "e2"), merged.habitEvents.map { it.id })
        assertEquals(3, SyncMerger.changes(a, merged))
    }

    @Test
    fun lastEdit_winsForTheHabitAndItsSchedule_butHistoryFromBothIsKept() {
        val a = snapshot(
            habits = listOf(habit("h1", updated = 10, name = "Read")),
            schedules = listOf(schedule("s1", "h1", target = 1.0)),
            habitEvents = listOf(habitEvent("e1", "h1")),
            pauses = listOf(PauseEntity("p1", OWNER_HABIT, "h1", day, null, at(10))),
        )
        val b = snapshot(
            habits = listOf(habit("h1", updated = 20, name = "Read 20 pages")),
            schedules = listOf(schedule("s1", "h1", target = 1.0), schedule("s2", "h1", target = 2.0)),
            habitEvents = listOf(habitEvent("e2", "h1")),
        )
        val merged = assertConverges(a, b)
        assertEquals("Read 20 pages", merged.habits.single().name)
        assertEquals(listOf("s1", "s2"), merged.habitSchedules.map { it.id })
        assertTrue(merged.pauses.isEmpty())
        assertEquals(listOf("e1", "e2"), merged.habitEvents.map { it.id })
    }

    @Test
    fun deletion_winsOverEditOnTheOtherDevice() {
        val a = snapshot(deletions = listOf(DeletedRecordEntity("h1", DELETED_HABIT, at(50))))
        val b = snapshot(
            habits = listOf(habit("h1", updated = 90)),
            schedules = listOf(schedule("s1", "h1")),
            habitEvents = listOf(habitEvent("e1", "h1")),
        )
        val merged = assertConverges(a, b)
        assertTrue(merged.habits.isEmpty())
        assertTrue(merged.habitSchedules.isEmpty())
        assertTrue(merged.habitEvents.isEmpty())
        assertEquals(listOf("h1"), merged.deletions.map { it.id })
    }

    @Test
    fun correction_onOneDevice_appliesToBoth() {
        val a = snapshot(habits = listOf(habit("h1", 0)), habitEvents = listOf(habitEvent("e1", "h1", voided = 300)))
        val b = snapshot(habits = listOf(habit("h1", 0)), habitEvents = listOf(habitEvent("e1", "h1")))
        val merged = assertConverges(a, b)
        assertEquals(at(300), merged.habitEvents.single().voidedAt)
    }

    @Test
    fun doseTakenOnBothDevices_keepsTheFirst_andReturnsStockOnce() {
        val initial = adjustment("init", "m1", 10.0, "INITIAL")
        val a = snapshot(
            medications = listOf(medication("m1")),
            medicationEvents = listOf(dose("ta", "m1", recorded = 500)),
            adjustments = listOf(initial, adjustment("ia", "m1", -1.0, "INTAKE", "ta")),
        )
        val b = snapshot(
            medications = listOf(medication("m1")),
            medicationEvents = listOf(dose("tb", "m1", recorded = 800)),
            adjustments = listOf(initial, adjustment("ib", "m1", -1.0, "INTAKE", "tb")),
        )
        val merged = assertConverges(a, b)
        val events = merged.medicationEvents.associateBy { it.id }
        assertNull(events.getValue("ta").voidedAt)
        assertNotNull(events.getValue("tb").voidedAt)
        val reversal = merged.adjustments.single { it.type == "INTAKE_REVERSAL" }
        assertEquals("tb", reversal.eventId)
        assertEquals(9.0, merged.adjustments.sumOf { it.delta }, 0.0)
    }

    @Test
    fun takenAndSkipped_forTheSameDose_keepTheEarlierOne() {
        val a = snapshot(medications = listOf(medication("m1")), medicationEvents = listOf(dose("skip", "m1", recorded = 400, type = "SKIPPED")))
        val b = snapshot(
            medications = listOf(medication("m1")),
            medicationEvents = listOf(dose("take", "m1", recorded = 700)),
            adjustments = listOf(adjustment("i", "m1", -1.0, "INTAKE", "take")),
        )
        val merged = assertConverges(a, b)
        assertEquals(listOf("skip"), merged.medicationEvents.filter { it.voidedAt == null }.map { it.id })
        assertEquals(0.0, merged.adjustments.sumOf { it.delta }, 0.0)
    }

    @Test
    fun reversalsMadeOnBothDevices_forTheSameDose_countOnce() {
        val voided = dose("t", "m1", recorded = 500).copy(voidedAt = at(600))
        val a = snapshot(
            medications = listOf(medication("m1")),
            medicationEvents = listOf(voided),
            adjustments = listOf(adjustment("i", "m1", -1.0, "INTAKE", "t"), adjustment("r-a", "m1", 1.0, "INTAKE_REVERSAL", "t")),
        )
        val b = a.copy(adjustments = listOf(adjustment("i", "m1", -1.0, "INTAKE", "t"), adjustment("r-b", "m1", 1.0, "INTAKE_REVERSAL", "t")))
        val merged = assertConverges(a, b)
        assertEquals(listOf("i", "r-a"), merged.adjustments.map { it.id })
    }
}
