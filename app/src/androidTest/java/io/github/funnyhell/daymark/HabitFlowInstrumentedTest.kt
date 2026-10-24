package io.github.funnyhell.daymark

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real UI: onboarding → create a habit → complete it on Today → the completion is persisted. */
@RunWith(AndroidJUnit4::class)
class HabitFlowInstrumentedTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = hasText(label) and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun waitForText(text: String) = rule.waitUntil(10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun createHabitAndCompleteIt() {
        val name = "Read ${System.currentTimeMillis() % 100000}"
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(tab("Habits")).fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Get started").fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText("Get started").onFirst().performClick()
        }
        rule.waitUntil(10_000) { rule.onAllNodes(tab("Habits")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(tab("Habits")).onFirst().performClick()
        waitForText("New habit")
        rule.onAllNodesWithText("New habit").onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction() and hasText("Name")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction() and hasText("Name")).onFirst().performTextInput(name)
        rule.onAllNodesWithText("Save").onFirst().performClick()
        waitForText(name)

        rule.onAllNodes(tab("Today")).onFirst().performClick()
        waitForText(name)
        rule.onAllNodesWithText("Done").onFirst().performClick()

        rule.waitUntil(10_000) {
            runBlocking {
                rule.activity.container.habits.all().firstOrNull { it.habit.name == name }?.events?.isNotEmpty() == true
            }
        }
        val habit = runBlocking { rule.activity.container.habits.all().first { it.habit.name == name } }
        assertEquals(1, habit.events.count { it.type == HabitEventType.COMPLETION && it.isActive })
        runBlocking { rule.activity.container.habits.delete(habit.habit.id) }
    }
}
