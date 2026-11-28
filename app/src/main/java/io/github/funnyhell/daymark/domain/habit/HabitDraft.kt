package io.github.funnyhell.daymark.domain.habit

import java.time.LocalDate
import java.time.LocalTime

/** User input for creating or editing a habit. */
data class HabitDraft(
    val name: String,
    val description: String?,
    val colorArgb: Long,
    val type: HabitType,
    val unit: String?,
    val startDate: LocalDate,
    val recurrence: Recurrence,
    val target: Double,
    val reminderTime: LocalTime?,
    val showOnWidget: Boolean,
    /** Steps of a checklist habit, in order; [StepDraft.id] is null for a new step. */
    val steps: List<StepDraft> = emptyList(),
)

data class StepDraft(val id: String?, val title: String, val target: Int = 1)

enum class HabitDraftError { NAME_REQUIRED, NAME_TOO_LONG, TARGET_INVALID, UNIT_REQUIRED, START_IN_FUTURE_TOO_FAR, STEPS_REQUIRED, STEP_INVALID }

object HabitDraftValidator {
    const val MAX_NAME = 60
    const val MAX_MULTI_PER_DAY = 50
    const val MAX_WEEKLY = 7 * MAX_MULTI_PER_DAY
    const val MAX_QUANTITY = 1_000_000.0
    const val MAX_STEPS = 30

    fun validate(draft: HabitDraft, today: LocalDate): List<HabitDraftError> = buildList {
        val name = draft.name.trim()
        if (name.isEmpty()) add(HabitDraftError.NAME_REQUIRED)
        if (name.length > MAX_NAME) add(HabitDraftError.NAME_TOO_LONG)
        val t = draft.target
        val targetOk = t.isFinite() && when (draft.type) {
            HabitType.CHECK, HabitType.ABSTINENCE, HabitType.CHECKLIST, HabitType.WEEKLY_CHECKLIST -> true
            HabitType.MULTI -> t >= 2 && t <= MAX_MULTI_PER_DAY && t == Math.floor(t)
            HabitType.WEEKLY_GOAL -> t >= 1 && t <= MAX_WEEKLY && t == Math.floor(t)
            HabitType.QUANTITY -> t > 0 && t <= MAX_QUANTITY
        }
        if (!targetOk) add(HabitDraftError.TARGET_INVALID)
        if (draft.type == HabitType.QUANTITY && draft.unit.isNullOrBlank()) add(HabitDraftError.UNIT_REQUIRED)
        if (draft.startDate.isAfter(today.plusYears(1))) add(HabitDraftError.START_IN_FUTURE_TOO_FAR)
        if (draft.type.isChecklist) {
            val steps = draft.steps.filter { it.title.isNotBlank() }
            if (steps.isEmpty()) add(HabitDraftError.STEPS_REQUIRED)
            if (steps.any { it.title.trim().length > MAX_NAME || it.target !in 1..MAX_MULTI_PER_DAY } || steps.size > MAX_STEPS) {
                add(HabitDraftError.STEP_INVALID)
            }
        }
    }

    /** The target stored for a type; types without a user target get a fixed value. */
    fun normalizedTarget(draft: HabitDraft): Double = when (draft.type) {
        HabitType.CHECK, HabitType.CHECKLIST, HabitType.WEEKLY_CHECKLIST -> 1.0
        HabitType.ABSTINENCE -> 0.0
        else -> draft.target
    }

    /** Weekly types and ABSTINENCE are evaluated on every day; their recurrence is stored as daily. */
    fun normalizedRecurrence(draft: HabitDraft): Recurrence = when (draft.type) {
        HabitType.WEEKLY_GOAL, HabitType.WEEKLY_CHECKLIST, HabitType.ABSTINENCE -> Recurrence.Daily
        else -> draft.recurrence
    }

    /** Validates a progress amount entered by the user. */
    fun isValidAmount(amount: Double): Boolean = amount.isFinite() && amount > 0 && amount <= MAX_QUANTITY
}
