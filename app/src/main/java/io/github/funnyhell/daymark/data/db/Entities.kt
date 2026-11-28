package io.github.funnyhell.daymark.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    /** [io.github.funnyhell.daymark.domain.habit.HabitType] name. */
    val type: String,
    /** [io.github.funnyhell.daymark.domain.common.LifecycleStatus] name. */
    val status: String,
    val unit: String?,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "reminder_time") val reminderTime: LocalTime?,
    @ColumnInfo(name = "show_on_widget") val showOnWidget: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

@Entity(
    tableName = "habit_schedules",
    foreignKeys = [ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habit_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("habit_id")],
)
data class HabitScheduleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
    @ColumnInfo(name = "effective_until") val effectiveUntil: LocalDate?,
    /** DAILY, WEEKDAYS or EVERY_N_DAYS. */
    @ColumnInfo(name = "recurrence_kind") val recurrenceKind: String,
    /** Bit 0 = Monday ... bit 6 = Sunday; used by WEEKDAYS. */
    @ColumnInfo(name = "weekday_mask") val weekdayMask: Int,
    @ColumnInfo(name = "interval_days") val intervalDays: Int,
    val target: Double,
)

@Entity(
    tableName = "habit_events",
    foreignKeys = [ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habit_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("habit_id"), Index("date")],
)
data class HabitEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    val date: LocalDate,
    val type: String,
    val amount: Double,
    val note: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Instant,
    @ColumnInfo(name = "voided_at") val voidedAt: Instant?,
    /** Checklist step of a completion (schema 3). */
    @ColumnInfo(name = "step_id") val stepId: String? = null,
)

/** Steps of checklist habits (schema 3). A removed step keeps its row with [removedOn] set, so history stays readable. */
@Entity(
    tableName = "habit_steps",
    foreignKeys = [ForeignKey(entity = HabitEntity::class, parentColumns = ["id"], childColumns = ["habit_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("habit_id")],
)
data class HabitStepEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "habit_id") val habitId: String,
    val title: String,
    val target: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "added_on") val addedOn: LocalDate,
    @ColumnInfo(name = "removed_on") val removedOn: LocalDate?,
)

/** Pause and archive intervals for habits and medications (see [owner_type]). */
@Entity(tableName = "pauses", indices = [Index("owner_id")])
data class PauseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "start_date") val start: LocalDate,
    @ColumnInfo(name = "end_date") val end: LocalDate?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val strength: String?,
    val notes: String?,
    @ColumnInfo(name = "color_argb") val colorArgb: Long,
    val status: String,
    @ColumnInfo(name = "inventory_enabled") val inventoryEnabled: Boolean,
    @ColumnInfo(name = "inventory_unit") val inventoryUnit: String,
    @ColumnInfo(name = "low_stock_threshold") val lowStockThreshold: Double?,
    @ColumnInfo(name = "low_stock_alerts") val lowStockAlerts: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

@Entity(
    tableName = "medication_schedules",
    foreignKeys = [ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medication_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medication_id")],
)
data class MedicationScheduleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "medication_id") val medicationId: String,
    @ColumnInfo(name = "effective_from") val effectiveFrom: LocalDate,
    @ColumnInfo(name = "effective_until") val effectiveUntil: LocalDate?,
    @ColumnInfo(name = "weekday_mask") val weekdayMask: Int,
)

@Entity(
    tableName = "scheduled_doses",
    foreignKeys = [ForeignKey(entity = MedicationScheduleEntity::class, parentColumns = ["id"], childColumns = ["schedule_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("schedule_id")],
)
data class ScheduledDoseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: String,
    val time: LocalTime,
    @ColumnInfo(name = "dose_label") val doseLabel: String,
    val quantity: Double,
)

@Entity(
    tableName = "medication_events",
    foreignKeys = [ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medication_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medication_id"), Index("occurrence_key")],
)
data class MedicationEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "medication_id") val medicationId: String,
    @ColumnInfo(name = "occurrence_key") val occurrenceKey: String?,
    @ColumnInfo(name = "dose_id") val doseId: String?,
    @ColumnInfo(name = "scheduled_date") val scheduledDate: LocalDate?,
    @ColumnInfo(name = "scheduled_time") val scheduledTime: LocalTime?,
    @ColumnInfo(name = "dose_label") val doseLabel: String?,
    val type: String,
    val quantity: Double,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: Instant?,
    val source: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Instant,
    @ColumnInfo(name = "voided_at") val voidedAt: Instant?,
    /** The user's note about the dose (schema 3). */
    val note: String? = null,
)

/**
 * Inventory ledger. The unique index on (event_id, type) guarantees that one TAKEN event can produce at most
 * one INTAKE and one INTAKE_REVERSAL row, even if an action is processed twice.
 */
@Entity(
    tableName = "inventory_adjustments",
    foreignKeys = [ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medication_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medication_id"), Index(value = ["event_id", "type"], unique = true)],
)
data class InventoryAdjustmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "medication_id") val medicationId: String,
    val delta: Double,
    val type: String,
    val note: String?,
    @ColumnInfo(name = "event_id") val eventId: String?,
    @ColumnInfo(name = "recorded_at") val recordedAt: Instant,
)

/**
 * A habit or medication deleted on this device. Kept so that device sync can delete the same record on
 * other devices instead of bringing it back (see docs/DECISIONS.md, ADR-030).
 */
@Entity(tableName = "deleted_records")
data class DeletedRecordEntity(
    @PrimaryKey val id: String,
    /** [DELETED_HABIT] or [DELETED_MEDICATION]. */
    val kind: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant,
)

const val DELETED_HABIT = "HABIT"
const val DELETED_MEDICATION = "MEDICATION"
