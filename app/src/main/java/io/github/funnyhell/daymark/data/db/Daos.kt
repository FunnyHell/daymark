package io.github.funnyhell.daymark.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sort_order, created_at")
    fun observeHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habit_schedules ORDER BY effective_from")
    fun observeSchedules(): Flow<List<HabitScheduleEntity>>

    @Query("SELECT * FROM habit_events ORDER BY recorded_at")
    fun observeEvents(): Flow<List<HabitEventEntity>>

    @Query("SELECT * FROM habits ORDER BY sort_order, created_at")
    suspend fun habits(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun habit(id: String): HabitEntity?

    @Query("SELECT * FROM habit_schedules ORDER BY effective_from")
    suspend fun schedules(): List<HabitScheduleEntity>

    @Query("SELECT * FROM habit_schedules WHERE habit_id = :habitId ORDER BY effective_from")
    suspend fun schedulesFor(habitId: String): List<HabitScheduleEntity>

    @Query("SELECT * FROM habit_events ORDER BY recorded_at")
    suspend fun events(): List<HabitEventEntity>

    @Query("SELECT * FROM habit_events WHERE habit_id = :habitId ORDER BY recorded_at")
    suspend fun eventsFor(habitId: String): List<HabitEventEntity>

    @Query("SELECT * FROM habit_events WHERE id = :id")
    suspend fun event(id: String): HabitEventEntity?

    @Query("SELECT * FROM habit_steps ORDER BY sort_order")
    fun observeSteps(): Flow<List<HabitStepEntity>>

    @Query("SELECT * FROM habit_steps ORDER BY sort_order")
    suspend fun steps(): List<HabitStepEntity>

    @Query("SELECT * FROM habit_steps WHERE habit_id = :habitId ORDER BY sort_order")
    suspend fun stepsFor(habitId: String): List<HabitStepEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStep(step: HabitStepEntity)

    @Update
    suspend fun updateStep(step: HabitStepEntity)

    @Query("DELETE FROM habit_steps WHERE id = :id")
    suspend fun deleteStep(id: String)

    @Insert
    suspend fun insertSteps(items: List<HabitStepEntity>)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM habits")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHabit(habit: HabitEntity)

    @Update
    suspend fun updateHabit(habit: HabitEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedule(schedule: HabitScheduleEntity)

    @Update
    suspend fun updateSchedule(schedule: HabitScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: HabitEventEntity)

    @Query("UPDATE habit_events SET voided_at = :voidedAt WHERE id = :id AND voided_at IS NULL")
    suspend fun voidEvent(id: String, voidedAt: Instant): Int

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun deleteHabit(id: String)

    @Insert
    suspend fun insertHabits(items: List<HabitEntity>)

    @Insert
    suspend fun insertSchedules(items: List<HabitScheduleEntity>)

    @Insert
    suspend fun insertEvents(items: List<HabitEventEntity>)

    @Query("DELETE FROM habits")
    suspend fun deleteAllHabits()
}

@Dao
interface PauseDao {
    @Query("SELECT * FROM pauses ORDER BY start_date")
    fun observePauses(): Flow<List<PauseEntity>>

    @Query("SELECT * FROM pauses ORDER BY start_date")
    suspend fun pauses(): List<PauseEntity>

    @Query("SELECT * FROM pauses WHERE owner_id = :ownerId ORDER BY start_date")
    suspend fun pausesFor(ownerId: String): List<PauseEntity>

    @Query("SELECT * FROM pauses WHERE owner_id = :ownerId AND end_date IS NULL LIMIT 1")
    suspend fun openPause(ownerId: String): PauseEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pause: PauseEntity)

    @Insert
    suspend fun insertAll(items: List<PauseEntity>)

    @Query("UPDATE pauses SET end_date = :end WHERE id = :id")
    suspend fun close(id: String, end: LocalDate)

    @Query("DELETE FROM pauses WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pauses WHERE owner_id = :ownerId")
    suspend fun deleteFor(ownerId: String)

    @Query("DELETE FROM pauses")
    suspend fun deleteAll()
}

@Dao
interface MedicationDao {
    @Query("UPDATE medication_events SET note = :note WHERE id = :id")
    suspend fun setEventNote(id: String, note: String?): Int

    @Query("SELECT * FROM medications ORDER BY created_at")
    fun observeMedications(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medication_schedules ORDER BY effective_from")
    fun observeSchedules(): Flow<List<MedicationScheduleEntity>>

    @Query("SELECT * FROM scheduled_doses ORDER BY time")
    fun observeDoses(): Flow<List<ScheduledDoseEntity>>

    @Query("SELECT * FROM medication_events ORDER BY recorded_at")
    fun observeEvents(): Flow<List<MedicationEventEntity>>

    @Query("SELECT * FROM inventory_adjustments ORDER BY recorded_at")
    fun observeAdjustments(): Flow<List<InventoryAdjustmentEntity>>

    @Query("SELECT * FROM medications ORDER BY created_at")
    suspend fun medications(): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun medication(id: String): MedicationEntity?

    @Query("SELECT * FROM medication_schedules ORDER BY effective_from")
    suspend fun schedules(): List<MedicationScheduleEntity>

    @Query("SELECT * FROM medication_schedules WHERE medication_id = :medicationId ORDER BY effective_from")
    suspend fun schedulesFor(medicationId: String): List<MedicationScheduleEntity>

    @Query("SELECT * FROM scheduled_doses ORDER BY time")
    suspend fun doses(): List<ScheduledDoseEntity>

    @Query("SELECT d.* FROM scheduled_doses d JOIN medication_schedules s ON d.schedule_id = s.id WHERE s.medication_id = :medicationId ORDER BY d.time")
    suspend fun dosesFor(medicationId: String): List<ScheduledDoseEntity>

    @Query("SELECT * FROM medication_events ORDER BY recorded_at")
    suspend fun events(): List<MedicationEventEntity>

    @Query("SELECT * FROM medication_events WHERE medication_id = :medicationId ORDER BY recorded_at")
    suspend fun eventsFor(medicationId: String): List<MedicationEventEntity>

    @Query("SELECT * FROM medication_events WHERE id = :id")
    suspend fun event(id: String): MedicationEventEntity?

    @Query("SELECT * FROM inventory_adjustments ORDER BY recorded_at")
    suspend fun adjustments(): List<InventoryAdjustmentEntity>

    @Query("SELECT * FROM inventory_adjustments WHERE medication_id = :medicationId ORDER BY recorded_at")
    suspend fun adjustmentsFor(medicationId: String): List<InventoryAdjustmentEntity>

    @Query("SELECT * FROM inventory_adjustments WHERE event_id = :eventId AND type = :type LIMIT 1")
    suspend fun adjustmentForEvent(eventId: String, type: String): InventoryAdjustmentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMedication(medication: MedicationEntity)

    @Update
    suspend fun updateMedication(medication: MedicationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSchedule(schedule: MedicationScheduleEntity)

    @Update
    suspend fun updateSchedule(schedule: MedicationScheduleEntity)

    @Query("DELETE FROM medication_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDoses(doses: List<ScheduledDoseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(event: MedicationEventEntity)

    @Query("UPDATE medication_events SET voided_at = :voidedAt WHERE id = :id AND voided_at IS NULL")
    suspend fun voidEvent(id: String, voidedAt: Instant): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAdjustment(adjustment: InventoryAdjustmentEntity)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMedication(id: String)

    @Insert
    suspend fun insertMedications(items: List<MedicationEntity>)

    @Insert
    suspend fun insertSchedules(items: List<MedicationScheduleEntity>)

    @Insert
    suspend fun insertEvents(items: List<MedicationEventEntity>)

    @Insert
    suspend fun insertAdjustments(items: List<InventoryAdjustmentEntity>)

    @Query("DELETE FROM medications")
    suspend fun deleteAllMedications()
}

@Dao
interface DeletedRecordDao {
    @Query("SELECT * FROM deleted_records ORDER BY deleted_at")
    suspend fun all(): List<DeletedRecordEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: DeletedRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<DeletedRecordEntity>)

    @Query("DELETE FROM deleted_records")
    suspend fun deleteAll()
}
