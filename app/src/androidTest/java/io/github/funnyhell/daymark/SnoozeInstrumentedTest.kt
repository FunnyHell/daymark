package io.github.funnyhell.daymark

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.DoseDraft
import io.github.funnyhell.daymark.domain.medication.MedicationDraft
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.platform.notifications.NotificationActionReceiver
import io.github.funnyhell.daymark.platform.notifications.Notifier
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Snoozing from a notification sets the alarm for the end of the snooze, and when the snooze ends the reminder
 * is shown again.
 */
@RunWith(AndroidJUnit4::class)
class SnoozeInstrumentedTest {
    @get:Rule
    val notifications: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun draft(name: String) = MedicationDraft(
        name = name, strength = null, notes = null, colorArgb = 0xFF336699,
        days = DayOfWeek.entries.toSet(), doses = listOf(DoseDraft(LocalTime.MIDNIGHT, "1 tablet", 1.0)),
        inventoryEnabled = false, inventoryUnit = "tablets", lowStockThreshold = null, lowStockAlerts = false, initialStock = null,
    )

    private suspend fun todaysKey(id: String): String {
        val c = context.container
        return OccurrenceRules.occurrencesOn(c.medications.get(id)!!, c.time.today()).single().key
    }

    @Test
    fun snoozeAllFromNotification_setsTheAlarmForTheSnoozeEnd() = runBlocking {
        val c = context.container
        val ids = listOf(c.medications.create(draft("Snooze A")), c.medications.create(draft("Snooze B")))
        try {
            val items = ids.map { "$it|${todaysKey(it)}" }.toTypedArray()
            context.sendBroadcast(
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_SNOOZE_ALL)
                    .putExtra(NotificationActionReceiver.EXTRA_ITEMS, items)
                    .putExtra(NotificationActionReceiver.EXTRA_TAG, "medgroup:test"),
            )
            var ends = emptyList<java.time.Instant?>()
            for (i in 0 until 50) {
                ends = ids.map { id -> c.medications.get(id)!!.events.firstOrNull { it.type == MedicationEventType.SNOOZED }?.snoozedUntil }
                if (ends.none { it == null }) break
                delay(200)
            }
            assertTrue("both doses snoozed", ends.none { it == null })
            assertEquals("both doses come back at the same moment", ends[0], ends[1])
            // The receiver arms the alarm right after recording the snooze.
            val alarms = context.getSystemService(AlarmManager::class.java)
            var trigger: Long? = null
            for (i in 0 until 25) {
                trigger = alarms.nextAlarmClock?.triggerTime
                if (trigger != null && trigger <= ends[0]!!.toEpochMilli()) break
                delay(200)
            }
            assertNotNull("an alarm is set", trigger)
            assertTrue("the alarm is not later than the snooze end", trigger!! <= ends[0]!!.toEpochMilli())
        } finally {
            ids.forEach { c.medications.delete(it) }
        }
    }

    @Test
    fun skipAllFromGroupNotification_skipsEveryDose() = runBlocking {
        val c = context.container
        val ids = listOf(c.medications.create(draft("Skip A")), c.medications.create(draft("Skip B")))
        try {
            val items = ids.map { "$it|${todaysKey(it)}" }.toTypedArray()
            context.sendBroadcast(
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_SKIP_ALL)
                    .putExtra(NotificationActionReceiver.EXTRA_ITEMS, items)
                    .putExtra(NotificationActionReceiver.EXTRA_TAG, "medgroup:test"),
            )
            var skipped = 0
            for (i in 0 until 50) {
                skipped = ids.count { id -> c.medications.get(id)!!.events.any { it.type == MedicationEventType.SKIPPED && it.isActive } }
                if (skipped == 2) break
                delay(200)
            }
            assertEquals(2, skipped)
        } finally {
            ids.forEach { c.medications.delete(it) }
        }
    }

    @Test
    fun reminderIsShownAgainWhenTheSnoozeEnds() = runBlocking {
        val c = context.container
        val id = c.medications.create(draft("Snooze C"))
        try {
            val key = todaysKey(id)
            c.reminders.runCheck()
            c.medications.snooze(id, key, ActionSource.NOTIFICATION, Duration.ofSeconds(2))
            val manager = context.getSystemService(NotificationManager::class.java)
            fun shown() = manager.activeNotifications.any { it.tag == Notifier.TAG_MED + key }
            assertTrue("no reminder while snoozed", !shown())
            delay(2_500)
            c.reminders.runCheck()
            var visible = false
            for (i in 0 until 25) {
                visible = shown()
                if (visible) break
                delay(200)
            }
            assertTrue("the reminder is back after the snooze", visible)
        } finally {
            c.medications.delete(id)
        }
    }
}
