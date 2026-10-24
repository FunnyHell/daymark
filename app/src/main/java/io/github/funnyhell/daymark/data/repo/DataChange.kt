package io.github.funnyhell.daymark.data.repo

/** Emitted after a committed write so that platform integrations (reminders, widgets) can refresh. */
sealed interface DataChange {
    data object Habits : DataChange

    data class Medications(
        /** Occurrences whose notification should be removed (resolved or no longer valid). */
        val clearedOccurrenceKeys: List<String> = emptyList(),
        /** Medication whose stock just fell to or below its threshold. */
        val lowStockMedicationId: String? = null,
    ) : DataChange

    data object Everything : DataChange
}

fun interface DataChangeListener {
    suspend fun onDataChanged(change: DataChange)

    companion object {
        val None = DataChangeListener { }
    }
}
