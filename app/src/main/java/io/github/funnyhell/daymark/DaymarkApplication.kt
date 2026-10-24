package io.github.funnyhell.daymark

import android.app.Application
import io.github.funnyhell.daymark.platform.work.MaintenanceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DaymarkApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Process-wide scope for short work started by the app itself (not tied to a screen). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        MaintenanceWorker.schedule(this)
        appScope.launch { container.reminders.runCheck() }
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as DaymarkApplication).container
