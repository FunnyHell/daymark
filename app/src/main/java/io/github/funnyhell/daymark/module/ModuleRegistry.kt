package io.github.funnyhell.daymark.module

import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.modules.intervals.IntervalsModule
import io.github.funnyhell.daymark.modules.activity.ActivityModule
import io.github.funnyhell.daymark.modules.habits.HabitsModule
import io.github.funnyhell.daymark.modules.medications.MedicationsModule
import io.github.funnyhell.daymark.modules.outdoors.OutdoorsModule

/** Every module of the app, in the order of the module list and the bottom bar. A new module is added here. */
object ModuleRegistry {
    val all: List<DaymarkModule> = listOf(HabitsModule, MedicationsModule, IntervalsModule, ActivityModule, OutdoorsModule)

    fun byId(id: String): DaymarkModule? = all.firstOrNull { it.id == id }

    fun isEnabled(module: DaymarkModule, settings: Settings): Boolean = when (module.id) {
        in settings.modulesOn -> true
        in settings.modulesOff -> false
        else -> module.defaultEnabled
    }

    fun enabled(settings: Settings): List<DaymarkModule> = all.filter { isEnabled(it, settings) }

    fun isEnabled(id: String, settings: Settings): Boolean = byId(id)?.let { isEnabled(it, settings) } ?: false
}

/** The Home screen layout: which sections are shown and in what order. */
object HomeLayout {
    /**
     * The sections to show, given the sections of enabled modules in their default order, the order the user
     * saved and the sections the user hid. Sections the user has not placed yet (a module turned on later) are
     * added at the end; saved ids of sections that no longer exist are ignored.
     */
    fun <T> resolve(available: List<T>, id: (T) -> String, savedOrder: List<String>, hidden: Set<String>): List<T> {
        val byId = available.associateBy(id)
        val placed = savedOrder.mapNotNull { byId[it] }
        val rest = available.filter { id(it) !in savedOrder }
        return (placed + rest).filter { id(it) !in hidden }
    }

    /** Full order (shown and hidden) for the editor, with the same rules as [resolve]. */
    fun <T> order(available: List<T>, id: (T) -> String, savedOrder: List<String>): List<T> =
        resolve(available, id, savedOrder, emptySet())
}
