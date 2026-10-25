package io.github.funnyhell.daymark

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.funnyhell.daymark.platform.widgets.WidgetRenderer
import kotlinx.coroutines.runBlocking
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
}
