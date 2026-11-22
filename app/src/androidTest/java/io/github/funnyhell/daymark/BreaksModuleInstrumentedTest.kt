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
import io.github.funnyhell.daymark.modules.breaks.BreakActionReceiver
import io.github.funnyhell.daymark.modules.breaks.BreaksModule
import io.github.funnyhell.daymark.modules.breaks.BreaksStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The break notification is shown through the module contract, and "I moved" records a break and removes it. */
@RunWith(AndroidJUnit4::class)
class BreaksModuleInstrumentedTest {
    @get:Rule
    val notifications: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shown() = context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == "breaks" }

    @Test
    fun doneFromNotification_recordsABreakAndClearsIt() = runBlocking {
        val c = context.container
        val store = BreaksStore(context)
        val before = store.current().countOn(c.time.today())
        BreaksModule.reminders.show(context, ModuleReminder("test", c.time.now()))
        var visible = false
        for (i in 0 until 25) {
            visible = shown()
            if (visible) break
            delay(200)
        }
        assertTrue("break notification shown", visible)
        context.sendBroadcast(Intent(context, BreakActionReceiver::class.java).setAction(BreakActionReceiver.ACTION_DONE))
        var count = before
        for (i in 0 until 50) {
            count = store.current().countOn(c.time.today())
            if (count == before + 1 && !shown()) break
            delay(200)
        }
        assertEquals(before + 1, count)
        assertTrue("notification removed", !shown())
    }
}
