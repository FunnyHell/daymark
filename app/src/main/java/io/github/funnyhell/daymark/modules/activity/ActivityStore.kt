package io.github.funnyhell.daymark.modules.activity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class ActivityState(
    /** Notes on workouts, by Health Connect record id. */
    val notes: Map<String, String> = emptyMap(),
    /** Habit marked done on days with a workout, or null. */
    val linkedHabitId: String? = null,
    /** Workouts that already marked the habit, so a mark the user removed is not added again. */
    val handled: Set<String> = emptySet(),
)

private val Context.activityStore: DataStore<Preferences> by preferencesDataStore(name = "module_activity")

/** The module's own settings and workout notes. Health Connect itself stays the source of the workouts. */
class ActivityStore(private val context: Context) {
    private object Keys {
        val notes = stringPreferencesKey("notes_json")
        val habit = stringPreferencesKey("linked_habit")
        val handled = stringSetPreferencesKey("handled")
    }

    private val notesSerializer = MapSerializer(String.serializer(), String.serializer())

    val state: Flow<ActivityState> = context.activityStore.data.map { p ->
        ActivityState(
            notes = p[Keys.notes]?.let { runCatching { Json.decodeFromString(notesSerializer, it) }.getOrNull() }.orEmpty(),
            linkedHabitId = p[Keys.habit],
            handled = p[Keys.handled].orEmpty(),
        )
    }

    suspend fun current(): ActivityState = state.first()

    suspend fun setNote(workoutId: String, note: String?) = context.activityStore.edit { p ->
        val notes = p[Keys.notes]?.let { runCatching { Json.decodeFromString(notesSerializer, it) }.getOrNull() }.orEmpty()
        val clean = note?.trim()?.take(2000)?.ifEmpty { null }
        p[Keys.notes] = Json.encodeToString(notesSerializer, if (clean == null) notes - workoutId else notes + (workoutId to clean))
    }

    suspend fun setLinkedHabit(habitId: String?) = context.activityStore.edit { p ->
        if (habitId == null) p.remove(Keys.habit) else p[Keys.habit] = habitId
        // Workouts seen before the link was set do not mark the new habit retroactively beyond the last week.
        p[Keys.handled] = emptySet()
    }

    suspend fun addHandled(ids: Set<String>) = context.activityStore.edit { p ->
        // Only the last few hundred ids are kept; older workouts are outside the window that is checked anyway.
        p[Keys.handled] = (p[Keys.handled].orEmpty() + ids).toList().takeLast(500).toSet()
    }
}
