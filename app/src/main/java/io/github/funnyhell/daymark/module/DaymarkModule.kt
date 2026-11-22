package io.github.funnyhell.daymark.module

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraphBuilder
import io.github.funnyhell.daymark.ui.common.UiMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A feature of Daymark that the user can turn on or off (Settings → Modules) and whose sections can be placed on
 * the Home screen. A module brings its own screens, Home sections, settings and reminders; turning it off hides
 * all of them and stops its reminders, while its data stays where it is. See docs/MODULES.md.
 *
 * Every member except [id], [title], [description] and [icon] is optional.
 */
interface DaymarkModule {
    /** Stable id, stored in settings; never change it once released. Lowercase letters, digits and '-'. */
    val id: String

    @get:StringRes val title: Int

    /** One line for the module list. */
    @get:StringRes val description: Int

    val icon: ModuleIcon

    /** Whether the module is on before the user changed anything. */
    val defaultEnabled: Boolean get() = true

    /** A tab in the bottom bar, shown while the module is on. */
    val tab: ModuleTab? get() = null

    /** Sections the module can show on Home, in their default order. */
    val homeSections: List<HomeSection> get() = emptyList()

    /** Route of the module's own settings screen (registered in [screens]), linked from the module list. */
    val settingsRoute: String? get() = null

    /** Reminders the module wants on top of the built-in ones, or null. */
    val reminders: ModuleReminders? get() = null

    /** Called once when the app process starts, whether the module is on or not (for example to create channels). */
    fun onAppStart(context: Context) {}

    /** Called after the user turned the module off; remove its notifications here. */
    suspend fun onDisabled(context: Context) {}

    /** Registers the module's screens in the app's navigation graph. Routes should start with the module id. */
    fun NavGraphBuilder.screens(nav: ModuleNavigator) {}
}

/** An icon from the Material set or from the app's drawables. */
sealed interface ModuleIcon {
    data class Vector(val image: ImageVector) : ModuleIcon
    data class Drawable(@DrawableRes val res: Int) : ModuleIcon
}

/** A bottom-bar tab: its screen, and an optional floating action button. */
interface ModuleTab {
    /** Route of the tab inside the tab bar; must be unique among modules. */
    val route: String

    @get:StringRes val label: Int

    /** Label and action of the floating button on this tab, or null for none. */
    val fab: ModuleFab? get() = null

    @Composable
    fun Content(host: ModuleHost)
}

data class ModuleFab(@StringRes val label: Int, val onClick: (ModuleNavigator) -> Unit)

/**
 * One section of the Home screen. Sections are drawn in a single scrolling list, so a section adds list items
 * instead of drawing a whole screen: [rememberItems] collects the section's state in composition and returns
 * the items to add for it.
 */
interface HomeSection {
    /** Stable id, unique across all modules; by convention "<module id>.<name>". */
    val id: String

    @get:StringRes val title: Int

    @Composable
    fun rememberItems(host: HomeHost): LazyListScope.() -> Unit
}

/** What a tab screen gets from the app. */
interface ModuleHost {
    val navigator: ModuleNavigator

    /** Padding of the surrounding scaffold (top bar and bottom bar). */
    val contentPadding: androidx.compose.foundation.layout.PaddingValues
}

/** What a Home section gets: the shown day (today, or an earlier day the user opened) and navigation. */
interface HomeHost {
    val navigator: ModuleNavigator
    val day: LocalDate
    val isToday: Boolean
}

/** Navigation and messages, shared by all modules. */
interface ModuleNavigator {
    fun navigate(route: String)

    /** Leaves the screen of [from] (does nothing if it is not on top any more, so a double tap cannot pop twice). */
    fun back(from: androidx.navigation.NavBackStackEntry)

    suspend fun showMessage(message: UiMessage)
}

/**
 * Reminders of a module. The app keeps one alarm for all reminders (see ReminderEngine): it asks every enabled
 * module for its upcoming reminders, arms the alarm for the earliest, and calls [show] when one is due.
 */
interface ModuleReminders {
    /** Upcoming reminders from [now], for about two days. [ModuleReminder.key] identifies a reminder in [show]. */
    suspend fun plan(context: Context, now: Instant, zone: ZoneId): List<ModuleReminder>

    /** Shows the reminder (normally a notification). Called at most once per reminder. */
    suspend fun show(context: Context, reminder: ModuleReminder)
}

data class ModuleReminder(val key: String, val at: Instant)
