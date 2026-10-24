package io.github.funnyhell.daymark

import android.content.Context
import io.github.funnyhell.daymark.data.backup.BackupRepository
import io.github.funnyhell.daymark.data.db.DaymarkDatabase
import io.github.funnyhell.daymark.data.repo.DataChange
import io.github.funnyhell.daymark.data.repo.DataChangeListener
import io.github.funnyhell.daymark.data.repo.HabitRepository
import io.github.funnyhell.daymark.data.repo.MedicationRepository
import io.github.funnyhell.daymark.data.repo.SettingsRepository
import io.github.funnyhell.daymark.domain.common.TimeSource
import io.github.funnyhell.daymark.platform.notifications.Notifier
import io.github.funnyhell.daymark.platform.reminders.ReminderEngine
import io.github.funnyhell.daymark.platform.widgets.WidgetUpdater

/**
 * Manual dependency container. One instance per process, owned by [DaymarkApplication].
 * It is also the [DataChangeListener] that keeps reminders, notifications and widgets in sync with the database.
 */
class AppContainer(context: Context) : DataChangeListener {
    private val appContext = context.applicationContext

    val time: TimeSource = TimeSource.System
    val database: DaymarkDatabase = DaymarkDatabase.build(appContext)
    val settings = SettingsRepository(appContext)
    val habits = HabitRepository(database, time, listener = this)
    val medications = MedicationRepository(database, time, listener = this)
    val backup = BackupRepository(
        db = database,
        time = time,
        appVersion = BuildConfig.VERSION_NAME,
        readSettings = { settings.current() },
        applySettings = { settings.apply(it) },
        listener = this,
    )
    val notifier = Notifier(appContext, settings)
    val reminders = ReminderEngine(appContext, habits, medications, settings, notifier, time)

    override suspend fun onDataChanged(change: DataChange) {
        when (change) {
            is DataChange.Medications -> {
                change.clearedOccurrenceKeys.forEach { notifier.cancelMedication(it) }
                change.lowStockMedicationId?.let { id -> medications.get(id)?.let { notifier.showLowStock(it) } }
            }
            DataChange.Habits -> notifier.cancelResolvedHabitReminders(habits.all(), time)
            DataChange.Everything -> notifier.cancelAll()
        }
        reminders.reschedule()
        WidgetUpdater.updateAll(appContext)
    }
}
