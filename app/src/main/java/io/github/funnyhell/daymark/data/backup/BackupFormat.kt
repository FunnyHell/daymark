package io.github.funnyhell.daymark.data.backup

import kotlinx.serialization.Serializable

/**
 * Daymark backup file, format version 1 (see docs/DECISIONS.md, ADR-017).
 * Dates are ISO-8601 local dates, times are "HH:mm", instants are ISO-8601 UTC timestamps.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT_ID,
    val formatVersion: Int = CURRENT_VERSION,
    val exportedAt: String,
    val appVersion: String,
    val settings: BackupSettings,
    val habits: List<BackupHabit> = emptyList(),
    val habitSchedules: List<BackupHabitSchedule> = emptyList(),
    val habitEvents: List<BackupHabitEvent> = emptyList(),
    val pauses: List<BackupPause> = emptyList(),
    val medications: List<BackupMedication> = emptyList(),
    val medicationSchedules: List<BackupMedicationSchedule> = emptyList(),
    val scheduledDoses: List<BackupDose> = emptyList(),
    val medicationEvents: List<BackupMedicationEvent> = emptyList(),
    val inventoryAdjustments: List<BackupAdjustment> = emptyList(),
) {
    companion object {
        const val FORMAT_ID = "daymark-backup"
        const val CURRENT_VERSION = 1
    }
}

@Serializable
data class BackupSettings(
    val themeMode: String,
    val dynamicColor: Boolean,
    val firstDayOfWeek: String,
    val medicationReminders: Boolean,
    val habitReminders: Boolean,
    val hideNamesOnLockScreen: Boolean,
)

@Serializable
data class BackupHabit(
    val id: String,
    val name: String,
    val description: String? = null,
    val colorArgb: Long,
    val type: String,
    val status: String,
    val unit: String? = null,
    val startDate: String,
    val reminderTime: String? = null,
    val showOnWidget: Boolean,
    val sortOrder: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupHabitSchedule(
    val id: String,
    val habitId: String,
    val effectiveFrom: String,
    val effectiveUntil: String? = null,
    val recurrenceKind: String,
    val weekdayMask: Int,
    val intervalDays: Int,
    val target: Double,
)

@Serializable
data class BackupHabitEvent(
    val id: String,
    val habitId: String,
    val date: String,
    val type: String,
    val amount: Double,
    val note: String? = null,
    val recordedAt: String,
    val voidedAt: String? = null,
)

@Serializable
data class BackupPause(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val start: String,
    val end: String? = null,
    val createdAt: String,
)

@Serializable
data class BackupMedication(
    val id: String,
    val name: String,
    val strength: String? = null,
    val notes: String? = null,
    val colorArgb: Long,
    val status: String,
    val inventoryEnabled: Boolean,
    val inventoryUnit: String,
    val lowStockThreshold: Double? = null,
    val lowStockAlerts: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupMedicationSchedule(
    val id: String,
    val medicationId: String,
    val effectiveFrom: String,
    val effectiveUntil: String? = null,
    val weekdayMask: Int,
)

@Serializable
data class BackupDose(
    val id: String,
    val scheduleId: String,
    val time: String,
    val doseLabel: String,
    val quantity: Double,
)

@Serializable
data class BackupMedicationEvent(
    val id: String,
    val medicationId: String,
    val occurrenceKey: String? = null,
    val doseId: String? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val doseLabel: String? = null,
    val type: String,
    val quantity: Double,
    val snoozedUntil: String? = null,
    val source: String,
    val recordedAt: String,
    val voidedAt: String? = null,
)

@Serializable
data class BackupAdjustment(
    val id: String,
    val medicationId: String,
    val delta: Double,
    val type: String,
    val note: String? = null,
    val eventId: String? = null,
    val recordedAt: String,
)
