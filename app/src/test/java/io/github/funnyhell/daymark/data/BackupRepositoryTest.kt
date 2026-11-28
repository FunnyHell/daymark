package io.github.funnyhell.daymark.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.funnyhell.daymark.data.backup.BackupCodec
import io.github.funnyhell.daymark.data.backup.BackupRepository
import io.github.funnyhell.daymark.data.backup.ImportResult
import io.github.funnyhell.daymark.data.backup.Snapshot
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.db.ScheduledDoseEntity
import io.github.funnyhell.daymark.data.repo.HabitRepository
import io.github.funnyhell.daymark.data.repo.MedicationRepository
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.data.repo.ThemeMode
import io.github.funnyhell.daymark.domain.habit.HabitDraft
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.StepDraft
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackupRepositoryTest {
    private val time = FakeTime(DAY1.atTime(9, 0).toInstant(ZoneOffset.UTC))
    private lateinit var source: DaymarkDatabase
    private lateinit var target: DaymarkDatabase
    private var sourceSettings = Settings(themeMode = ThemeMode.DARK, firstDayOfWeek = DayOfWeek.SUNDAY, onboardingDone = true)
    private var targetSettings = Settings()

    @Before
    fun setUp() {
        source = inMemoryDb()
        target = inMemoryDb()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun backupRepo(db: DaymarkDatabase, read: () -> Settings, write: (Settings) -> Unit) =
        BackupRepository(db, time, "test", { read() }, { write(it) })

    private suspend fun populate(db: DaymarkDatabase) {
        val habits = HabitRepository(db, time)
        val meds = MedicationRepository(db, time)
        val h = habits.create(HabitDraft("Water", "glasses", 0xFF2196F3, HabitType.QUANTITY, "ml", DAY1, Recurrence.Weekdays(setOf(DayOfWeek.MONDAY)), 1500.0, LocalTime.of(20, 0), true))
        habits.addProgress(h, 500.0, note = "morning")
        habits.pause(h)
        val routine = habits.create(
            HabitDraft(
                "Evening", null, 0xFF2196F3, HabitType.CHECKLIST, null, DAY1, Recurrence.Daily, 1.0, null, true,
                steps = listOf(StepDraft(null, "Brush teeth", 2), StepDraft(null, "Shower")),
            ),
        )
        val step = habits.get(routine)!!.steps.first()
        habits.markStep(routine, step.id, done = true)
        habits.setNote(routine, DAY1, "late today")
        val m = meds.create(medDraft(times = listOf(LocalTime.of(8, 0), LocalTime.of(21, 0)), threshold = 5.0))
        val key = OccurrenceRules.occurrencesOn(meds.get(m)!!, DAY1).first().key
        val taken = meds.recordDose(m, key, MedicationEventType.TAKEN, ActionSource.NOTIFICATION) as io.github.funnyhell.daymark.data.repo.MedicationActionResult.Recorded
        meds.setDoseNote(taken.eventId, "after breakfast")
        meds.addPackage(m, 30.0, "box")
    }

    @Test
    fun exportThenImport_roundTripsEveryRecord() = runBlocking {
        populate(source)
        val json = backupRepo(source, { sourceSettings }, { sourceSettings = it }).exportJson()
        val targetRepo = backupRepo(target, { targetSettings }, { targetSettings = it })
        val result = targetRepo.importJson(json)
        assertTrue(result is ImportResult.Imported)
        val a = backupRepo(source, { sourceSettings }, {}).snapshot()
        val b = targetRepo.snapshot()
        assertEquals(a.normalized(), b.normalized())
        assertEquals(2, b.habitSteps.size)
        assertTrue(b.habitEvents.any { it.stepId != null })
        assertTrue(b.habitEvents.any { it.type == "NOTE" && it.note == "late today" })
        assertTrue(b.medicationEvents.any { it.note == "after breakfast" })
        assertEquals(ThemeMode.DARK, targetSettings.themeMode)
        assertEquals(DayOfWeek.SUNDAY, targetSettings.firstDayOfWeek)
    }

    @Test
    fun importReplacesExistingDataOnlyAfterValidation() = runBlocking {
        populate(target)
        val before = backupRepo(target, { targetSettings }, {}).snapshot()
        val repo = backupRepo(target, { targetSettings }, { targetSettings = it })

        assertTrue(repo.importJson("not json") is ImportResult.Invalid)
        val wrongVersion = """{"format":"daymark-backup","formatVersion":99,"exportedAt":"x","appVersion":"x","settings":{"themeMode":"SYSTEM","dynamicColor":true,"firstDayOfWeek":"MONDAY","medicationReminders":true,"habitReminders":true,"hideNamesOnLockScreen":true}}"""
        assertTrue(repo.importJson(wrongVersion) is ImportResult.Invalid)

        // A structurally valid file with a dangling reference must be rejected before anything is written.
        populate(source)
        val good = backupRepo(source, { sourceSettings }, {}).exportJson()
        val broken = good.replaceFirst("\"habitId\": \"", "\"habitId\": \"missing-")
        val invalid = repo.importJson(broken)
        assertTrue(invalid is ImportResult.Invalid)
        assertTrue((invalid as ImportResult.Invalid).errors.any { it.contains("missing") })
        assertEquals(before.normalized(), repo.snapshot().normalized())
        assertEquals(ThemeMode.SYSTEM, targetSettings.themeMode)
    }

    @Test
    fun failedReplaceRollsBackCompletely() = runBlocking {
        populate(target)
        val repo = backupRepo(target, { targetSettings }, {})
        val before = repo.snapshot()
        // Bypass validation to force a foreign-key failure in the middle of the transaction.
        val corrupt = before.copy(doses = before.doses + ScheduledDoseEntity("orphan", "no-such-schedule", LocalTime.NOON, "x", 1.0))
        try {
            repo.replaceAll(corrupt)
            fail("Expected constraint failure")
        } catch (expected: Exception) {
            // Expected: SQLite foreign key constraint.
        }
        assertEquals(before.normalized(), repo.snapshot().normalized())
    }

    @Test
    fun decodeRejectsDuplicateActiveResolutions() = runBlocking {
        populate(source)
        val snapshot = backupRepo(source, { sourceSettings }, {}).snapshot()
        val taken = snapshot.medicationEvents.first { it.type == "TAKEN" }
        val duplicated = snapshot.copy(medicationEvents = snapshot.medicationEvents + taken.copy(id = "dup"))
        val json = BackupCodec.encode(duplicated, sourceSettings, time.now(), "test")
        val result = backupRepo(target, { targetSettings }, {}).importJson(json)
        assertTrue(result is ImportResult.Invalid)
    }

    private fun Snapshot.normalized() = Snapshot(
        habits.sortedBy { it.id }, habitSchedules.sortedBy { it.id }, habitEvents.sortedBy { it.id }, pauses.sortedBy { it.id },
        medications.sortedBy { it.id }, medicationSchedules.sortedBy { it.id }, doses.sortedBy { it.id },
        medicationEvents.sortedBy { it.id }, adjustments.sortedBy { it.id }, deletions.sortedBy { it.id }, habitSteps.sortedBy { it.id },
    )
}
