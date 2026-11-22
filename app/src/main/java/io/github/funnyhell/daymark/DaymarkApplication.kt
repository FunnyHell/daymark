package io.github.funnyhell.daymark

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
        container = AppContainer(this, appScope)
        container.notifier.createChannels()
        io.github.funnyhell.daymark.module.ModuleRegistry.all.forEach { it.onAppStart(this) }
        MaintenanceWorker.schedule(this)
        appScope.launch { container.reminders.runCheck() }
        registerActivityLifecycleCallbacks(ForegroundTracker { visible ->
            if (visible) container.syncCoordinator.onForeground() else container.syncCoordinator.onBackground()
        })
    }

    /** Reports when the first activity starts and when the last one stops (the app is visible or not). */
    private class ForegroundTracker(private val onChange: (Boolean) -> Unit) : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) onChange(true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (--started == 0) onChange(false)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as DaymarkApplication).container
