package io.github.funnyhell.daymark.platform.widgets

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the completion control is drawn in list widgets. */
enum class CheckStyle { CIRCLE, RADIO }

/** Accent color of a widget; [SYSTEM] follows the wallpaper colors on Android 12+. */
enum class WidgetAccent { SYSTEM, BLUE, GREEN, ORANGE, PURPLE, RED, MONO }

/** Background color of a widget; [SYSTEM] follows the light or dark theme. Opacity is set separately. */
enum class WidgetBackground { SYSTEM, WHITE, LIGHT_GRAY, DARK_GRAY, BLACK, NAVY, ACCENT }

/**
 * Color of a widget's text. [AUTO] picks dark or light text for the background color; on a mostly transparent
 * background the wallpaper decides what is readable, so [LIGHT] or [DARK] can be chosen instead.
 */
enum class WidgetText { AUTO, LIGHT, DARK }

/** Per-widget settings, chosen in [WidgetConfigActivity] and stored by app widget id. */
data class WidgetPrefs(
    val checkStyle: CheckStyle = CheckStyle.CIRCLE,
    val hideCompleted: Boolean = false,
    /** Habit shown by the streak and calendar widgets; null = all habits (calendar) or none chosen (streak). */
    val habitId: String? = null,
    val accent: WidgetAccent = WidgetAccent.SYSTEM,
    /** Background opacity in percent (0 = fully transparent, 100 = opaque). */
    val backgroundAlpha: Int = 100,
    val background: WidgetBackground = WidgetBackground.SYSTEM,
    val text: WidgetText = WidgetText.AUTO,
)

private val Context.widgetStore: DataStore<Preferences> by preferencesDataStore(name = "widget_prefs")

class WidgetPrefsStore(private val context: Context) {
    private fun key(id: Int, name: String) = "w${id}_$name"

    suspend fun get(appWidgetId: Int): WidgetPrefs = observe(appWidgetId).first()

    /** Current settings of one widget, updated whenever they are saved. */
    fun observe(appWidgetId: Int): Flow<WidgetPrefs> = context.widgetStore.data.map { p -> read(p, appWidgetId) }.distinctUntilChanged()

    private fun read(p: Preferences, appWidgetId: Int): WidgetPrefs {
        val d = WidgetPrefs()
        return WidgetPrefs(
            checkStyle = p[stringPreferencesKey(key(appWidgetId, "check"))]?.let { runCatching { CheckStyle.valueOf(it) }.getOrNull() } ?: d.checkStyle,
            hideCompleted = p[booleanPreferencesKey(key(appWidgetId, "hide_done"))] ?: d.hideCompleted,
            habitId = p[stringPreferencesKey(key(appWidgetId, "habit"))],
            accent = p[stringPreferencesKey(key(appWidgetId, "accent"))]?.let { runCatching { WidgetAccent.valueOf(it) }.getOrNull() } ?: d.accent,
            backgroundAlpha = (p[intPreferencesKey(key(appWidgetId, "bg_alpha"))] ?: d.backgroundAlpha).coerceIn(0, 100),
            background = p[stringPreferencesKey(key(appWidgetId, "bg_color"))]?.let { runCatching { WidgetBackground.valueOf(it) }.getOrNull() } ?: d.background,
            text = p[stringPreferencesKey(key(appWidgetId, "text_color"))]?.let { runCatching { WidgetText.valueOf(it) }.getOrNull() } ?: d.text,
        )
    }

    suspend fun set(appWidgetId: Int, prefs: WidgetPrefs) {
        context.widgetStore.edit { p ->
            p[stringPreferencesKey(key(appWidgetId, "check"))] = prefs.checkStyle.name
            p[booleanPreferencesKey(key(appWidgetId, "hide_done"))] = prefs.hideCompleted
            p[stringPreferencesKey(key(appWidgetId, "accent"))] = prefs.accent.name
            p[intPreferencesKey(key(appWidgetId, "bg_alpha"))] = prefs.backgroundAlpha.coerceIn(0, 100)
            p[stringPreferencesKey(key(appWidgetId, "bg_color"))] = prefs.background.name
            p[stringPreferencesKey(key(appWidgetId, "text_color"))] = prefs.text.name
            val habitKey = stringPreferencesKey(key(appWidgetId, "habit"))
            if (prefs.habitId == null) {
                p.remove(habitKey)
            } else {
                p[habitKey] = prefs.habitId
            }
        }
    }

    suspend fun delete(appWidgetIds: IntArray) {
        context.widgetStore.edit { p ->
            val prefixes = appWidgetIds.map { "w${it}_" }
            p.asMap().keys.filter { k -> prefixes.any { k.name.startsWith(it) } }.forEach {
                @Suppress("UNCHECKED_CAST")
                p.remove(it as Preferences.Key<Any>)
            }
        }
    }
}
