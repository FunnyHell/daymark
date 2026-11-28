package io.github.funnyhell.daymark

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.funnyhell.daymark.module.ModuleReminder
import io.github.funnyhell.daymark.modules.intervals.IntervalActionReceiver
import io.github.funnyhell.daymark.modules.intervals.IntervalReminder
import io.github.funnyhell.daymark.modules.intervals.IntervalStore
import io.github.funnyhell.daymark.modules.intervals.IntervalsModule
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** An interval reminder is shown through the module contract on its own channel, and "Done" counts it and clears it. */
@RunWith(AndroidJUnit4::class)
class IntervalsModuleInstrumentedTest {
    @get:Rule
    val notifications: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun active(id: String) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.tag == IntervalsModule.notificationTag(id) }

    @Test
    fun doneFromNotification_countsAndClears() = runBlocking {
        val c = context.container
        val store = IntervalStore(context)
        val reminder = IntervalReminder(id = UUID.randomUUID().toString(), title = "Stretch test")
        store.save(reminder)
        try {
            IntervalsModule.reminders.show(context, ModuleReminder("${reminder.id}@1", c.time.now(), group = reminder.id))
            var shown = active(reminder.id)
            for (i in 0 until 25) {
                if (shown != null) break
                delay(200)
                shown = active(reminder.id)
            }
            assertTrue("reminder shown", shown != null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                assertEquals(IntervalsModule.CHANNEL, shown!!.notification.channelId)
            }
            context.sendBroadcast(
                Intent(context, IntervalActionReceiver::class.java)
                    .setAction(IntervalActionReceiver.ACTION_DONE)
                    .putExtra("interval_id", reminder.id),
            )
            var count = 0
            for (i in 0 until 50) {
                count = store.get(reminder.id)?.countOn(c.time.today()) ?: 0
                if (count == 1 && active(reminder.id) == null) break
                delay(200)
            }
            assertEquals(1, count)
            assertTrue("notification removed", active(reminder.id) == null)
        } finally {
            store.delete(reminder.id)
        }
    }
}
