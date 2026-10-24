package io.github.funnyhell.daymark.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.repo.DataChange
import io.github.funnyhell.daymark.data.repo.DataChangeListener
import io.github.funnyhell.daymark.data.repo.MedicationActionResult
import io.github.funnyhell.daymark.data.repo.MedicationRepository
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.AdjustmentType
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import io.github.funnyhell.daymark.domain.medication.RejectReason
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class MedicationRepositoryTest {
    private lateinit var db: DaymarkDatabase
    private lateinit var repo: MedicationRepository
    private val time = FakeTime(DAY1.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC))
    private val changes = mutableListOf<DataChange>()

    @Before
    fun setUp() {
        db = inMemoryDb()
        repo = MedicationRepository(db, time, listener = DataChangeListener { synchronized(changes) { changes += it } })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun stock(id: String) = InventoryRules.stock(repo.get(id)!!.adjustments)

    private suspend fun todaysKey(id: String, index: Int = 0): String =
        OccurrenceRules.occurrencesOn(repo.get(id)!!, time.today())[index].key

    @Test
    fun duplicateTakenAction_isRecordedOnceAndConsumesStockOnce() = runBlocking {
        val id = repo.create(medDraft())
        val key = todaysKey(id)
        val first = repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.NOTIFICATION)
        val second = repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.NOTIFICATION)
        val fromWidget = repo.recordDose(id, key, MedicationEventType.SKIPPED, ActionSource.WIDGET)
        assertTrue(first is MedicationActionResult.Recorded)
        assertEquals(MedicationActionResult.AlreadyResolved(MedicationEventType.TAKEN), second)
        assertEquals(MedicationActionResult.AlreadyResolved(MedicationEventType.TAKEN), fromWidget)
        assertEquals(9.0, stock(id), 0.0)
        assertEquals(1, repo.get(id)!!.events.size)
    }

    @Test
    fun undoTaken_restoresStockOnceAndAllowsTakingAgain() = runBlocking {
        val id = repo.create(medDraft())
        val key = todaysKey(id)
        repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.WIDGET)
        assertEquals(9.0, stock(id), 0.0)
        assertTrue(repo.undoTaken(id, key))
        assertFalse(repo.undoTaken(id, key))
        assertEquals(10.0, stock(id), 0.0)
        assertTrue(repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.WIDGET) is MedicationActionResult.Recorded)
        assertEquals(9.0, stock(id), 0.0)
        // Both events stay in history; only the second one counts.
        assertEquals(1, repo.get(id)!!.events.count { it.isActive })
        assertEquals(2, repo.get(id)!!.events.size)
    }

    @Test
    fun concurrentDuplicateDeliveries_recordExactlyOnce() = runBlocking {
        val id = repo.create(medDraft())
        val key = todaysKey(id)
        val results = (1..12).map {
            async(Dispatchers.IO) { repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.NOTIFICATION) }
        }.awaitAll()
        assertEquals(1, results.count { it is MedicationActionResult.Recorded })
        assertEquals(11, results.count { it is MedicationActionResult.AlreadyResolved })
        assertEquals(9.0, stock(id), 0.0)
        assertEquals(1, repo.get(id)!!.adjustments.count { it.type == AdjustmentType.INTAKE })
    }

    @Test
    fun skipAndSnooze_doNotConsumeStock() = runBlocking {
        val id = repo.create(medDraft(times = listOf(LocalTime.of(8, 0), LocalTime.of(8, 30))))
        assertTrue(repo.snooze(id, todaysKey(id, 0), ActionSource.NOTIFICATION) is MedicationActionResult.Recorded)
        assertTrue(repo.recordDose(id, todaysKey(id, 1), MedicationEventType.SKIPPED, ActionSource.APP) is MedicationActionResult.Recorded)
        assertEquals(10.0, stock(id), 0.0)
        val statuses = OccurrenceRules.statusesOn(repo.get(id)!!, time.today(), time.now(), time.zone())
        assertEquals(listOf(OccurrenceState.SNOOZED, OccurrenceState.SKIPPED), statuses.map { it.state })
    }

    @Test
    fun duplicateSnoozeDelivery_isIgnored() = runBlocking {
        val id = repo.create(medDraft())
        val key = todaysKey(id)
        assertTrue(repo.snooze(id, key, ActionSource.NOTIFICATION) is MedicationActionResult.Recorded)
        assertEquals(MedicationActionResult.AlreadySnoozed, repo.snooze(id, key, ActionSource.NOTIFICATION))
        assertEquals(1, repo.get(id)!!.events.size)
    }

    @Test
    fun snoozeAfterTaken_isRejectedAsResolved() = runBlocking {
        val id = repo.create(medDraft())
        val key = todaysKey(id)
        repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.APP)
        assertEquals(MedicationActionResult.AlreadyResolved(MedicationEventType.TAKEN), repo.snooze(id, key, ActionSource.NOTIFICATION))
    }

    @Test
    fun correction_reversesIntakeExactlyOnce_andAllowsNewRecord() = runBlocking {
        val id = repo.create(medDraft())
        val key = todaysKey(id)
        val recorded = repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.APP) as MedicationActionResult.Recorded
        assertEquals(9.0, stock(id), 0.0)
        assertTrue(repo.voidEvent(recorded.eventId))
        assertFalse(repo.voidEvent(recorded.eventId))
        assertEquals(10.0, stock(id), 0.0)
        val data = repo.get(id)!!
        assertEquals(listOf(AdjustmentType.INITIAL, AdjustmentType.INTAKE, AdjustmentType.INTAKE_REVERSAL), data.adjustments.map { it.type })
        // Original event is kept and marked corrected.
        assertEquals(1, data.events.size)
        assertFalse(data.events.single().isActive)
        // The occurrence can now be recorded again (e.g. as skipped).
        assertTrue(repo.recordDose(id, key, MedicationEventType.SKIPPED, ActionSource.APP) is MedicationActionResult.Recorded)
        assertEquals(10.0, stock(id), 0.0)
    }

    @Test
    fun correctingSkip_doesNotTouchStock() = runBlocking {
        val id = repo.create(medDraft())
        val skipped = repo.recordDose(id, todaysKey(id), MedicationEventType.SKIPPED, ActionSource.APP) as MedicationActionResult.Recorded
        assertTrue(repo.voidEvent(skipped.eventId))
        assertEquals(10.0, stock(id), 0.0)
        assertEquals(1, repo.get(id)!!.adjustments.size)
    }

    @Test
    fun insufficientStock_recordsIntakeAndReconcilesOnCorrection() = runBlocking {
        val id = repo.create(medDraft(quantity = 2.0, stock = 0.5))
        val result = repo.recordDose(id, todaysKey(id), MedicationEventType.TAKEN, ActionSource.APP) as MedicationActionResult.Recorded
        assertTrue(result.insufficientStock)
        assertEquals(0.0, stock(id), 0.0)
        repo.voidEvent(result.eventId)
        assertEquals(0.5, stock(id), 0.0)
    }

    @Test
    fun lowStockThreshold_reportedOnlyWhenCrossed() = runBlocking {
        val id = repo.create(medDraft(times = listOf(LocalTime.of(8, 0), LocalTime.of(8, 30)), stock = 10.0, threshold = 9.0))
        val first = repo.recordDose(id, todaysKey(id, 0), MedicationEventType.TAKEN, ActionSource.APP) as MedicationActionResult.Recorded
        assertTrue(first.becameLow)
        val second = repo.recordDose(id, todaysKey(id, 1), MedicationEventType.TAKEN, ActionSource.APP) as MedicationActionResult.Recorded
        assertFalse(second.becameLow)
        assertTrue(synchronized(changes) { changes.any { it is DataChange.Medications && it.lowStockMedicationId == id } })
    }

    @Test
    fun packagesAndManualCounts_areLoggedAdjustments() = runBlocking {
        val id = repo.create(medDraft(stock = 3.0))
        assertTrue(repo.addPackage(id, 28.0, "new box"))
        assertFalse(repo.addPackage(id, -5.0, null))
        assertFalse(repo.addPackage(id, Double.NaN, null))
        assertEquals(31.0, stock(id), 0.0)
        assertTrue(repo.setStock(id, 30.0, "counted"))
        assertEquals(30.0, stock(id), 0.0)
        val types = repo.get(id)!!.adjustments.map { it.type }
        assertEquals(listOf(AdjustmentType.INITIAL, AdjustmentType.PACKAGE_ADDED, AdjustmentType.MANUAL_CORRECTION), types)
    }

    @Test
    fun inventoryDisabled_intakeDoesNotCreateAdjustments() = runBlocking {
        val id = repo.create(medDraft(inventory = false, stock = null))
        repo.recordDose(id, todaysKey(id), MedicationEventType.TAKEN, ActionSource.APP)
        assertTrue(repo.get(id)!!.adjustments.isEmpty())
    }

    @Test
    fun scheduleEditAfterRecordingToday_takesEffectTomorrow_andKeepsHistory() = runBlocking {
        val id = repo.create(medDraft())
        val key = todaysKey(id)
        repo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.APP)
        repo.update(id, medDraft(times = listOf(LocalTime.of(9, 0)), stock = null))
        val data = repo.get(id)!!
        val today = OccurrenceRules.statusesOn(data, DAY1, time.now(), time.zone())
        assertEquals(listOf(LocalTime.of(8, 0)), today.map { it.occurrence.time })
        assertEquals(OccurrenceState.TAKEN, today.single().state)
        assertEquals(listOf(LocalTime.of(9, 0)), OccurrenceRules.occurrencesOn(data, DAY1.plusDays(1)).map { it.time })
        assertEquals(key, data.events.single().occurrenceKey)
        assertEquals(9.0, stock(id), 0.0)
    }

    @Test
    fun scheduleEditWithoutRecordsToday_appliesToday_andOldNotificationBecomesStale() = runBlocking {
        val id = repo.create(medDraft())
        time.set(DAY1.plusDays(2), 7)
        val oldKey = todaysKey(id)
        repo.update(id, medDraft(times = listOf(LocalTime.of(9, 0)), stock = null))
        val data = repo.get(id)!!
        assertEquals(listOf(LocalTime.of(9, 0)), OccurrenceRules.occurrencesOn(data, time.today()).map { it.time })
        // Earlier days keep the old version.
        assertEquals(listOf(LocalTime.of(8, 0)), OccurrenceRules.occurrencesOn(data, DAY1).map { it.time })
        assertEquals(
            MedicationActionResult.Rejected(RejectReason.UNKNOWN_OCCURRENCE),
            repo.recordDose(id, oldKey, MedicationEventType.TAKEN, ActionSource.NOTIFICATION),
        )
        assertEquals(10.0, stock(id), 0.0)
    }

    @Test
    fun pauseAndResume_keepHistoryAndCreateNoOccurrencesWhilePaused() = runBlocking {
        val id = repo.create(medDraft())
        repo.recordDose(id, todaysKey(id), MedicationEventType.TAKEN, ActionSource.APP)
        time.set(DAY1.plusDays(1), 7)
        val pausedKey = todaysKey(id)
        repo.pause(id)
        assertTrue(OccurrenceRules.occurrencesOn(repo.get(id)!!, time.today()).isEmpty())
        assertEquals(
            MedicationActionResult.Rejected(RejectReason.UNKNOWN_OCCURRENCE),
            repo.recordDose(id, pausedKey, MedicationEventType.TAKEN, ActionSource.NOTIFICATION),
        )
        time.set(DAY1.plusDays(4), 7)
        repo.resume(id)
        val data = repo.get(id)!!
        assertTrue(OccurrenceRules.occurrencesOn(data, DAY1.plusDays(2)).isEmpty())
        assertEquals(1, OccurrenceRules.occurrencesOn(data, DAY1.plusDays(4)).size)
        assertEquals(1, data.events.size)
        assertEquals(9.0, stock(id), 0.0)
    }

    @Test
    fun futureOccurrence_cannotBeRecorded() = runBlocking {
        val id = repo.create(medDraft())
        val tomorrowKey = OccurrenceRules.occurrencesOn(repo.get(id)!!, DAY1.plusDays(1)).first().key
        assertEquals(
            MedicationActionResult.Rejected(RejectReason.FUTURE_DATE),
            repo.recordDose(id, tomorrowKey, MedicationEventType.TAKEN, ActionSource.WIDGET),
        )
    }

    @Test
    fun dataSurvivesDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("reopen-test.db")
        val fileDb = Room.databaseBuilder(context, DaymarkDatabase::class.java, "reopen-test.db").build()
        val fileRepo = MedicationRepository(fileDb, time)
        val id = fileRepo.create(medDraft())
        val key = OccurrenceRules.occurrencesOn(fileRepo.get(id)!!, time.today()).first().key
        fileRepo.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.APP)
        fileDb.close()

        val reopened = Room.databaseBuilder(context, DaymarkDatabase::class.java, "reopen-test.db").build()
        val again = MedicationRepository(reopened, time)
        assertEquals(9.0, InventoryRules.stock(again.get(id)!!.adjustments), 0.0)
        assertEquals(MedicationActionResult.AlreadyResolved(MedicationEventType.TAKEN), again.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.NOTIFICATION))
        reopened.close()
    }
}
