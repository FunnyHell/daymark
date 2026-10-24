package io.github.funnyhell.daymark.domain

import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.medication.ActionDecision
import io.github.funnyhell.daymark.domain.medication.ActionRules
import io.github.funnyhell.daymark.domain.medication.AdjustmentType
import io.github.funnyhell.daymark.domain.medication.InventoryAdjustment
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.Occurrence
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import io.github.funnyhell.daymark.domain.medication.RejectReason
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationRulesTest {

    private val key8 = Occurrence.occurrenceKey("d1", MONDAY)

    @Test
    fun generatesMultipleDailyOccurrencesWithStableKeys() {
        val occ = OccurrenceRules.occurrencesOn(medData(), MONDAY)
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), occ.map { it.time })
        assertEquals("d1@2024-03-04", occ.first().key)
        assertEquals(occ, OccurrenceRules.occurrencesOn(medData(), MONDAY))
    }

    @Test
    fun weekdaySchedulesSkipOtherDays() {
        val data = medData(schedules = listOf(medSchedule(days = setOf(DayOfWeek.MONDAY))))
        assertEquals(2, OccurrenceRules.occurrencesOn(data, MONDAY).size)
        assertTrue(OccurrenceRules.occurrencesOn(data, MONDAY.plusDays(1)).isEmpty())
    }

    @Test
    fun pausedDaysProduceNoOccurrencesAndNoMissedDoses() {
        val data = medData(pauses = listOf(pause(MONDAY.plusDays(1), MONDAY.plusDays(3))))
        assertTrue(OccurrenceRules.occurrencesOn(data, MONDAY.plusDays(1)).isEmpty())
        assertTrue(OccurrenceRules.occurrencesOn(data, MONDAY.plusDays(2)).isEmpty())
        assertEquals(2, OccurrenceRules.occurrencesOn(data, MONDAY.plusDays(3)).size)
    }

    @Test
    fun stateTransitions_upcomingDueMissed() {
        val occ = OccurrenceRules.occurrencesOn(medData(), MONDAY).first()
        assertEquals(OccurrenceState.UPCOMING, OccurrenceRules.status(occ, emptyList(), at(MONDAY, 7), UTC).state)
        assertEquals(OccurrenceState.DUE, OccurrenceRules.status(occ, emptyList(), at(MONDAY, 8, 30), UTC).state)
        assertEquals(OccurrenceState.DUE, OccurrenceRules.status(occ, emptyList(), at(MONDAY, 10, 59), UTC).state)
        assertEquals(OccurrenceState.MISSED, OccurrenceRules.status(occ, emptyList(), at(MONDAY, 11), UTC).state)
    }

    @Test
    fun takenAndSkippedAreDistinctAndStable() {
        val occ = OccurrenceRules.occurrencesOn(medData(), MONDAY).first()
        val taken = OccurrenceRules.status(occ, listOf(medEvent(key8, MedicationEventType.TAKEN)), at(MONDAY, 23), UTC)
        assertEquals(OccurrenceState.TAKEN, taken.state)
        val skipped = OccurrenceRules.status(occ, listOf(medEvent(key8, MedicationEventType.SKIPPED)), at(MONDAY, 23), UTC)
        assertEquals(OccurrenceState.SKIPPED, skipped.state)
    }

    @Test
    fun snoozeDefersWithoutRecordingIntake() {
        val occ = OccurrenceRules.occurrencesOn(medData(), MONDAY).first()
        val snooze = medEvent(key8, MedicationEventType.SNOOZED, recordedAt = at(MONDAY, 8, 5), snoozedUntil = at(MONDAY, 8, 35))
        val during = OccurrenceRules.status(occ, listOf(snooze), at(MONDAY, 8, 20), UTC)
        assertEquals(OccurrenceState.SNOOZED, during.state)
        assertNull(during.resolution)
        assertEquals(at(MONDAY, 8, 35), during.snoozedUntil)
        assertEquals(OccurrenceState.DUE, OccurrenceRules.status(occ, listOf(snooze), at(MONDAY, 8, 40), UTC).state)
    }

    @Test
    fun snoozeExtendsMissedWindow() {
        val occ = OccurrenceRules.occurrencesOn(medData(), MONDAY).first()
        val snooze = medEvent(key8, MedicationEventType.SNOOZED, recordedAt = at(MONDAY, 10, 50), snoozedUntil = at(MONDAY, 11, 20))
        assertEquals(OccurrenceState.DUE, OccurrenceRules.status(occ, listOf(snooze), at(MONDAY, 12), UTC).state)
        assertEquals(OccurrenceState.MISSED, OccurrenceRules.status(occ, listOf(snooze), at(MONDAY, 14, 21), UTC).state)
    }

    @Test
    fun correctedTakenReopensOccurrence() {
        val occ = OccurrenceRules.occurrencesOn(medData(), MONDAY).first()
        val voided = medEvent(key8, MedicationEventType.TAKEN, voided = true)
        assertEquals(OccurrenceState.DUE, OccurrenceRules.status(occ, listOf(voided), at(MONDAY, 9), UTC).state)
    }

    @Test
    fun actionRules_duplicateIsAlreadyResolved() {
        val existing = medEvent(key8, MedicationEventType.TAKEN)
        val decision = ActionRules.decide(medData(events = listOf(existing)), key8, MONDAY)
        assertTrue(decision is ActionDecision.AlreadyResolved)
        assertEquals(existing, (decision as ActionDecision.AlreadyResolved).existing)
    }

    @Test
    fun actionRules_rejectsFutureAndUnknownOccurrences() {
        val tomorrowKey = Occurrence.occurrenceKey("d1", MONDAY.plusDays(1))
        assertEquals(ActionDecision.Rejected(RejectReason.FUTURE_DATE), ActionRules.decide(medData(), tomorrowKey, MONDAY))
        assertEquals(
            ActionDecision.Rejected(RejectReason.UNKNOWN_OCCURRENCE),
            ActionRules.decide(medData(), Occurrence.occurrenceKey("gone", MONDAY), MONDAY),
        )
        assertEquals(ActionDecision.Rejected(RejectReason.UNKNOWN_OCCURRENCE), ActionRules.decide(medData(), "garbage", MONDAY))
    }

    @Test
    fun actionRules_staleNotificationAfterScheduleEditIsRejected() {
        val old = medSchedule(id = "s1", until = MONDAY.plusDays(1))
        val new = medSchedule(id = "s2", from = MONDAY.plusDays(1), times = listOf("d9" to LocalTime.of(9, 0)))
        val data = medData(schedules = listOf(old, new))
        // Old dose on the new schedule's date is stale...
        assertEquals(
            ActionDecision.Rejected(RejectReason.UNKNOWN_OCCURRENCE),
            ActionRules.decide(data, Occurrence.occurrenceKey("d1", MONDAY.plusDays(1)), MONDAY.plusDays(1)),
        )
        // ...but a past occurrence of the old version can still be recorded.
        assertTrue(ActionRules.decide(data, key8, MONDAY.plusDays(1)) is ActionDecision.Apply)
    }

    @Test
    fun actionRules_pausedOrArchivedDaysAreRejected() {
        val data = medData(pauses = listOf(pause(MONDAY, null)), medication = medication(status = LifecycleStatus.PAUSED))
        assertEquals(ActionDecision.Rejected(RejectReason.UNKNOWN_OCCURRENCE), ActionRules.decide(data, key8, MONDAY))
    }

    @Test
    fun scheduleEdit_takesEffectTomorrowWhenTodayHasRecords() {
        assertEquals(MONDAY, OccurrenceRules.editEffectiveDate(medData(), MONDAY))
        val withEvent = medData(events = listOf(medEvent(key8, MedicationEventType.TAKEN)))
        assertEquals(MONDAY.plusDays(1), OccurrenceRules.editEffectiveDate(withEvent, MONDAY))
        val withVoided = medData(events = listOf(medEvent(key8, MedicationEventType.TAKEN, voided = true)))
        assertEquals(MONDAY, OccurrenceRules.editEffectiveDate(withVoided, MONDAY))
    }

    @Test
    fun floatingTimeFollowsDeviceZone() {
        val occ = OccurrenceRules.occurrencesOn(medData(), MONDAY).first()
        val berlin = occ.dueAt(ZoneId.of("Europe/Berlin"))
        val tokyo = occ.dueAt(ZoneId.of("Asia/Tokyo"))
        assertEquals(java.time.Instant.parse("2024-03-04T07:00:00Z"), berlin)
        assertEquals(java.time.Instant.parse("2024-03-03T23:00:00Z"), tokyo)
    }

    @Test
    fun dstGapTimeShiftsForward() {
        // 2024-03-31 02:30 does not exist in Berlin; java.time moves it to 03:30 local.
        val date = java.time.LocalDate.of(2024, 3, 31)
        val data = medData(schedules = listOf(medSchedule(from = date, times = listOf("d1" to LocalTime.of(2, 30)))))
        val due = OccurrenceRules.occurrencesOn(data, date).single().dueAt(ZoneId.of("Europe/Berlin"))
        assertEquals(java.time.Instant.parse("2024-03-31T01:30:00Z"), due)
    }

    // ---- Inventory ----

    private fun adj(delta: Double, type: AdjustmentType = AdjustmentType.MANUAL_CORRECTION) =
        InventoryAdjustment(newId("a"), "m1", delta, type, null, null, EPOCH)

    @Test
    fun stockIsSumOfAdjustments() {
        val stock = InventoryRules.stock(listOf(adj(30.0, AdjustmentType.INITIAL), adj(-1.0, AdjustmentType.INTAKE), adj(28.0, AdjustmentType.PACKAGE_ADDED)))
        assertEquals(57.0, stock, 0.0)
    }

    @Test
    fun fractionalQuantitiesDoNotDrift() {
        val stock = InventoryRules.stock(List(10) { adj(-0.1) } + adj(1.0))
        assertEquals(0.0, stock, 0.0)
    }

    @Test
    fun consumptionIsClampedWhenStockInsufficient() {
        val ok = InventoryRules.consumption(10.0, 2.0)
        assertEquals(-2.0, ok.delta, 0.0)
        assertFalse(ok.insufficient)
        val short = InventoryRules.consumption(0.5, 2.0)
        assertEquals(-0.5, short.delta, 0.0)
        assertTrue(short.insufficient)
        val empty = InventoryRules.consumption(0.0, 1.0)
        assertEquals(0.0, -empty.delta, 0.0)
        assertTrue(empty.insufficient)
    }

    @Test
    fun lowStockThreshold() {
        assertFalse(InventoryRules.isLow(5.0, null))
        assertTrue(InventoryRules.isLow(5.0, 5.0))
        assertFalse(InventoryRules.isLow(5.5, 5.0))
        assertTrue(InventoryRules.crossedLowThreshold(6.0, 5.0, 5.0))
        assertFalse(InventoryRules.crossedLowThreshold(5.0, 4.0, 5.0))
    }

    @Test
    fun correctionDeltaSetsCountedStock() {
        assertEquals(-3.0, InventoryRules.correctionDelta(10.0, 7.0), 0.0)
        assertEquals(4.5, InventoryRules.correctionDelta(0.5, 5.0), 0.0)
    }

    @Test
    fun daysOfSupply() {
        assertEquals(10, InventoryRules.daysOfSupply(20.0, medSchedule()))
        assertNull(InventoryRules.daysOfSupply(20.0, null))
    }
}
