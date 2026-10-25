package io.github.funnyhell.daymark.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.funnyhell.daymark.data.backup.BackupRepository
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.repo.HabitRepository
import io.github.funnyhell.daymark.data.repo.MedicationRepository
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.data.sync.SyncMerger
import io.github.funnyhell.daymark.data.sync.SyncRepository
import io.github.funnyhell.daymark.domain.habit.HabitDraft
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Two devices (two databases) exchange payloads the way the sync screen does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncRepositoryTest {
    private class Device(val time: FakeTime) {
        val db: DaymarkDatabase = inMemoryDb()
        val habits = HabitRepository(db, time)
        val meds = MedicationRepository(db, time)
        val backup = BackupRepository(db, time, "test", { Settings() }, { })
        val sync = SyncRepository(db, backup, time, "test", { Settings() })
    }

    private val time = FakeTime(DAY1.atTime(9, 0).toInstant(ZoneOffset.UTC))
    private lateinit var phone: Device
    private lateinit var tablet: Device

    @Before
    fun setUp() {
        phone = Device(time)
        tablet = Device(time)
    }

    @After
    fun tearDown() {
        phone.db.close()
        tablet.db.close()
    }

    private suspend fun syncBoth(): Pair<Int, Int> {
        val fromPhone = phone.sync.payload()
        val fromTablet = tablet.sync.payload()
        return tablet.sync.applyRemote(fromPhone) to phone.sync.applyRemote(fromTablet)
    }

    private fun draft(name: String) = HabitDraft(name, null, 0xFF00AA00, HabitType.CHECK, null, DAY1, Recurrence.Daily, 1.0, null, true)

    @Test
    fun bothDevicesEndWithTheSameData_andRepeatingChangesNothing() = runBlocking {
        val h = phone.habits.create(draft("Read"))
        phone.habits.completeIfIncomplete(h)
        tablet.habits.create(draft("Stretch"))
        syncBoth()
        // merge(x, x) only sorts the records, which makes the two databases comparable.
        val onPhone = phone.backup.snapshot().let { SyncMerger.merge(it, it) }
        val onTablet = tablet.backup.snapshot().let { SyncMerger.merge(it, it) }
        assertEquals(onPhone, onTablet)
        assertEquals(setOf("Read", "Stretch"), phone.habits.all().map { it.habit.name }.toSet())
        assertEquals(0 to 0, syncBoth())
    }

    @Test
    fun deletionOnOneDevice_removesTheRecordOnTheOther() = runBlocking {
        val h = phone.habits.create(draft("Read"))
        syncBoth()
        phone.habits.delete(h)
        syncBoth()
        assertNull(tablet.habits.get(h))
    }

    @Test
    fun doseTakenOnBothDevices_countsOnceInStock() = runBlocking {
        val id = phone.meds.create(medDraft(stock = 10.0))
        syncBoth()
        val key = OccurrenceRules.occurrencesOn(phone.meds.get(id)!!, DAY1).first().key
        phone.meds.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.APP)
        time.current = time.current.plusSeconds(60)
        tablet.meds.recordDose(id, key, MedicationEventType.TAKEN, ActionSource.APP)
        syncBoth()
        for (device in listOf(phone, tablet)) {
            val data = device.meds.get(id)!!
            assertEquals(9.0, InventoryRules.stock(data.adjustments), 0.0)
            assertEquals(1, data.events.count { it.isActive && it.type == MedicationEventType.TAKEN })
        }
    }
}
