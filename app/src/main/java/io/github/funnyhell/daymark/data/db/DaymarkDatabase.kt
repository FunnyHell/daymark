package io.github.funnyhell.daymark.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Schema version history:
 * 1 — initial schema (0.1.0).
 *
 * Every future schema change must add an explicit Migration; destructive migration is never enabled.
 */
@Database(
    entities = [
        HabitEntity::class,
        HabitScheduleEntity::class,
        HabitEventEntity::class,
        PauseEntity::class,
        MedicationEntity::class,
        MedicationScheduleEntity::class,
        ScheduledDoseEntity::class,
        MedicationEventEntity::class,
        InventoryAdjustmentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DaymarkDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao
    abstract fun pauseDao(): PauseDao
    abstract fun medicationDao(): MedicationDao

    companion object {
        const val NAME = "daymark.db"

        fun build(context: Context): DaymarkDatabase =
            Room.databaseBuilder(context.applicationContext, DaymarkDatabase::class.java, NAME)
                .build()
    }
}
