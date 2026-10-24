package io.github.funnyhell.daymark

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.funnyhell.daymark.domain.medication.DoseDraft
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationDraft
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.platform.notifications.NotificationActionReceiver
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sends the real notification-action broadcasts to the real receiver, twice, as a duplicated delivery would.
 * The dose must be recorded once and the stock decremented once.
 */
@RunWith(AndroidJUnit4::class)
class NotificationActionInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun duplicatedTakenBroadcast_recordsOnceAndConsumesStockOnce() = runBlocking {
        val c = context.container
        val id = c.medications.create(
            MedicationDraft(
                name = "Instrumented med", strength = null, notes = null, colorArgb = 0xFF336699,
                days = DayOfWeek.entries.toSet(), doses = listOf(DoseDraft(LocalTime.MIDNIGHT, "1 tablet", 1.0)),
                inventoryEnabled = true, inventoryUnit = "tablets", lowStockThreshold = null, lowStockAlerts = false, initialStock = 10.0,
            ),
        )
        val key = OccurrenceRules.occurrencesOn(c.medications.get(id)!!, c.time.today()).single().key
        repeat(2) {
            context.sendBroadcast(
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_TAKEN)
                    .putExtra(NotificationActionReceiver.EXTRA_MEDICATION_ID, id)
                    .putExtra(NotificationActionReceiver.EXTRA_OCCURRENCE_KEY, key),
            )
        }
        var waited = 0
        while (c.medications.get(id)!!.events.none { it.type == MedicationEventType.TAKEN } && waited < 10_000) {
            delay(200)
            waited += 200
        }
        // A snooze arriving after the dose was taken must not change anything.
        context.sendBroadcast(
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_SNOOZE)
                .putExtra(NotificationActionReceiver.EXTRA_MEDICATION_ID, id)
                .putExtra(NotificationActionReceiver.EXTRA_OCCURRENCE_KEY, key),
        )
        delay(2_000)
        val data = c.medications.get(id)!!
        assertEquals(1, data.events.count { it.type == MedicationEventType.TAKEN && it.isActive })
        assertEquals(0, data.events.count { it.type == MedicationEventType.SNOOZED })
        assertEquals(9.0, InventoryRules.stock(data.adjustments), 0.0)
        c.medications.delete(id)
    }
}
