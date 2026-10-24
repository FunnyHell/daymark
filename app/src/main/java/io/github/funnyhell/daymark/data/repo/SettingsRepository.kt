package io.github.funnyhell.daymark.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.DayOfWeek
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val medicationReminders: Boolean = true,
    val habitReminders: Boolean = true,
    /** Lock-screen notifications show a generic text instead of medication and habit names. */
    val hideNamesOnLockScreen: Boolean = true,
    val onboardingDone: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val weekStart = stringPreferencesKey("first_day_of_week")
        val medReminders = booleanPreferencesKey("medication_reminders")
        val habitReminders = booleanPreferencesKey("habit_reminders")
        val hideNames = booleanPreferencesKey("hide_names_lock_screen")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val lastReminderCheck = longPreferencesKey("last_reminder_check")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val defaults = Settings()
        Settings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
            dynamicColor = p[Keys.dynamic] ?: defaults.dynamicColor,
            firstDayOfWeek = p[Keys.weekStart]?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: defaults.firstDayOfWeek,
            medicationReminders = p[Keys.medReminders] ?: defaults.medicationReminders,
            habitReminders = p[Keys.habitReminders] ?: defaults.habitReminders,
            hideNamesOnLockScreen = p[Keys.hideNames] ?: defaults.hideNamesOnLockScreen,
            onboardingDone = p[Keys.onboarding] ?: defaults.onboardingDone,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.dataStore.edit { it[Keys.dynamic] = enabled }
    suspend fun setFirstDayOfWeek(day: DayOfWeek) = context.dataStore.edit { it[Keys.weekStart] = day.name }
    suspend fun setMedicationReminders(enabled: Boolean) = context.dataStore.edit { it[Keys.medReminders] = enabled }
    suspend fun setHabitReminders(enabled: Boolean) = context.dataStore.edit { it[Keys.habitReminders] = enabled }
    suspend fun setHideNamesOnLockScreen(enabled: Boolean) = context.dataStore.edit { it[Keys.hideNames] = enabled }
    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.onboarding] = true }

    /** Applies imported settings in one edit. */
    suspend fun apply(settings: Settings) = context.dataStore.edit {
        it[Keys.theme] = settings.themeMode.name
        it[Keys.dynamic] = settings.dynamicColor
        it[Keys.weekStart] = settings.firstDayOfWeek.name
        it[Keys.medReminders] = settings.medicationReminders
        it[Keys.habitReminders] = settings.habitReminders
        it[Keys.hideNames] = settings.hideNamesOnLockScreen
    }

    suspend fun lastReminderCheck(): Instant? =
        context.dataStore.data.first()[Keys.lastReminderCheck]?.let(Instant::ofEpochMilli)

    suspend fun setLastReminderCheck(at: Instant) = context.dataStore.edit { it[Keys.lastReminderCheck] = at.toEpochMilli() }
}
