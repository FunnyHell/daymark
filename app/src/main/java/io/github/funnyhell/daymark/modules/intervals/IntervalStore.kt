package io.github.funnyhell.daymark.modules.intervals

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.funnyhell.daymark.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// The file name comes from the first version of the module (one "movement break"); kept so data is not lost.
private val Context.intervalStore: DataStore<Preferences> by preferencesDataStore(name = "module_breaks")

/**
 * The module keeps its own small settings file. Interval reminders are not health records, so they are not part
 * of backups or sync; a module with records worth keeping would store them in Room instead (see docs/MODULES.md).
 */
class IntervalStore(private val context: Context) {
    private object Keys {
        val list = stringPreferencesKey("reminders_json")

        // The single break of the first version, moved into the list once.
        val oldInterval = intPreferencesKey("interval_minutes")
        val oldFrom = stringPreferencesKey("from")
        val oldUntil = stringPreferencesKey("until")
        val oldDays = stringPreferencesKey("days")
        val oldAnchor = longPreferencesKey("anchor")
        val oldCountDate = stringPreferencesKey("count_date")
        val oldCount = intPreferencesKey("count")
    }

    val reminders: Flow<List<IntervalReminder>> = context.intervalStore.data.map { p ->
        p[Keys.list]?.let(IntervalCodec::decode) ?: legacy(p)
    }

    suspend fun all(): List<IntervalReminder> = reminders.first()

    suspend fun get(id: String): IntervalReminder? = all().firstOrNull { it.id == id }

    private fun legacy(p: Preferences): List<IntervalReminder> {
        val interval = p[Keys.oldInterval] ?: return emptyList()
        val d = BreakPlan()
        return listOf(
            IntervalReminder(
                id = UUID.randomUUID().toString(),
                title = context.getString(R.string.intervals_default_title),
                plan = BreakPlan(
                    intervalMinutes = interval,
                    from = p[Keys.oldFrom]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.from,
                    until = p[Keys.oldUntil]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.until,
                    days = p[Keys.oldDays]?.split(',')?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet() ?: d.days,
                ),
                anchor = p[Keys.oldAnchor]?.let(Instant::ofEpochMilli),
                countDate = p[Keys.oldCountDate]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                count = p[Keys.oldCount] ?: 0,
            ),
        )
    }

    private suspend fun update(change: (List<IntervalReminder>) -> List<IntervalReminder>) {
        context.intervalStore.edit { p ->
            val current = p[Keys.list]?.let(IntervalCodec::decode) ?: legacy(p)
            p[Keys.list] = IntervalCodec.encode(change(current))
        }
    }

    /** Adds [reminder] or replaces the one with the same id. */
    suspend fun save(reminder: IntervalReminder) = update { list ->
        if (list.any { it.id == reminder.id }) list.map { if (it.id == reminder.id) reminder else it } else list + reminder
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    suspend fun setEnabled(id: String, enabled: Boolean) = update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }

    suspend fun done(id: String, at: Instant, date: LocalDate) = update { list -> list.map { if (it.id == id) it.done(at, date) else it } }

    suspend fun later(id: String, at: Instant) = update { list -> list.map { if (it.id == id) it.later(at) else it } }
}
