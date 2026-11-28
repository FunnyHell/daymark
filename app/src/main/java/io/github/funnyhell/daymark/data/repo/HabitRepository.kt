package io.github.funnyhell.daymark.data.repo

import androidx.room.withTransaction
import io.github.funnyhell.daymark.data.db.DELETED_HABIT
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.db.DeletedRecordEntity
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
import io.github.funnyhell.daymark.domain.common.roundQuantity
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitDraft
import io.github.funnyhell.daymark.domain.habit.HabitDraftError
import io.github.funnyhell.daymark.domain.habit.HabitDraftValidator
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import io.github.funnyhell.daymark.domain.habit.HabitSchedule
import io.github.funnyhell.daymark.domain.habit.HabitType
import java.time.LocalDate
import io.github.funnyhell.daymark.data.db.HabitStepEntity
import io.github.funnyhell.daymark.domain.common.startOfWeek
import java.time.DayOfWeek
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
    /** First day of the week from the settings; weekly goals and weekly checklists count from it. */
    private val weekStart: suspend () -> DayOfWeek = { DayOfWeek.MONDAY },
) {
    private suspend fun evaluator(data: HabitData) = HabitEvaluator(data, weekStart())

    private val dao = db.habitDao()
    private val pauseDao = db.pauseDao()

    fun observeAll(): Flow<List<HabitData>> = combine(
        dao.observeHabits(), dao.observeSchedules(), pauseDao.observePauses(), dao.observeEvents(), dao.observeSteps(),
    ) { h, s, p, e, st -> assembleHabits(h, s, p, e, st) }

    fun observe(id: String): Flow<HabitData?> = observeAll().map { list -> list.firstOrNull { it.habit.id == id } }

    suspend fun all(): List<HabitData> = assembleHabits(dao.habits(), dao.schedules(), db.pauseDao().pauses(), dao.events(), dao.steps())

    suspend fun get(id: String): HabitData? {
        val habit = dao.habit(id) ?: return null
        return assembleHabits(listOf(habit), dao.schedulesFor(id), pauseDao.pausesFor(id), dao.eventsFor(id), dao.stepsFor(id)).single()
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
            if (draft.type.isChecklist) saveSteps(id, draft, draft.startDate, hasHistory = false)
        }
        listener.onDataChanged(DataChange.Habits)
        return id
    }

    /**
     * Brings the stored steps in line with [draft]. A step that is renamed or retargeted keeps its id. A removed
     * step is deleted when it has no history; otherwise it ends today, so earlier days are still evaluated with
     * the steps they had. A new step counts from [from].
     */
    private suspend fun saveSteps(habitId: String, draft: HabitDraft, from: LocalDate, hasHistory: Boolean) {
        val today = time.today()
        val existing = dao.stepsFor(habitId).filter { it.removedOn == null }
        val wanted = draft.steps.filter { it.title.isNotBlank() }
        val usedStepIds = dao.eventsFor(habitId).mapNotNull { it.stepId }.toSet()
        for (old in existing) {
            if (wanted.none { it.id == old.id }) {
                if (old.id in usedStepIds) dao.updateStep(old.copy(removedOn = maxOf(today, old.addedOn.plusDays(1))))
                else dao.deleteStep(old.id)
            }
        }
        wanted.forEachIndexed { index, step ->
            val old = existing.firstOrNull { it.id == step.id }
            if (old != null) {
                dao.updateStep(old.copy(title = step.title.trim(), target = step.target, sortOrder = index))
            } else {
                dao.insertStep(
                    HabitStepEntity(ids.newId(), habitId, step.title.trim(), step.target, index, if (hasHistory) maxOf(from, today) else from, null),
                )
            }
        }
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
            if (draft.type.isChecklist) saveSteps(id, draft, startDate, hasHistory = hasEvents)
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
            // Remembered so that sync deletes the habit on other devices too.
            db.deletedRecordDao().insert(DeletedRecordEntity(id, DELETED_HABIT, time.now()))
        }
        listener.onDataChanged(DataChange.Habits)
    }

    companion object {
        const val MAX_NOTE = 2000
    }

    /** Adds one completion (CHECK, MULTI, WEEKLY_GOAL). */
    suspend fun addCompletion(habitId: String, date: LocalDate = time.today()): HabitActionResult =
        record(habitId, date, HabitEventType.COMPLETION, 1.0, null, onlyIfIncomplete = false)

    /**
     * Idempotent completion used by notifications and widgets: records a completion only while the day
     * (or the week, for weekly goals) is below target, so a repeated or duplicated tap cannot over-count.
     */
    suspend fun completeIfIncomplete(habitId: String, date: LocalDate = time.today()): HabitActionResult {
        val data = get(habitId) ?: return HabitActionResult.Rejected(HabitRejectReason.NOT_FOUND)
        if (!data.habit.type.isChecklist) return record(habitId, date, HabitEventType.COMPLETION, 1.0, null, onlyIfIncomplete = true)
        // A checklist is completed by completing every open step (each up to its own target).
        var last: HabitActionResult = HabitActionResult.AlreadyDone
        for (p in evaluator(data).stepProgress(date)) {
            repeat(p.step.target - p.done) {
                val r = record(habitId, date, HabitEventType.COMPLETION, 1.0, null, onlyIfIncomplete = true, stepId = p.step.id)
                if (r is HabitActionResult.Rejected) return r
                if (r is HabitActionResult.Recorded) last = r
            }
        }
        return last
    }

    /**
     * Marks one checklist step once more (up to its target), or removes its latest mark: on [date] for a daily
     * checklist, in the week of [date] (up to [date]) for a weekly one. Returns whether anything changed.
     */
    suspend fun markStep(habitId: String, stepId: String, done: Boolean, date: LocalDate = time.today()): Boolean {
        if (done) return record(habitId, date, HabitEventType.COMPLETION, 1.0, null, onlyIfIncomplete = true, stepId = stepId) is HabitActionResult.Recorded
        val data = get(habitId) ?: return false
        val first = if (data.habit.type == HabitType.WEEKLY_CHECKLIST) date.startOfWeek(weekStart()) else date
        val changed = db.withTransaction {
            val latest = dao.eventsFor(habitId)
                .filter { it.stepId == stepId && it.voidedAt == null && it.type == HabitEventType.COMPLETION.name && !it.date.isBefore(first) && !it.date.isAfter(date) }
                .maxByOrNull { it.recordedAt } ?: return@withTransaction false
            dao.voidEvent(latest.id, time.now()) > 0
        }
        if (changed) listener.onDataChanged(DataChange.Habits)
        return changed
    }

    /**
     * Sets the note of a day: what was done and how ("bench 5×5 60 kg"). The previous note of that day is kept in
     * the history as corrected; a blank text only removes it.
     */
    suspend fun setNote(habitId: String, date: LocalDate, text: String?): Boolean {
        val changed = db.withTransaction {
            val data = get(habitId) ?: return@withTransaction false
            if (date.isAfter(time.today()) || data.habit.status == LifecycleStatus.ARCHIVED) return@withTransaction false
            val old = dao.eventsFor(habitId).filter { it.date == date && it.voidedAt == null && it.type == HabitEventType.NOTE.name }
            val clean = text?.trim()?.take(MAX_NOTE)?.ifEmpty { null }
            if (old.size == 1 && old.single().note == clean) return@withTransaction false
            old.forEach { dao.voidEvent(it.id, time.now()) }
            if (clean != null) dao.insertEvent(HabitEventEntity(ids.newId(), habitId, date, HabitEventType.NOTE.name, 0.0, clean, time.now(), null))
            old.isNotEmpty() || clean != null
        }
        if (changed) listener.onDataChanged(DataChange.Habits)
        return changed
    }

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
        stepId: String? = null,
    ): HabitActionResult {
        val result = db.withTransaction {
            val data = get(habitId) ?: return@withTransaction HabitActionResult.Rejected(HabitRejectReason.NOT_FOUND)
            val habit = data.habit
            val today = time.today()
            val typeOk = when (type) {
                HabitEventType.COMPLETION -> if (habit.type.isChecklist) stepId != null && data.steps.any { it.id == stepId && it.activeOn(date) }
                    else stepId == null && habit.type in setOf(HabitType.CHECK, HabitType.MULTI, HabitType.WEEKLY_GOAL)
                HabitEventType.PROGRESS -> habit.type == HabitType.QUANTITY
                HabitEventType.SKIP -> habit.type in setOf(HabitType.CHECK, HabitType.MULTI, HabitType.QUANTITY, HabitType.CHECKLIST)
                HabitEventType.LAPSE -> habit.type == HabitType.ABSTINENCE
                HabitEventType.NOTE -> true
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
            val evaluator = evaluator(data)
            if (onlyIfIncomplete) {
                val satisfied = when {
                    stepId != null -> evaluator.stepProgress(date).firstOrNull { it.step.id == stepId }?.complete ?: true
                    habit.type == HabitType.WEEKLY_GOAL -> evaluator.progressOn(date) > 0.0
                    else -> evaluator.progressOn(date) >= evaluator.targetOn(date)
                }
                if (satisfied) return@withTransaction HabitActionResult.AlreadyDone
            }
            if (type == HabitEventType.SKIP && evaluator.eventsOn(date).any { it.type == HabitEventType.SKIP }) {
                return@withTransaction HabitActionResult.AlreadyDone
            }
            val id = ids.newId()
            dao.insertEvent(HabitEventEntity(id, habitId, date, type.name, amount, note?.trim()?.ifEmpty { null }, time.now(), null, stepId))
            HabitActionResult.Recorded(id)
        }
        if (result is HabitActionResult.Recorded) listener.onDataChanged(DataChange.Habits)
        return result
    }

    /**
     * Removes the latest mark of [date]: the most recent active completion is voided (kept in history as
     * corrected). Returns false when the day has no completion to remove.
     */
    suspend fun undoCompletion(habitId: String, date: LocalDate = time.today()): Boolean {
        val changed = db.withTransaction {
            val latest = dao.eventsFor(habitId)
                .filter { it.date == date && it.voidedAt == null && it.type == HabitEventType.COMPLETION.name }
                .maxByOrNull { it.recordedAt } ?: return@withTransaction false
            dao.voidEvent(latest.id, time.now()) > 0
        }
        if (changed) listener.onDataChanged(DataChange.Habits)
        return changed
    }

    /**
     * Brings a day to the requested state, used by toggles in the app and in widgets. The caller passes the
     * state it wants rather than "toggle", so a stale widget or a repeated tap cannot flip the result back:
     * marking a done day or unmarking an empty one changes nothing.
     *
     * By type: a check, a counted habit and a weekly goal get one completion more or their latest one removed;
     * an amount is filled up to the day's target or its latest entry removed; an abstinence day is clean unless
     * a slip is recorded, so "not done" records a slip and "done" removes the day's slips. Removed entries stay
     * in the history as corrected.
     */
    suspend fun setDone(habitId: String, done: Boolean, date: LocalDate = time.today()): Boolean {
        val data = get(habitId) ?: return false
        val evaluator = evaluator(data)
        val isDone = when (data.habit.type) {
            HabitType.WEEKLY_GOAL -> evaluator.progressOn(date) > 0.0
            HabitType.CHECKLIST, HabitType.WEEKLY_CHECKLIST -> evaluator.stepProgress(date).let { s -> s.isNotEmpty() && s.all { it.complete } }
            HabitType.ABSTINENCE -> evaluator.eventsOn(date).none { it.type == HabitEventType.LAPSE }
            else -> evaluator.progressOn(date) >= evaluator.targetOn(date)
        }
        if (done == isDone) return false
        return when (data.habit.type) {
            HabitType.ABSTINENCE -> if (done) undoLatest(habitId, date, HabitEventType.LAPSE, all = true) else recordLapse(habitId, date) is HabitActionResult.Recorded
            HabitType.QUANTITY -> if (done) {
                addProgress(habitId, (evaluator.targetOn(date) - evaluator.progressOn(date)).roundQuantity(), date) is HabitActionResult.Recorded
            } else {
                undoLatest(habitId, date, HabitEventType.PROGRESS, all = false)
            }
            // "Not done" on a checklist removes the marks made on that day; marks of other days of a week stay.
            HabitType.CHECKLIST, HabitType.WEEKLY_CHECKLIST -> if (done) {
                completeIfIncomplete(habitId, date) is HabitActionResult.Recorded
            } else {
                undoLatest(habitId, date, HabitEventType.COMPLETION, all = true)
            }
            else -> if (done) completeIfIncomplete(habitId, date) is HabitActionResult.Recorded else undoCompletion(habitId, date)
        }
    }

    /** Voids the latest active event of [type] on [date], or every one of them when [all] is set. */
    private suspend fun undoLatest(habitId: String, date: LocalDate, type: HabitEventType, all: Boolean): Boolean {
        val changed = db.withTransaction {
            val events = dao.eventsFor(habitId)
                .filter { it.date == date && it.voidedAt == null && it.type == type.name }
                .sortedByDescending { it.recordedAt }
            (if (all) events else events.take(1)).count { dao.voidEvent(it.id, time.now()) > 0 } > 0
        }
        if (changed) listener.onDataChanged(DataChange.Habits)
        return changed
    }

    /** Marks an event as corrected. The event stays in history but no longer counts. Idempotent. */
    suspend fun voidEvent(eventId: String): Boolean {
        val changed = dao.voidEvent(eventId, time.now()) > 0
        if (changed) listener.onDataChanged(DataChange.Habits)
        return changed
    }
}
