package io.github.funnyhell.daymark.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema version history:
 * 1 — initial schema (0.1.0).
 * 2 — `deleted_records` for device sync ([MIGRATION_1_2]).
 * 3 — checklist steps (`habit_steps`, `habit_events.step_id`) and dose notes (`medication_events.note`) ([MIGRATION_2_3]).
 *
 * Every future schema change must add an explicit Migration; destructive migration is never enabled.
 */
@Database(
    entities = [
        HabitEntity::class,
        HabitScheduleEntity::class,
        HabitEventEntity::class,
        HabitStepEntity::class,
        PauseEntity::class,
        MedicationEntity::class,
        MedicationScheduleEntity::class,
        ScheduledDoseEntity::class,
        MedicationEventEntity::class,
        InventoryAdjustmentEntity::class,
        DeletedRecordEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class DaymarkDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao
    abstract fun pauseDao(): PauseDao
    abstract fun medicationDao(): MedicationDao
    abstract fun deletedRecordDao(): DeletedRecordDao

    companion object {
        const val NAME = "daymark.db"

        fun build(context: Context): DaymarkDatabase =
            Room.databaseBuilder(context.applicationContext, DaymarkDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `deleted_records` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`deleted_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habit_steps` (`id` TEXT NOT NULL, `habit_id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`target` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `added_on` TEXT NOT NULL, `removed_on` TEXT, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`habit_id`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_habit_steps_habit_id` ON `habit_steps` (`habit_id`)")
        db.execSQL("ALTER TABLE `habit_events` ADD COLUMN `step_id` TEXT")
        db.execSQL("ALTER TABLE `medication_events` ADD COLUMN `note` TEXT")
    }
}
