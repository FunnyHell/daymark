package io.github.funnyhell.daymark.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.funnyhell.daymark.data.db.DELETED_HABIT
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.db.DeletedRecordEntity
import io.github.funnyhell.daymark.data.db.MIGRATION_1_2
import io.github.funnyhell.daymark.data.db.MIGRATION_2_3
import io.github.funnyhell.daymark.data.db.HabitStepEntity
import io.github.funnyhell.daymark.data.db.HabitEventEntity
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Opens a version 1 database (the 0.1.0 schema) with the current Room database and every migration. Room checks
 * after migrating that every table matches the current entities, so a wrong migration fails here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun version1To2_keepsDataAndAddsDeletedRecords() = runBlocking {
        createVersion1()
        val db = Room.databaseBuilder(context, DaymarkDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        try {
            val habits = db.habitDao().habits()
            assertEquals(listOf("h1"), habits.map { it.id })
            assertEquals(1, db.habitDao().events().size)
            assertEquals(1, db.medicationDao().adjustments().size)
            db.deletedRecordDao().insert(DeletedRecordEntity("x", DELETED_HABIT, Instant.ofEpochMilli(1_000)))
            assertEquals(listOf("x"), db.deletedRecordDao().all().map { it.id })
            // Schema 3: steps, a completion of a step, a note on a dose.
            assertEquals(null, db.habitDao().events().single().stepId)
            db.habitDao().insertStep(HabitStepEntity("st1", "h1", "Shower", 1, 0, LocalDate.of(2024, 3, 4), null))
            db.habitDao().insertEvent(HabitEventEntity("e2", "h1", LocalDate.of(2024, 3, 5), "COMPLETION", 1.0, null, Instant.ofEpochMilli(3000), null, "st1"))
            assertEquals("st1", db.habitDao().events().first { it.id == "e2" }.stepId)
            assertEquals(listOf("Shower"), db.habitDao().stepsFor("h1").map { it.title })
        } finally {
            db.close()
        }
    }

    private fun createVersion1() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    VERSION_1_SCHEMA.forEach(db::execSQL)
                    db.execSQL(
                        "INSERT INTO habits VALUES ('h1', 'Read', NULL, 0, 'CHECK', 'ACTIVE', NULL, '2024-03-04', NULL, 1, 0, 1000, 1000)",
                    )
                    db.execSQL("INSERT INTO habit_schedules VALUES ('s1', 'h1', '2024-03-04', NULL, 'DAILY', 0, 0, 1.0)")
                    db.execSQL("INSERT INTO habit_events VALUES ('e1', 'h1', '2024-03-04', 'COMPLETION', 1.0, NULL, 2000, NULL)")
                    db.execSQL(
                        "INSERT INTO medications VALUES ('m1', 'Vitamin D', NULL, NULL, 0, 'ACTIVE', 1, 'tablets', NULL, 1, 1000, 1000)",
                    )
                    db.execSQL("INSERT INTO inventory_adjustments VALUES ('a1', 'm1', 10.0, 'INITIAL', NULL, NULL, 1000)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).also { it.writableDatabase.close() }.close()
    }

    private companion object {
        /** Tables and indices of schema version 1, as Room created them in 0.1.0. */
        val VERSION_1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `habits` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, " +
                "`color_argb` INTEGER NOT NULL, `type` TEXT NOT NULL, `status` TEXT NOT NULL, `unit` TEXT, " +
                "`start_date` TEXT NOT NULL, `reminder_time` TEXT, `show_on_widget` INTEGER NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `habit_schedules` (`id` TEXT NOT NULL, `habit_id` TEXT NOT NULL, " +
                "`effective_from` TEXT NOT NULL, `effective_until` TEXT, `recurrence_kind` TEXT NOT NULL, " +
                "`weekday_mask` INTEGER NOT NULL, `interval_days` INTEGER NOT NULL, `target` REAL NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`habit_id`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_habit_schedules_habit_id` ON `habit_schedules` (`habit_id`)",
            "CREATE TABLE IF NOT EXISTS `habit_events` (`id` TEXT NOT NULL, `habit_id` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `amount` REAL NOT NULL, `note` TEXT, `recorded_at` INTEGER NOT NULL, `voided_at` INTEGER, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`habit_id`) REFERENCES `habits`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_habit_events_habit_id` ON `habit_events` (`habit_id`)",
            "CREATE INDEX IF NOT EXISTS `index_habit_events_date` ON `habit_events` (`date`)",
            "CREATE TABLE IF NOT EXISTS `pauses` (`id` TEXT NOT NULL, `owner_type` TEXT NOT NULL, `owner_id` TEXT NOT NULL, " +
                "`start_date` TEXT NOT NULL, `end_date` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_pauses_owner_id` ON `pauses` (`owner_id`)",
            "CREATE TABLE IF NOT EXISTS `medications` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `strength` TEXT, `notes` TEXT, " +
                "`color_argb` INTEGER NOT NULL, `status` TEXT NOT NULL, `inventory_enabled` INTEGER NOT NULL, " +
                "`inventory_unit` TEXT NOT NULL, `low_stock_threshold` REAL, `low_stock_alerts` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `medication_schedules` (`id` TEXT NOT NULL, `medication_id` TEXT NOT NULL, " +
                "`effective_from` TEXT NOT NULL, `effective_until` TEXT, `weekday_mask` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`medication_id`) REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_medication_schedules_medication_id` ON `medication_schedules` (`medication_id`)",
            "CREATE TABLE IF NOT EXISTS `scheduled_doses` (`id` TEXT NOT NULL, `schedule_id` TEXT NOT NULL, `time` TEXT NOT NULL, " +
                "`dose_label` TEXT NOT NULL, `quantity` REAL NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`schedule_id`) " +
                "REFERENCES `medication_schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_scheduled_doses_schedule_id` ON `scheduled_doses` (`schedule_id`)",
            "CREATE TABLE IF NOT EXISTS `medication_events` (`id` TEXT NOT NULL, `medication_id` TEXT NOT NULL, " +
                "`occurrence_key` TEXT, `dose_id` TEXT, `scheduled_date` TEXT, `scheduled_time` TEXT, `dose_label` TEXT, " +
                "`type` TEXT NOT NULL, `quantity` REAL NOT NULL, `snoozed_until` INTEGER, `source` TEXT NOT NULL, " +
                "`recorded_at` INTEGER NOT NULL, `voided_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`medication_id`) " +
                "REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_medication_events_medication_id` ON `medication_events` (`medication_id`)",
            "CREATE INDEX IF NOT EXISTS `index_medication_events_occurrence_key` ON `medication_events` (`occurrence_key`)",
            "CREATE TABLE IF NOT EXISTS `inventory_adjustments` (`id` TEXT NOT NULL, `medication_id` TEXT NOT NULL, " +
                "`delta` REAL NOT NULL, `type` TEXT NOT NULL, `note` TEXT, `event_id` TEXT, `recorded_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`medication_id`) REFERENCES `medications`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_inventory_adjustments_medication_id` ON `inventory_adjustments` (`medication_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_inventory_adjustments_event_id_type` ON `inventory_adjustments` (`event_id`, `type`)",
        )
    }
}
