package io.github.funnyhell.daymark

import android.content.Context
import io.github.funnyhell.daymark.data.backup.BackupRepository
import io.github.funnyhell.daymark.data.sync.PairedDevices
import io.github.funnyhell.daymark.data.sync.SyncRepository
import io.github.funnyhell.daymark.platform.sync.SyncCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
class AppContainer(context: Context, scope: CoroutineScope) : DataChangeListener {
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
    val sync = SyncRepository(
        db = database,
        backup = backup,
        time = time,
        appVersion = BuildConfig.VERSION_NAME,
        readSettings = { settings.current() },
        listener = this,
    )
    val syncCoordinator = SyncCoordinator(appContext, settings, sync, PairedDevices(appContext), time, scope)
    val notifier = Notifier(appContext, settings)
    val reminders = ReminderEngine(appContext, habits, medications, settings, notifier, time)

    /**
     * Reminders come first and do not wait for widgets: a notification action has only a few seconds to finish,
     * and drawing several widgets in a freshly started process can take longer than that. The alarm for a snoozed
     * dose must be set even then.
     */
    override suspend fun onDataChanged(change: DataChange) = coroutineScope {
        launch { WidgetUpdater.updateAll(appContext) }
        when (change) {
            is DataChange.Medications -> {
                change.clearedOccurrenceKeys.forEach { notifier.cancelMedication(it) }
                change.lowStockMedicationIds.forEach { id -> medications.get(id)?.let { notifier.showLowStock(it) } }
            }
            DataChange.Habits -> notifier.cancelResolvedHabitReminders(habits.all(), time)
            DataChange.Everything -> notifier.cancelAll()
        }
        reminders.reschedule()
    }
}
