package io.github.funnyhell.daymark.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleRegistry

/** A screen that can be a bottom-bar tab: the tab of an enabled module, or History. Home is always the first tab. */
data class TabSpec(val route: String, val label: Int, val icon: ModuleIcon)

object AppTabs {
    const val HOME = "home"
    const val HISTORY = "history"

    /** Home plus at most this many tabs fit the bottom bar. */
    const val MAX = 4

    /** Every screen that can be placed in the bottom bar, in the default order. */
    fun available(settings: Settings): List<TabSpec> =
        ModuleRegistry.enabled(settings).mapNotNull { m -> m.tab?.let { TabSpec(it.route, it.label, m.icon) } } +
            TabSpec(HISTORY, R.string.tab_history, ModuleIcon.Vector(Icons.Filled.DateRange))

    /**
     * The tabs after Home: the user's choice (tabs of modules turned off are left out), or until the user chooses,
     * the available ones in default order up to [MAX].
     */
    fun chosen(settings: Settings): List<TabSpec> {
        val available = available(settings)
        val saved = settings.tabs ?: return available.take(MAX)
        val byRoute = available.associateBy { it.route }
        return saved.mapNotNull { byRoute[it] }.take(MAX)
    }

    /** Available screens that are not in the bottom bar; Home links to them. */
    fun notInBar(settings: Settings): List<TabSpec> {
        val inBar = chosen(settings).map { it.route }.toSet()
        return available(settings).filter { it.route !in inBar }
    }
}
