package io.github.funnyhell.daymark.domain.medication

import java.time.DayOfWeek
import java.time.LocalTime

data class DoseDraft(val time: LocalTime, val doseLabel: String, val quantity: Double)

/** User input for creating or editing a medication. Dose labels and quantities are entered by the user. */
data class MedicationDraft(
    val name: String,
    val strength: String?,
    val notes: String?,
    val colorArgb: Long,
    val days: Set<DayOfWeek>,
    val doses: List<DoseDraft>,
    val inventoryEnabled: Boolean,
    val inventoryUnit: String,
    val lowStockThreshold: Double?,
    val lowStockAlerts: Boolean,
    /** Counted stock entered by the user; applied when inventory is first set up. */
    val initialStock: Double?,
)

enum class MedicationDraftError {
    NAME_REQUIRED, NAME_TOO_LONG, DAYS_REQUIRED, DUPLICATE_TIME, DOSE_QUANTITY_INVALID, TOO_MANY_DOSES,
    UNIT_REQUIRED, THRESHOLD_INVALID, STOCK_INVALID,
}

object MedicationDraftValidator {
    const val MAX_NAME = 60
    const val MAX_DOSES = 12
    const val MAX_QUANTITY = 100_000.0

    fun validate(draft: MedicationDraft): List<MedicationDraftError> = buildList {
        val name = draft.name.trim()
        if (name.isEmpty()) add(MedicationDraftError.NAME_REQUIRED)
        if (name.length > MAX_NAME) add(MedicationDraftError.NAME_TOO_LONG)
        if (draft.doses.isNotEmpty() && draft.days.isEmpty()) add(MedicationDraftError.DAYS_REQUIRED)
        if (draft.doses.size > MAX_DOSES) add(MedicationDraftError.TOO_MANY_DOSES)
        if (draft.doses.map { it.time.withSecond(0).withNano(0) }.toSet().size != draft.doses.size) add(MedicationDraftError.DUPLICATE_TIME)
        if (draft.doses.any { !isValidQuantity(it.quantity, allowZero = true) }) add(MedicationDraftError.DOSE_QUANTITY_INVALID)
        if (draft.inventoryEnabled) {
            if (draft.inventoryUnit.isBlank()) add(MedicationDraftError.UNIT_REQUIRED)
            draft.lowStockThreshold?.let { if (!isValidQuantity(it, allowZero = true)) add(MedicationDraftError.THRESHOLD_INVALID) }
            draft.initialStock?.let { if (!isValidQuantity(it, allowZero = true)) add(MedicationDraftError.STOCK_INVALID) }
        }
    }

    fun isValidQuantity(value: Double, allowZero: Boolean): Boolean =
        value.isFinite() && value <= MAX_QUANTITY && (if (allowZero) value >= 0 else value > 0)
}
