package io.github.funnyhell.daymark

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.funnyhell.daymark.domain.medication.DoseDraft
import io.github.funnyhell.daymark.domain.medication.MedicationDraft
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.platform.notifications.Notifier
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two doses due at the same time are one notification; recording one of them elsewhere removes it from that
 * notification, and recording the other removes the notification.
 */
@RunWith(AndroidJUnit4::class)
class GroupNotificationInstrumentedTest {
    @get:Rule
    val notifications: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun groupItems(): List<List<String>> =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { it.tag?.startsWith(Notifier.TAG_MED_GROUP) == true }
            .map { it.notification.extras.getStringArray(Notifier.EXTRA_GROUP_ITEMS)?.toList().orEmpty() }

    /** Posting and cancelling go through the system service, so the result is visible shortly after. */
    private suspend fun awaitGroups(expected: (List<List<String>>) -> Boolean): List<List<String>> {
        repeat(40) {
            val now = groupItems()
            if (expected(now)) return now
            delay(100)
        }
        return groupItems()
    }

    private fun draft(name: String) = MedicationDraft(
        name = name, strength = null, notes = null, colorArgb = 0xFF336699,
        days = DayOfWeek.entries.toSet(), doses = listOf(DoseDraft(LocalTime.of(23, 59), "1 tablet", 1.0)),
        inventoryEnabled = false, inventoryUnit = "tablets", lowStockThreshold = null, lowStockAlerts = false, initialStock = null,
    )

    @Test
    fun groupShrinksAsDosesAreRecorded_andDisappearsWhenEmpty() = runBlocking {
        val c = context.container
        val ids = listOf(c.medications.create(draft("Group A")), c.medications.create(draft("Group B")))
        try {
            val items = ids.map { id ->
                val data = c.medications.get(id)!!
                data to OccurrenceRules.occurrencesOn(data, c.time.today()).single()
            }
            c.notifier.showMedicationGroup(items, items.first().second.dueAt(c.time.zone()))
            assertEquals(listOf(2), awaitGroups { it.map { g -> g.size } == listOf(2) }.map { it.size })

            c.notifier.cancelMedication(items[0].second.key)
            val remaining = awaitGroups { it.map { g -> g.size } == listOf(1) }
            assertEquals(listOf(1), remaining.map { it.size })
            assertTrue(remaining.single().single().endsWith(items[1].second.key))

            c.notifier.cancelMedication(items[1].second.key)
            assertTrue(awaitGroups { it.isEmpty() }.isEmpty())
        } finally {
            ids.forEach { c.medications.delete(it) }
        }
    }
}
