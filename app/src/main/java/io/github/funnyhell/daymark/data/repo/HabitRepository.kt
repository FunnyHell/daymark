package io.github.funnyhell.daymark.data.repo

import androidx.room.withTransaction
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.db.HabitEntity
import io.github.funnyhell.daymark.data.db.HabitEventEntity
import io.github.funnyhell.daymark.data.db.OWNER_HABIT
import io.github.funnyhell.daymark.data.db.PauseEntity
import io.github.funnyhell.daymark.data.db.assembleHabits
import io.github.funnyhell.daymark.data.db.toDomain
import io.github.funnyhell.daymark.data.db.toEntity
import io.github.funnyhell.daymark.domain.common.IdGenerator
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.TimeSource
import io.github.funnyhell.daymark.domain.common.isPausedOn
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitDraft
import io.github.funnyhell.daymark.domain.habit.HabitDraftError
import io.github.funnyhell.daymark.domain.habit.HabitDraftValidator
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitSchedule
import io.github.funnyhell.daymark.domain.habit.HabitType
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

sealed interface HabitActionResult {
    data class Recorded(val eventId: String) : HabitActionResult

    /** The requested "complete" action was already satisfied; nothing was written. */
    data object AlreadyDone : HabitActionResult

    data class Rejected(val reason: HabitRejectReason) : HabitActionResult
}

enum class HabitRejectReason { NOT_FOUND, ARCHIVED, FUTURE_DATE, BEFORE_START, PAUSED_DAY, INVALID_AMOUNT, WRONG_TYPE }

class HabitDraftException(val errors: List<HabitDraftError>) : IllegalArgumentException(errors.joinToString())

class HabitRepository(
    private val db: DaymarkDatabase,
    private val time: TimeSource,
    private val ids: IdGenerator = IdGenerator.Random,
    private val listener: DataChangeListener = DataChangeListener.None,
) {
    private val dao = db.habitDao()
    private val pauseDao = db.pauseDao()

    fun observeAll(): Flow<List<HabitData>> = combine(
        dao.observeHabits(), dao.observeSchedules(), pauseDao.observePauses(), dao.observeEvents(),
    ) { h, s, p, e -> assembleHabits(h, s, p, e) }

    fun observe(id: String): Flow<HabitData?> = observeAll().map { list -> list.firstOrNull { it.habit.id == id } }

    suspend fun all(): List<HabitData> = assembleHabits(dao.habits(), dao.schedules(), db.pauseDao().pauses(), dao.events())

    suspend fun get(id: String): HabitData? {
        val habit = dao.habit(id) ?: return null
        return assembleHabits(listOf(habit), dao.schedulesFor(id), pauseDao.pausesFor(id), dao.eventsFor(id)).single()
    }

    suspend fun create(draft: HabitDraft): String {
        val errors = HabitDraftValidator.validate(draft, time.today())
        if (errors.isNotEmpty()) throw HabitDraftException(errors)
        val now = time.now()
        val id = ids.newId()
        db.withTransaction {
            dao.insertHabit(
                HabitEntity(
                    id = id, name = draft.name.trim(), description = draft.description?.trim()?.ifEmpty { null },
                    colorArgb = draft.colorArgb, type = draft.type.name, status = LifecycleStatus.ACTIVE.name,
                    unit = draft.unit?.trim()?.ifEmpty { null }, startDate = draft.startDate,
                    reminderTime = draft.reminderTime, showOnWidget = draft.showOnWidget,
                    sortOrder = dao.nextSortOrder(), createdAt = now, updatedAt = now,
                ),
            )
            dao.insertSchedule(
                HabitSchedule(
                    id = ids.newId(), habitId = id, effectiveFrom = draft.startDate, effectiveUntil = null,
                    recurrence = HabitDraftValidator.normalizedRecurrence(draft), target = HabitDraftValidator.normalizedTarget(draft),
                ).toEntity(),
            )
        }
        listener.onDataChanged(DataChange.Habits)
        return id
    }

    /**
     * Updates a habit. The habit type cannot change (its history would lose meaning). A changed recurrence or
     * target creates a new schedule version effective today; earlier days keep the version that applied then.
     */
    suspend fun update(id: String, draft: HabitDraft) {
        val today = time.today()
        val errors = HabitDraftValidator.validate(draft, today)
        if (errors.isNotEmpty()) throw HabitDraftException(errors)
        db.withTransaction {
            val existing = dao.habit(id) ?: return@withTransaction
            require(existing.type == draft.type.name) { "Habit type cannot be changed" }
            val hasEvents = dao.eventsFor(id).isNotEmpty()
            // The start date can only move when there is no history that it could hide.
            val startDate = if (hasEvents) existing.startDate else draft.startDate
            dao.updateHabit(
                existing.copy(
                    name = draft.name.trim(), description = draft.description?.trim()?.ifEmpty { null },
                    colorArgb = draft.colorArgb, unit = draft.unit?.trim()?.ifEmpty { null }, startDate = startDate,
                    reminderTime = draft.reminderTime, showOnWidget = draft.showOnWidget, updatedAt = time.now(),
                ),
            )
            val recurrence = HabitDraftValidator.normalizedRecurrence(draft)
            val target = HabitDraftValidator.normalizedTarget(draft)
            val schedules = dao.schedulesFor(id).map { it.toDomain() }
            val current = schedules.lastOrNull { it.effectiveUntil == null } ?: schedules.lastOrNull()
            if (current == null) {
                dao.insertSchedule(HabitSchedule(ids.newId(), id, startDate, null, recurrence, target).toEntity())
            } else if (current.recurrence != recurrence || current.target != target) {
                if (!hasEvents && schedules.size == 1) {
                    // No recorded history yet: the single version can simply be replaced.
                    dao.updateSchedule(current.copy(effectiveFrom = startDate, recurrence = recurrence, target = target).toEntity())
                } else if (!current.effectiveFrom.isBefore(today)) {
                    // The current version has not applied to a past day yet.
                    dao.updateSchedule(current.copy(recurrence = recurrence, target = target).toEntity())
                } else {
                    dao.updateSchedule(current.copy(effectiveUntil = today).toEntity())
                    dao.insertSchedule(HabitSchedule(ids.newId(), id, today, null, recurrence, target).toEntity())
                }
            } else if (!hasEvents && schedules.size == 1 && current.effectiveFrom != startDate) {
                dao.updateSchedule(current.copy(effectiveFrom = startDate).toEntity())
            }
        }
        listener.onDataChanged(DataChange.Habits)
    }

    suspend fun pause(id: String) = setLifecycle(id, LifecycleStatus.PAUSED)

    suspend fun resume(id: String) = setLifecycle(id, LifecycleStatus.ACTIVE)

    suspend fun archive(id: String) = setLifecycle(id, LifecycleStatus.ARCHIVED)

    /**
     * Pausing and archiving open a pause interval starting today; resuming closes it (end = today, exclusive).
     * The interval is what keeps paused days neutral in streaks and rates.
     */
    private suspend fun setLifecycle(id: String, target: LifecycleStatus) {
        val today = time.today()
        db.withTransaction {
            val habit = dao.habit(id) ?: return@withTransaction
            if (habit.status == target.name) return@withTransaction
            val open = pauseDao.openPause(id)
            when (target) {
                LifecycleStatus.ACTIVE -> if (open != null) {
                    if (!open.start.isBefore(today)) pauseDao.delete(open.id) else pauseDao.close(open.id, today)
                }
                LifecycleStatus.PAUSED, LifecycleStatus.ARCHIVED -> if (open == null) {
                    pauseDao.insert(PauseEntity(ids.newId(), OWNER_HABIT, id, today, null, time.now()))
                }
            }
            dao.updateHabit(habit.copy(status = target.name, updatedAt = time.now()))
        }
        listener.onDataChanged(DataChange.Habits)
    }

    /** Permanently deletes a habit and all of its history. Only offered to the user with explicit confirmation. */
    suspend fun delete(id: String) {
        db.withTransaction {
            pauseDao.deleteFor(id)
            dao.deleteHabit(id)
        }
        listener.onDataChanged(DataChange.Habits)
    }

    /** Adds one completion (CHECK, MULTI, WEEKLY_GOAL). */
    suspend fun addCompletion(habitId: String, date: LocalDate = time.today()): HabitActionResult =
        record(habitId, date, HabitEventType.COMPLETION, 1.0, null, onlyIfIncomplete = false)

    /**
     * Idempotent completion used by notifications and widgets: records a completion only while the day
     * (or the week, for weekly goals) is below target, so a repeated or duplicated tap cannot over-count.
     */
    suspend fun completeIfIncomplete(habitId: String, date: LocalDate = time.today()): HabitActionResult =
        record(habitId, date, HabitEventType.COMPLETION, 1.0, null, onlyIfIncomplete = true)

    suspend fun addProgress(habitId: String, amount: Double, date: LocalDate = time.today(), note: String? = null): HabitActionResult =
        record(habitId, date, HabitEventType.PROGRESS, amount, note, onlyIfIncomplete = false)

    suspend fun skip(habitId: String, date: LocalDate = time.today()): HabitActionResult =
        record(habitId, date, HabitEventType.SKIP, 0.0, null, onlyIfIncomplete = false)

    suspend fun recordLapse(habitId: String, date: LocalDate = time.today(), note: String? = null): HabitActionResult =
        record(habitId, date, HabitEventType.LAPSE, 0.0, note, onlyIfIncomplete = false)

    private suspend fun record(
        habitId: String,
        date: LocalDate,
        type: HabitEventType,
        amount: Double,
        note: String?,
        onlyIfIncomplete: Boolean,
    ): HabitActionResult {
        val result = db.withTransaction {
            val data = get(habitId) ?: return@withTransaction HabitActionResult.Rejected(HabitRejectReason.NOT_FOUND)
            val habit = data.habit
            val today = time.today()
            val typeOk = when (type) {
                HabitEventType.COMPLETION -> habit.type in setOf(HabitType.CHECK, HabitType.MULTI, HabitType.WEEKLY_GOAL)
                HabitEventType.PROGRESS -> habit.type == HabitType.QUANTITY
                HabitEventType.SKIP -> habit.type in setOf(HabitType.CHECK, HabitType.MULTI, HabitType.QUANTITY)
                HabitEventType.LAPSE -> habit.type == HabitType.ABSTINENCE
            }
            when {
                !typeOk -> return@withTransaction HabitActionResult.Rejected(HabitRejectReason.WRONG_TYPE)
                habit.status == LifecycleStatus.ARCHIVED ->
                    return@withTransaction HabitActionResult.Rejected(HabitRejectReason.ARCHIVED)
                date.isAfter(today) -> return@withTransaction HabitActionResult.Rejected(HabitRejectReason.FUTURE_DATE)
                date.isBefore(habit.startDate) -> return@withTransaction HabitActionResult.Rejected(HabitRejectReason.BEFORE_START)
                data.pauses.isPausedOn(date) -> return@withTransaction HabitActionResult.Rejected(HabitRejectReason.PAUSED_DAY)
                type == HabitEventType.PROGRESS && !HabitDraftValidator.isValidAmount(amount) ->
                    return@withTransaction HabitActionResult.Rejected(HabitRejectReason.INVALID_AMOUNT)
            }
            val evaluator = HabitEvaluator(data)
            if (onlyIfIncomplete) {
                val satisfied = if (habit.type == HabitType.WEEKLY_GOAL) {
                    evaluator.progressOn(date) > 0.0
                } else {
                    evaluator.progressOn(date) >= evaluator.targetOn(date)
                }
                if (satisfied) return@withTransaction HabitActionResult.AlreadyDone
            }
            if (type == HabitEventType.SKIP && evaluator.eventsOn(date).any { it.type == HabitEventType.SKIP }) {
                return@withTransaction HabitActionResult.AlreadyDone
            }
            val id = ids.newId()
            dao.insertEvent(HabitEventEntity(id, habitId, date, type.name, amount, note?.trim()?.ifEmpty { null }, time.now(), null))
            HabitActionResult.Recorded(id)
        }
        if (result is HabitActionResult.Recorded) listener.onDataChanged(DataChange.Habits)
        return result
    }

    /** Marks an event as corrected. The event stays in history but no longer counts. Idempotent. */
    suspend fun voidEvent(eventId: String): Boolean {
        val changed = dao.voidEvent(eventId, time.now()) > 0
        if (changed) listener.onDataChanged(DataChange.Habits)
        return changed
    }
}
