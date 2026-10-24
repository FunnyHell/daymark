package io.github.funnyhell.daymark.platform.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.platform.widgets.WidgetUpdater
import java.util.concurrent.TimeUnit

/**
 * Deferrable safety net: periodically re-arms the reminder alarm and refreshes widgets, in case the alarm was
 * dropped (for example after the app was force-stopped). It is not used for timing reminders.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.reminders.runCheck()
        WidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "daymark-maintenance"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
