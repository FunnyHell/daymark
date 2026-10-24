package io.github.funnyhell.daymark.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.repo.HabitActionResult
import io.github.funnyhell.daymark.data.repo.HabitDraftException
import io.github.funnyhell.daymark.data.repo.HabitRejectReason
import io.github.funnyhell.daymark.data.repo.HabitRepository
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitDraft
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.Recurrence
import java.time.DayOfWeek
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class HabitRepositoryTest {
    private lateinit var db: DaymarkDatabase
    private lateinit var repo: HabitRepository
    private val time = FakeTime(DAY1.atTime(9, 0).toInstant(ZoneOffset.UTC))

    @Before
    fun setUp() {
        db = inMemoryDb()
        repo = HabitRepository(db, time)
    }

    @After
    fun tearDown() = db.close()

    private fun draft(
        type: HabitType = HabitType.CHECK,
        recurrence: Recurrence = Recurrence.Daily,
        target: Double = 1.0,
        unit: String? = null,
    ) = HabitDraft("Read", null, 0xFF00AA00, type, unit, DAY1, recurrence, target, null, true)

    @Test
    fun completeIfIncomplete_isIdempotent() = runBlocking {
        val id = repo.create(draft())
        assertTrue(repo.completeIfIncomplete(id) is HabitActionResult.Recorded)
        assertEquals(HabitActionResult.AlreadyDone, repo.completeIfIncomplete(id))
        assertEquals(HabitActionResult.AlreadyDone, repo.completeIfIncomplete(id))
        assertEquals(1, repo.get(id)!!.events.size)
    }

    @Test
    fun multiHabit_widgetStepStopsAtTarget() = runBlocking {
        val id = repo.create(draft(type = HabitType.MULTI, target = 2.0))
        assertTrue(repo.completeIfIncomplete(id) is HabitActionResult.Recorded)
        assertTrue(repo.completeIfIncomplete(id) is HabitActionResult.Recorded)
        assertEquals(HabitActionResult.AlreadyDone, repo.completeIfIncomplete(id))
        // The app's explicit "+1" may still go beyond the target.
        assertTrue(repo.addCompletion(id) is HabitActionResult.Recorded)
        assertEquals(3.0, HabitEvaluator(repo.get(id)!!).progressOn(DAY1), 0.0)
    }

    @Test
    fun invalidInputIsRejected() = runBlocking {
        val q = repo.create(draft(type = HabitType.QUANTITY, target = 2000.0, unit = "ml"))
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.INVALID_AMOUNT), repo.addProgress(q, -5.0))
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.INVALID_AMOUNT), repo.addProgress(q, Double.POSITIVE_INFINITY))
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.FUTURE_DATE), repo.addProgress(q, 100.0, DAY1.plusDays(1)))
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.BEFORE_START), repo.addProgress(q, 100.0, DAY1.minusDays(1)))
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.WRONG_TYPE), repo.addCompletion(q))
        try {
            repo.create(draft(type = HabitType.QUANTITY, target = 0.0, unit = null))
            fail("Expected validation error")
        } catch (e: HabitDraftException) {
            assertTrue(e.errors.isNotEmpty())
        }
    }

    @Test
    fun pauseResume_preservesHistoryAndKeepsPausedDaysNeutral() = runBlocking {
        val id = repo.create(draft())
        repo.completeIfIncomplete(id)
        time.set(DAY1.plusDays(1), 9)
        repo.completeIfIncomplete(id)
        time.set(DAY1.plusDays(2), 9)
        repo.pause(id)
        assertEquals(LifecycleStatus.PAUSED, repo.get(id)!!.habit.status)
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.PAUSED_DAY), repo.completeIfIncomplete(id))
        time.set(DAY1.plusDays(6), 9)
        repo.resume(id)
        repo.completeIfIncomplete(id)
        val data = repo.get(id)!!
        val e = HabitEvaluator(data)
        assertEquals(DayState.PAUSED, e.dayResult(DAY1.plusDays(3), time.today()).state)
        assertEquals(3, e.streaks(time.today()).current)
        assertEquals(3, data.events.size)
        assertEquals(1, data.pauses.size)
        assertEquals(DAY1.plusDays(6), data.pauses.single().end)
    }

    @Test
    fun pauseAndResumeSameDay_leavesNoInterval() = runBlocking {
        val id = repo.create(draft())
        repo.pause(id)
        repo.resume(id)
        assertTrue(repo.get(id)!!.pauses.isEmpty())
    }

    @Test
    fun archive_keepsHistoryAndBlocksNewRecords() = runBlocking {
        val id = repo.create(draft())
        repo.completeIfIncomplete(id)
        time.set(DAY1.plusDays(1), 9)
        repo.archive(id)
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.ARCHIVED), repo.completeIfIncomplete(id))
        val data = repo.get(id)!!
        assertEquals(LifecycleStatus.ARCHIVED, data.habit.status)
        assertEquals(1, data.events.size)
    }

    @Test
    fun scheduleEdit_createsNewVersionWithoutRewritingPast() = runBlocking {
        val id = repo.create(draft())
        repo.completeIfIncomplete(id)
        time.set(DAY1.plusDays(7), 9)
        repo.update(id, draft(recurrence = Recurrence.Weekdays(setOf(DayOfWeek.MONDAY))))
        val data = repo.get(id)!!
        assertEquals(2, data.schedules.size)
        assertEquals(DAY1.plusDays(7), data.schedules[0].effectiveUntil)
        val e = HabitEvaluator(data)
        // Tuesday in week 1 is still a missed daily occurrence; Tuesday in week 2 is not scheduled.
        assertEquals(DayState.MISSED, e.dayResult(DAY1.plusDays(1), DAY1.plusDays(9)).state)
        assertEquals(DayState.NOT_SCHEDULED, e.dayResult(DAY1.plusDays(8), DAY1.plusDays(9)).state)
    }

    @Test
    fun voidEvent_isIdempotentAndKeepsRecord() = runBlocking {
        val id = repo.create(draft())
        val recorded = repo.completeIfIncomplete(id) as HabitActionResult.Recorded
        assertTrue(repo.voidEvent(recorded.eventId))
        assertFalse(repo.voidEvent(recorded.eventId))
        val data = repo.get(id)!!
        assertEquals(1, data.events.size)
        assertFalse(data.events.single().isActive)
        assertEquals(DayState.PENDING, HabitEvaluator(data).dayResult(DAY1, DAY1).state)
    }

    @Test
    fun abstinence_lapseRecordedAsEvent() = runBlocking {
        val id = repo.create(draft(type = HabitType.ABSTINENCE, target = 0.0))
        time.set(DAY1.plusDays(5), 9)
        assertTrue(repo.recordLapse(id, note = "stressful day") is HabitActionResult.Recorded)
        assertEquals(HabitActionResult.Rejected(HabitRejectReason.WRONG_TYPE), repo.addCompletion(id))
        val e = HabitEvaluator(repo.get(id)!!)
        assertEquals(listOf(5), e.cleanRuns(time.today()).map { it.days })
        assertEquals(0, e.streaks(time.today()).current)
    }

    @Test
    fun delete_removesHabitAndItsRecords() = runBlocking {
        val id = repo.create(draft())
        repo.completeIfIncomplete(id)
        repo.pause(id)
        repo.delete(id)
        assertNull(repo.get(id))
        assertTrue(db.pauseDao().pauses().isEmpty())
        assertTrue(db.habitDao().events().isEmpty())
    }
}
