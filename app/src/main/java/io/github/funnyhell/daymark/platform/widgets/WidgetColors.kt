package io.github.funnyhell.daymark.platform.widgets

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.glance.color.ColorProvider as dayNightColor
import androidx.glance.unit.ColorProvider

/** Concrete colors for bitmaps drawn into widgets (Glance color providers cannot be used on a Canvas). */
data class WidgetPalette(val accent: Int, val track: Int, val text: Int, val muted: Int)

/**
 * Colors of one widget, derived from its settings (accent and background opacity). The Glance providers switch
 * with the system light/dark theme; the [palette] for bitmaps uses the theme active when the widget is drawn.
 */
class WidgetColors(
    val background: ColorProvider,
    val onSurface: ColorProvider,
    val onSurfaceVariant: ColorProvider,
    val accent: ColorProvider,
    /** A weak neutral for tracks and inactive marks. */
    val faint: ColorProvider,
    val palette: WidgetPalette,
) {
    companion object {
        fun from(context: Context, prefs: WidgetPrefs): WidgetColors {
            val systemSurface = pair(context, android.R.color.system_neutral1_10, android.R.color.system_neutral1_900, 0xFFFAF9FD, 0xFF1B1B1F)
            val darkText = 0xFF1B1B21.toInt() to 0xFF1B1B21.toInt()
            val darkMuted = 0xFF5D5E67.toInt() to 0xFF5D5E67.toInt()
            val lightText = 0xFFF4F4F7.toInt() to 0xFFF4F4F7.toInt()
            val lightMuted = 0xFFC9CAD3.toInt() to 0xFFC9CAD3.toInt()
            val systemText = pair(context, android.R.color.system_neutral1_900, android.R.color.system_neutral1_50, 0xFF1B1B21, 0xFFE3E2E9)
            val systemMuted = pair(context, android.R.color.system_neutral2_600, android.R.color.system_neutral2_300, 0xFF5D5E67, 0xFFC4C6D0)
            val accent = accent(context, prefs.accent)
            val surface = background(prefs.background, systemSurface, accent)
            val alpha = (prefs.backgroundAlpha.coerceIn(0, 100) * 255 + 50) / 100
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            // Text follows the chosen text color, or the background: dark text on light colors, light on dark ones.
            val (text, muted) = when (prefs.text) {
                WidgetText.LIGHT -> lightText to lightMuted
                WidgetText.DARK -> darkText to darkMuted
                WidgetText.AUTO -> if (prefs.background == WidgetBackground.SYSTEM) {
                    systemText to systemMuted
                } else {
                    val textDay = if (isLight(surface.first)) darkText.first else lightText.first
                    val textNight = if (isLight(surface.second)) darkText.second else lightText.second
                    val mutedDay = if (isLight(surface.first)) darkMuted.first else lightMuted.first
                    val mutedNight = if (isLight(surface.second)) darkMuted.second else lightMuted.second
                    (textDay to textNight) to (mutedDay to mutedNight)
                }
            }
            val currentAccent = if (dark) accent.second else accent.first
            return WidgetColors(
                background = provider(withAlpha(surface.first, alpha), withAlpha(surface.second, alpha)),
                onSurface = provider(text.first, text.second),
                onSurfaceVariant = provider(muted.first, muted.second),
                accent = provider(accent.first, accent.second),
                faint = provider(withAlpha(muted.first, 0x47), withAlpha(muted.second, 0x47)),
                palette = WidgetPalette(
                    accent = currentAccent,
                    track = withAlpha(currentAccent, 0x47),
                    text = if (dark) text.second else text.first,
                    muted = if (dark) muted.second else muted.first,
                ),
            )
        }

        /** Day and night color of a background choice. */
        fun background(choice: WidgetBackground, system: Pair<Int, Int>, accent: Pair<Int, Int>): Pair<Int, Int> = when (choice) {
            WidgetBackground.SYSTEM -> system
            WidgetBackground.WHITE -> 0xFFFFFFFF.toInt() to 0xFFFFFFFF.toInt()
            WidgetBackground.LIGHT_GRAY -> 0xFFE7E8EC.toInt() to 0xFFE7E8EC.toInt()
            WidgetBackground.DARK_GRAY -> 0xFF3A3B40.toInt() to 0xFF3A3B40.toInt()
            WidgetBackground.BLACK -> 0xFF000000.toInt() to 0xFF000000.toInt()
            WidgetBackground.NAVY -> 0xFF1C2541.toInt() to 0xFF1C2541.toInt()
            // A pale tint of the accent by day, a deep one at night.
            WidgetBackground.ACCENT -> mix(accent.first, 0xFFFFFFFF.toInt(), 0.82f) to mix(accent.second, 0xFF000000.toInt(), 0.75f)
        }

        /** Whether dark text reads better than light text on [color] (relative luminance above the midpoint). */
        fun isLight(color: Int): Boolean {
            fun channel(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            val l = 0.2126 * channel((color shr 16) and 0xFF) + 0.7152 * channel((color shr 8) and 0xFF) + 0.0722 * channel(color and 0xFF)
            return l > 0.179
        }

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun c(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }

        /** Day and night color of an accent choice. */
        fun accent(context: Context, accent: WidgetAccent): Pair<Int, Int> = when (accent) {
            WidgetAccent.SYSTEM -> pair(context, android.R.color.system_accent1_600, android.R.color.system_accent1_200, 0xFF2F5FD0, 0xFFB4C5FF)
            WidgetAccent.BLUE -> 0xFF2F5FD0.toInt() to 0xFFB4C5FF.toInt()
            WidgetAccent.GREEN -> 0xFF2E7D32.toInt() to 0xFF8FD694.toInt()
            WidgetAccent.ORANGE -> 0xFFC25E00.toInt() to 0xFFFFB77C.toInt()
            WidgetAccent.PURPLE -> 0xFF6A4BC4.toInt() to 0xFFCDBDFF.toInt()
            WidgetAccent.RED -> 0xFFC62828.toInt() to 0xFFFFB4AB.toInt()
            WidgetAccent.MONO -> 0xFF303033.toInt() to 0xFFE6E6EA.toInt()
        }

        private fun pair(context: Context, dayRes: Int, nightRes: Int, dayFallback: Long, nightFallback: Long): Pair<Int, Int> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getColor(dayRes) to context.getColor(nightRes)
            } else {
                dayFallback.toInt() to nightFallback.toInt()
            }

        private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        private fun provider(day: Int, night: Int): ColorProvider = dayNightColor(Color(day), Color(night))
    }
}
