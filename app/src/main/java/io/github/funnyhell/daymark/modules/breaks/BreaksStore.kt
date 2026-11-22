package io.github.funnyhell.daymark.modules.breaks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The break plan, the last break and today's count. */
data class BreakState(
    val plan: BreakPlan = BreakPlan(),
    val anchor: Instant? = null,
    val countDate: LocalDate? = null,
    val count: Int = 0,
) {
    fun countOn(date: LocalDate): Int = if (countDate == date) count else 0
}

private val Context.breaksStore: DataStore<Preferences> by preferencesDataStore(name = "module_breaks")

/**
 * The module keeps its own small settings file. Breaks are not health records, so they are not part of backups
 * or sync; a module with records worth keeping would store them in Room instead (see docs/MODULES.md).
 */
class BreaksStore(private val context: Context) {
    private object Keys {
        val interval = intPreferencesKey("interval_minutes")
        val from = stringPreferencesKey("from")
        val until = stringPreferencesKey("until")
        val days = stringPreferencesKey("days")
        val anchor = longPreferencesKey("anchor")
        val countDate = stringPreferencesKey("count_date")
        val count = intPreferencesKey("count")
    }

    val state: Flow<BreakState> = context.breaksStore.data.map { p ->
        val d = BreakPlan()
        BreakState(
            plan = BreakPlan(
                intervalMinutes = p[Keys.interval] ?: d.intervalMinutes,
                from = p[Keys.from]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.from,
                until = p[Keys.until]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.until,
                days = p[Keys.days]?.split(',')?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet() ?: d.days,
            ),
            anchor = p[Keys.anchor]?.let(Instant::ofEpochMilli),
            countDate = p[Keys.countDate]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            count = p[Keys.count] ?: 0,
        )
    }

    suspend fun current(): BreakState = state.first()

    suspend fun setPlan(plan: BreakPlan) = context.breaksStore.edit {
        it[Keys.interval] = plan.intervalMinutes
        it[Keys.from] = plan.from.toString()
        it[Keys.until] = plan.until.toString()
        it[Keys.days] = plan.days.joinToString(",") { d -> d.name }
    }

    /** A break was taken at [at] on [date]. */
    suspend fun done(at: Instant, date: LocalDate) = context.breaksStore.edit {
        it[Keys.anchor] = BreakRules.anchorAfterDone(at).toEpochMilli()
        val sameDay = it[Keys.countDate] == date.toString()
        it[Keys.countDate] = date.toString()
        it[Keys.count] = (if (sameDay) it[Keys.count] ?: 0 else 0) + 1
    }

    suspend fun later(at: Instant) {
        val plan = current().plan
        context.breaksStore.edit { it[Keys.anchor] = BreakRules.anchorForLater(plan, at).toEpochMilli() }
    }
}
