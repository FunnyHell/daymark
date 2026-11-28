package io.github.funnyhell.daymark.modules.activity

import android.content.Context
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.module.DaymarkModule
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleHost
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleTab

/**
 * Activity from Health Connect: steps and workouts recorded by Samsung Health or other apps, notes on workouts,
 * and optionally a habit marked done on days with a workout. Reads only, while the app is open. Off by default.
 */
object ActivityModule : DaymarkModule {
    const val ID = "activity"
    const val ROUTE = "activity"
    const val SCREEN_ROUTE = "activity/screen"
    private const val TAG = "DaymarkActivity"

    override val id = ID
    override val title = R.string.module_activity
    override val description = R.string.module_activity_desc
    override val icon = ModuleIcon.Vector(Icons.Filled.Favorite)
    override val defaultEnabled = false
    override val settingsRoute = SCREEN_ROUTE
    override val homeSections: List<HomeSection> = listOf(ActivityDaySection)

    override val tab = object : ModuleTab {
        override val route = ROUTE
        override val label = R.string.tab_activity

        @Composable
        override fun Content(host: ModuleHost) = ActivityScreen(host.contentPadding, host.navigator)
    }

    override fun NavGraphBuilder.screens(nav: ModuleNavigator) {
        composable(SCREEN_ROUTE) { entry -> ActivityStandaloneScreen(nav, onBack = { nav.back(entry) }) }
    }

    /** Marks the linked habit for workouts of the last week that were not used yet. */
    override suspend fun onForeground(context: Context) = markLinkedHabit(context)

    suspend fun markLinkedHabit(context: Context) {
        val store = ActivityStore(context)
        val state = store.current()
        val habitId = state.linkedHabitId ?: return
        val data = HealthData(context)
        if (data.access() != HealthAccess.GRANTED) return
        val c = context.container
        val today = c.time.today()
        val workouts = try {
            data.workouts(today.minusDays(6), today, c.time.zone())
        } catch (e: Exception) {
            Log.w(TAG, "Reading workouts failed", e)
            return
        }
        val days = ActivityRules.daysToMark(workouts, state.handled, c.time.zone())
        for ((day, ids) in days) {
            c.habits.setDone(habitId, done = true, date = day)
            store.addHandled(ids.toSet())
        }
    }

    /** A readable name for a Health Connect exercise type. */
    fun typeName(context: Context, type: Int): String = context.getString(
        when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> R.string.workout_walking
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> R.string.workout_running
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> R.string.workout_cycling
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> R.string.workout_swimming
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> R.string.workout_strength
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> R.string.workout_yoga
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> R.string.workout_hiking
            else -> R.string.workout_other
        },
    )
}
