package io.github.funnyhell.daymark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
import io.github.funnyhell.daymark.platform.widgets.WeekMark
import io.github.funnyhell.daymark.platform.widgets.WeekWidget
import io.github.funnyhell.daymark.platform.widgets.WidgetContent
import io.github.funnyhell.daymark.platform.widgets.WidgetRenderer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every widget can be composed directly into RemoteViews (the path that draws a widget without waiting for a
 * Glance session) and those RemoteViews inflate into real views, at a small and a large size.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRenderInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun allWidgets_composeAndInflate() = runBlocking {
        for (size in listOf(DpSize(110.dp, 110.dp), DpSize(320.dp, 220.dp))) {
            for (create in WidgetRenderer.all) {
                val widget = create()
                val views = widget.compose(context, size = size)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val parent = FrameLayout(context)
                    val view = views.apply(context, parent)
                    parent.addView(view)
                    assertTrue("${widget.javaClass.simpleName} at $size", parent.childCount == 1)
                }
            }
        }
    }

    /**
     * Draws the week widget with a few sample habits into a picture, saved with the test results so its layout
     * can be looked at. Tapping a day goes through HabitRepository.setDone, which is used here to fill the days.
     */
    @Test
    fun weekWidget_drawsSampleHabits() = runBlocking {
        val c = context.container
        val today = c.time.today()
        fun draft(name: String, type: HabitType, color: Long, target: Double = 1.0) =
            HabitDraft(name, null, color, type, null, today.minusDays(9), Recurrence.Daily, target, null, true)
        val ids = listOf(
            c.habits.create(draft("No junk", HabitType.ABSTINENCE, 0xFFE5484D, 0.0)),
            c.habits.create(draft("Languages", HabitType.CHECK, 0xFF45474F)),
            c.habits.create(draft("Gym", HabitType.WEEKLY_GOAL, 0xFF5B5BD6, 3.0)),
        )
        try {
            c.habits.setDone(ids[0], done = false, date = today.minusDays(2))
            c.habits.setDone(ids[1], done = true, date = today.minusDays(4))
            c.habits.setDone(ids[2], done = true, date = today.minusDays(6))
            c.habits.setDone(ids[2], done = true, date = today)
            val row = WidgetContent.weekGrid(c.habits.all(), today, c.settings.current().firstDayOfWeek).first { it.habitId == ids[0] }
            assertEquals(WeekMark.SLIP, row.cells[4].mark)
            assertEquals(2, row.streak)

            val density = context.resources.displayMetrics.density
            val size = DpSize(330.dp, 150.dp)
            val views = WeekWidget().compose(context, size = size)
            val width = (size.width.value * density).toInt()
            val height = (size.height.value * density).toInt()
            var bitmap: Bitmap? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val parent = FrameLayout(context)
                parent.addView(views.apply(context, parent))
                parent.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                parent.layout(0, 0, width, height)
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { parent.draw(Canvas(it)) }
            }
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: context.getExternalFilesDir(null)
            if (dir != null) {
                dir.mkdirs()
                File(dir, "week-widget.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            ids.forEach { c.habits.delete(it) }
        }
    }
}
