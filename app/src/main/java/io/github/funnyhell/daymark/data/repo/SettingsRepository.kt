package io.github.funnyhell.daymark.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** Sync with paired devices whenever Daymark is open on both (device-local, not exported). */
    val autoSync: Boolean = true,
    /** Folder (a document tree URI) for automatic daily backups, or null when they are off. */
    val backupFolder: String? = null,
    val lastBackupAt: Instant? = null,
    /**
     * An unmarked medication reminder rings again this many minutes after it was shown, until the dose is
     * taken, skipped or snoozed, or its missed window ends; 0 turns repeating off.
     */
    val medicationRepeatMinutes: Int = 5,
    /** Modules the user turned on or off; modules in neither set use their default (see ModuleRegistry). */
    val modulesOn: Set<String> = emptySet(),
    val modulesOff: Set<String> = emptySet(),
    /** Home sections in the order the user arranged them, and the ones the user hid. */
    val homeOrder: List<String> = emptyList(),
    val homeHidden: Set<String> = emptySet(),
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
        val autoSync = booleanPreferencesKey("auto_sync")
        val backupFolder = stringPreferencesKey("backup_folder")
        val lastBackup = longPreferencesKey("last_backup")
        val deviceId = stringPreferencesKey("device_id")
        val medRepeat = intPreferencesKey("medication_repeat_minutes")
        val modulesOn = stringSetPreferencesKey("modules_on")
        val modulesOff = stringSetPreferencesKey("modules_off")
        val homeOrder = stringPreferencesKey("home_order")
        val homeHidden = stringSetPreferencesKey("home_hidden")
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
            autoSync = p[Keys.autoSync] ?: defaults.autoSync,
            backupFolder = p[Keys.backupFolder],
            lastBackupAt = p[Keys.lastBackup]?.let(Instant::ofEpochMilli),
            medicationRepeatMinutes = (p[Keys.medRepeat] ?: defaults.medicationRepeatMinutes).coerceIn(0, 120),
            modulesOn = p[Keys.modulesOn].orEmpty(),
            modulesOff = p[Keys.modulesOff].orEmpty(),
            homeOrder = p[Keys.homeOrder]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            homeHidden = p[Keys.homeHidden].orEmpty(),
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
    suspend fun setMedicationRepeatMinutes(minutes: Int) = context.dataStore.edit { it[Keys.medRepeat] = minutes.coerceIn(0, 120) }
    suspend fun setModuleEnabled(id: String, enabled: Boolean) = context.dataStore.edit {
        it[Keys.modulesOn] = it[Keys.modulesOn].orEmpty().let { set -> if (enabled) set + id else set - id }
        it[Keys.modulesOff] = it[Keys.modulesOff].orEmpty().let { set -> if (enabled) set - id else set + id }
    }

    suspend fun setHomeLayout(order: List<String>, hidden: Set<String>) = context.dataStore.edit {
        it[Keys.homeOrder] = order.joinToString(",")
        it[Keys.homeHidden] = hidden
    }

    suspend fun setAutoSync(enabled: Boolean) = context.dataStore.edit { it[Keys.autoSync] = enabled }
    suspend fun setBackupFolder(uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.backupFolder) else it[Keys.backupFolder] = uri
    }
    suspend fun setLastBackupAt(at: Instant) = context.dataStore.edit { it[Keys.lastBackup] = at.toEpochMilli() }

    /** A random id for this installation, created on first use; other devices recognise it when syncing. */
    suspend fun deviceId(): String {
        context.dataStore.data.first()[Keys.deviceId]?.let { return it }
        var id = ""
        context.dataStore.edit { p ->
            id = p[Keys.deviceId] ?: java.util.UUID.randomUUID().toString().also { p[Keys.deviceId] = it }
        }
        return id
    }

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
