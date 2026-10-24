package io.github.funnyhell.daymark

import android.Manifest
import android.os.Build
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.funnyhell.daymark.domain.habit.HabitEventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real UI: onboarding → create a habit → complete it on Today → the completion is persisted. */
@RunWith(AndroidJUnit4::class)
class HabitFlowInstrumentedTest {
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Onboarding asks for this permission; granted up front so no system dialog covers the app.
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @get:Rule(order = 1)
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
        rule.onNodeWithTag("save_action").performClick()
        // The editor closes after the save completes; then the tab bar is visible again.
        rule.waitUntil(10_000) { rule.onAllNodes(tab("Today")).fetchSemanticsNodes().isNotEmpty() }
        waitForText(name)

        rule.onAllNodes(tab("Today")).onFirst().performClick()
        waitForText(name)
        // Tapping the ring on the left marks the habit done.
        rule.onAllNodesWithContentDescription("Mark $name done").onFirst().performClick()

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
