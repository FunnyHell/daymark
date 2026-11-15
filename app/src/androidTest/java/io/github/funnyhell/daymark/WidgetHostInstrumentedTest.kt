package io.github.funnyhell.daymark

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.funnyhell.daymark.domain.habit.HabitDraft
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.domain.medication.DoseDraft
import io.github.funnyhell.daymark.domain.medication.MedicationDraft
import io.github.funnyhell.daymark.platform.widgets.WidgetRenderer
import io.github.funnyhell.daymark.platform.widgets.WidgetUpdater
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every widget is bound to a widget host, as a launcher does, and drawn through the same call the app uses,
 * with a realistic amount of data, at several sizes and in Russian. A widget that cannot be composed or that
 * the system refuses fails here instead of staying on "Loading" on a device.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHostInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private suspend fun sampleData(): Pair<List<String>, List<String>> {
        val c = context.container
        val today = c.time.today()
        val start = today.minusDays(40)
        fun habit(name: String, type: HabitType, target: Double, recurrence: Recurrence = Recurrence.Daily) =
            HabitDraft(name, null, 0xFF2F5FD0, type, if (type == HabitType.QUANTITY) "ml" else null, start, recurrence, target, null, true)
        val habits = listOf(
            c.habits.create(habit("Зарядка", HabitType.CHECK, 1.0)),
            c.habits.create(habit("Вода", HabitType.MULTI, 3.0)),
            c.habits.create(habit("Чтение", HabitType.QUANTITY, 30.0)),
            c.habits.create(habit("Спортзал", HabitType.WEEKLY_GOAL, 3.0)),
            c.habits.create(habit("Без сладкого", HabitType.ABSTINENCE, 0.0)),
            c.habits.create(habit("Прогулка", HabitType.CHECK, 1.0, Recurrence.Weekdays(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)))),
            c.habits.create(habit("Дневник", HabitType.CHECK, 1.0, Recurrence.EveryNDays(3))),
            c.habits.create(habit("Очень длинное название привычки для проверки строки", HabitType.CHECK, 1.0)),
        )
        for (back in 0L..30L step 2) {
            c.habits.setDone(habits[0], done = true, date = today.minusDays(back))
            c.habits.setDone(habits[3], done = true, date = today.minusDays(back))
        }
        c.habits.setDone(habits[1], done = true, date = today)
        c.habits.setDone(habits[2], done = true, date = today.minusDays(1))
        c.habits.setDone(habits[4], done = false, date = today.minusDays(5))
        val meds = listOf("Витамин D", "Магний").map { name ->
            c.medications.create(
                MedicationDraft(
                    name = name, strength = null, notes = null, colorArgb = 0xFF6A4BC4, days = DayOfWeek.entries.toSet(),
                    doses = listOf(DoseDraft(LocalTime.of(8, 0), "1 таблетка", 1.0), DoseDraft(LocalTime.of(20, 0), "1 таблетка", 1.0)),
                    inventoryEnabled = false, inventoryUnit = "tablets", lowStockThreshold = null, lowStockAlerts = false, initialStock = null,
                ),
            )
        }
        return habits to meds
    }

    @Test
    fun everyWidget_isDrawnForABoundWidgetId() = runBlocking {
        val c = context.container
        val (habits, meds) = sampleData()
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 4711)
        val ids = mutableListOf<Int>()
        try {
            shell("appwidget grantbind --package ${context.packageName} --user 0")
            for (receiver in WidgetUpdater.receivers) {
                val id = host.allocateAppWidgetId()
                ids += id
                assertTrue("bind ${receiver.simpleName}", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, receiver)))
                // Before the launcher reports a size, then small, large and wide.
                WidgetRenderer.render(context, id)
                for ((w, h) in listOf(110 to 110, 250 to 110, 350 to 420, 700 to 300)) {
                    manager.updateAppWidgetOptions(
                        id,
                        Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w + 120)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h - 40)
                            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
                        },
                    )
                    WidgetRenderer.render(context, id)
                }
                // The settings preview of the same widget.
                val preview = WidgetRenderer.compose(context, id, preview = true)!!
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val parent = FrameLayout(context)
                    parent.addView(preview.apply(context, parent))
                }
            }
        } finally {
            ids.forEach { host.deleteAppWidgetId(it) }
            habits.forEach { c.habits.delete(it) }
            meds.forEach { c.medications.delete(it) }
        }
    }

    @Test
    fun everyWidget_composesAndInflatesInRussian() = runBlocking {
        val c = context.container
        val (habits, meds) = sampleData()
        try {
            val russian = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ru", "RU")) })
            for (size in listOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(350.dp, 420.dp))) {
                for (create in WidgetRenderer.all) {
                    val widget = create()
                    val views = widget.compose(russian, size = size)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val parent = FrameLayout(russian)
                        parent.addView(views.apply(russian, parent))
                    }
                }
            }
        } finally {
            habits.forEach { c.habits.delete(it) }
            meds.forEach { c.medications.delete(it) }
        }
    }
}
