package io.github.funnyhell.daymark.module

import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.modules.breaks.BreaksModule
import io.github.funnyhell.daymark.modules.habits.HabitsModule
import io.github.funnyhell.daymark.modules.medications.MedicationsModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private fun resolve(available: List<String>, order: List<String>, hidden: Set<String> = emptySet()) =
        HomeLayout.resolve(available, { it }, order, hidden)

    @Test
    fun savedOrderWinsAndNewSectionsGoLast() {
        assertEquals(listOf("b", "a", "c"), resolve(listOf("a", "b", "c"), listOf("b", "a")))
    }

    @Test
    fun hiddenAndRemovedSectionsAreLeftOut() {
        assertEquals(listOf("c"), resolve(listOf("a", "c"), listOf("x", "a", "c"), hidden = setOf("a")))
    }

    @Test
    fun defaultsUseModuleDefaultsAndUserChoicesOverrideThem() {
        val defaults = Settings()
        assertTrue(ModuleRegistry.isEnabled(HabitsModule.ID, defaults))
        assertTrue(ModuleRegistry.isEnabled(MedicationsModule.ID, defaults))
        assertFalse(ModuleRegistry.isEnabled(BreaksModule.ID, defaults))
        val changed = defaults.copy(modulesOn = setOf(BreaksModule.ID), modulesOff = setOf(MedicationsModule.ID))
        assertEquals(listOf(HabitsModule.ID, BreaksModule.ID), ModuleRegistry.enabled(changed).map { it.id })
    }

    @Test
    fun moduleAndSectionIdsAreUnique() {
        val modules = ModuleRegistry.all.map { it.id }
        assertEquals(modules.size, modules.toSet().size)
        val sections = ModuleRegistry.all.flatMap { m -> m.homeSections.map { it.id } }
        assertEquals(sections.size, sections.toSet().size)
        val tabs = ModuleRegistry.all.mapNotNull { it.tab?.route }
        assertEquals(tabs.size, tabs.toSet().size)
    }
}
