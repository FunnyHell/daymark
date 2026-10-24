package io.github.funnyhell.daymark.platform.widgets

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Concrete colours for bitmaps drawn into widgets (Glance colour providers cannot be used on a Canvas). */
data class WidgetPalette(val accent: Int, val track: Int, val text: Int, val muted: Int) {
    companion object {
        fun from(context: Context): WidgetPalette {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WidgetPalette(
                    accent = context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600),
                    track = context.getColor(if (dark) android.R.color.system_neutral2_700 else android.R.color.system_neutral2_100),
                    text = context.getColor(if (dark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900),
                    muted = context.getColor(if (dark) android.R.color.system_neutral2_400 else android.R.color.system_neutral2_500),
                )
            } else if (dark) {
                WidgetPalette(0xFFB4C5FF.toInt(), 0xFF3A3F4A.toInt(), 0xFFE3E2E9.toInt(), 0xFF9A9DA8.toInt())
            } else {
                WidgetPalette(0xFF2F5FD0.toInt(), 0xFFE1E3EC.toInt(), 0xFF1B1B21.toInt(), 0xFF73767F.toInt())
            }
        }
    }
}

object WidgetGraphics {
    private fun paint(color: Int, stroke: Float? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (stroke != null) {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
    }

    /**
     * A ring split into one sector per item; each sector is filled in proportion to its item's progress (0..1).
     * A completely filled ring means everything due is done.
     */
    fun sectorRing(fractions: List<Double>, sizePx: Int, palette: WidgetPalette, strokeRatio: Float = 0.13f): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val stroke = sizePx * strokeRatio
        val inset = stroke / 2 + 1
        val oval = RectF(inset, inset, sizePx - inset, sizePx - inset)
        val track = paint(palette.track, stroke)
        val fill = paint(palette.accent, stroke)
        if (fractions.isEmpty()) {
            canvas.drawArc(oval, 0f, 360f, false, track)
            return bmp
        }
        val n = fractions.size
        // The round caps need a visible gap between sectors; a single item is a plain ring.
        val gap = if (n == 1) 0f else minOf(14f, 120f / n)
        val sweep = 360f / n - gap
        fractions.forEachIndexed { i, f ->
            val start = -90f + i * (360f / n) + gap / 2
            if (n == 1) {
                canvas.drawArc(oval, 0f, 360f, false, track.apply { strokeCap = Paint.Cap.BUTT })
            } else {
                canvas.drawArc(oval, start, sweep, false, track)
            }
            val filled = (sweep * f.coerceIn(0.0, 1.0)).toFloat()
            if (filled > 0.5f) canvas.drawArc(oval, start, filled, false, fill)
        }
        return bmp
    }

    /** One continuous progress ring (0..1). */
    fun progressRing(fraction: Double, sizePx: Int, palette: WidgetPalette): Bitmap = sectorRing(listOf(fraction), sizePx, palette)

    /**
     * A month calendar of dots. [levels] maps a date to 0..1 (share of due habits done) or null when nothing was due.
     * Bigger, fuller dots mean more was done; days with nothing due are small faint dots; today is outlined.
     */
    fun monthDots(
        month: LocalDate,
        today: LocalDate,
        levels: Map<LocalDate, Double?>,
        firstDayOfWeek: DayOfWeek,
        widthPx: Int,
        palette: WidgetPalette,
        locale: Locale,
    ): Bitmap {
        val first = month.withDayOfMonth(1)
        val offset = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        val rows = (offset + first.lengthOfMonth() + 6) / 7
        val cell = widthPx / 7f
        val header = cell * 0.6f
        val heightPx = (header + rows * cell).toInt()
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.muted
            textSize = cell * 0.32f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        for (i in 0 until 7) {
            val day = firstDayOfWeek.plus(i.toLong())
            canvas.drawText(day.getDisplayName(TextStyle.NARROW, locale), cell * i + cell / 2, header * 0.75f, label)
        }
        val maxR = cell * 0.36f
        val track = paint(palette.track)
        val fill = paint(palette.accent)
        val outline = paint(palette.text, cell * 0.06f)
        for (d in 1..first.lengthOfMonth()) {
            val date = first.withDayOfMonth(d)
            val index = offset + d - 1
            val cx = cell * (index % 7) + cell / 2
            val cy = header + cell * (index / 7) + cell / 2
            val level = levels[date]
            when {
                date.isAfter(today) -> canvas.drawCircle(cx, cy, maxR * 0.25f, track)
                level == null -> canvas.drawCircle(cx, cy, maxR * 0.35f, track)
                else -> {
                    canvas.drawCircle(cx, cy, maxR, track)
                    if (level > 0.0) canvas.drawCircle(cx, cy, maxR * (0.35f + 0.65f * level.toFloat()), fill)
                }
            }
            if (date == today) canvas.drawCircle(cx, cy, maxR + cell * 0.07f, outline)
        }
        return bmp
    }
}
