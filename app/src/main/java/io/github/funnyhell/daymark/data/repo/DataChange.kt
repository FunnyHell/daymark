package io.github.funnyhell.daymark.data.repo

/** Emitted after a committed write so that platform integrations (reminders, widgets) can refresh. */
sealed interface DataChange {
    data object Habits : DataChange

    data class Medications(
        /** Occurrences whose notification should be removed (resolved or no longer valid). */
        val clearedOccurrenceKeys: List<String> = emptyList(),
        /** Medications whose stock just fell to or below their threshold. */
        val lowStockMedicationIds: List<String> = emptyList(),
    ) : DataChange

    data object Everything : DataChange
}

fun interface DataChangeListener {
    suspend fun onDataChanged(change: DataChange)

    companion object {
        val None = DataChangeListener { }
    }
}
