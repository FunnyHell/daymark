package io.github.funnyhell.daymark.platform.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

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
        drawSectorRing(Canvas(bmp), sizePx / 2f, sizePx / 2f, sizePx / 2f, fractions, palette, sizePx * strokeRatio)
        return bmp
    }

    private fun drawSectorRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, fractions: List<Double>, palette: WidgetPalette, stroke: Float) {
        val inset = stroke / 2 + 1
        val oval = RectF(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)
        val track = paint(palette.track, stroke)
        val fill = paint(palette.accent, stroke)
        if (fractions.isEmpty()) {
            canvas.drawArc(oval, 0f, 360f, false, track)
            return
        }
        val n = fractions.size
        if (n == 1) {
            track.strokeCap = Paint.Cap.BUTT
            canvas.drawArc(oval, 0f, 360f, false, track)
            val filled = (360f * fractions[0].coerceIn(0.0, 1.0)).toFloat()
            if (filled >= 359.5f) {
                fill.strokeCap = Paint.Cap.BUTT
                canvas.drawArc(oval, 0f, 360f, false, fill)
            } else if (filled > 0.5f) {
                canvas.drawArc(oval, -90f, filled, false, fill)
            }
            return
        }
        // The round caps need a visible gap between sectors.
        val capAngle = Math.toDegrees((stroke / (oval.width() / 2)).toDouble()).toFloat()
        val gap = minOf(capAngle + 6f, 120f / n)
        val sweep = 360f / n - gap
        fractions.forEachIndexed { i, f ->
            val start = -90f + i * (360f / n) + gap / 2
            canvas.drawArc(oval, start, sweep, false, track)
            val filled = (sweep * f.coerceIn(0.0, 1.0)).toFloat()
            if (filled > 0.5f) canvas.drawArc(oval, start, filled, false, fill)
        }
    }

    /** One continuous progress ring (0..1). */
    fun progressRing(fraction: Double, sizePx: Int, palette: WidgetPalette): Bitmap = sectorRing(listOf(fraction), sizePx, palette)

    /**
     * A month calendar where every day is a small sector ring around its day number: one sector per habit due
     * that day, each filled by its progress, so "2 of 3 done" reads as two full sectors out of three. Days with
     * nothing due show only the number; future days are muted; today is drawn in the accent color.
     */
    fun monthRings(
        month: LocalDate,
        today: LocalDate,
        fractions: Map<LocalDate, List<Double>>,
        firstDayOfWeek: DayOfWeek,
        widthPx: Int,
        heightPx: Int,
        palette: WidgetPalette,
        locale: Locale,
    ): Bitmap {
        val first = month.withDayOfMonth(1)
        val offset = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
        val rows = (offset + first.lengthOfMonth() + 6) / 7
        // Square cells as large as both dimensions allow; the weekday header takes 0.5 of a cell.
        val cell = minOf(widthPx / 7f, heightPx / (rows + 0.5f))
        val header = cell * 0.5f
        val gridWidth = cell * 7
        val left = (widthPx - gridWidth) / 2
        val bmp = Bitmap.createBitmap(widthPx, (header + rows * cell).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.muted
            textSize = cell * 0.28f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        for (i in 0 until 7) {
            val day = firstDayOfWeek.plus(i.toLong())
            canvas.drawText(day.getDisplayName(TextStyle.NARROW, locale), left + cell * i + cell / 2, header * 0.75f, label)
        }
        val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cell * 0.3f
            textAlign = Paint.Align.CENTER
        }
        val radius = cell * 0.46f
        val stroke = cell * 0.09f
        for (d in 1..first.lengthOfMonth()) {
            val date = first.withDayOfMonth(d)
            val index = offset + d - 1
            val cx = left + cell * (index % 7) + cell / 2
            val cy = header + cell * (index / 7) + cell / 2
            val dayFractions = fractions[date].orEmpty()
            if (!date.isAfter(today) && dayFractions.isNotEmpty()) {
                drawSectorRing(canvas, cx, cy, radius, dayFractions, palette, stroke)
            }
            number.color = when {
                date == today -> palette.accent
                date.isAfter(today) || dayFractions.isEmpty() -> palette.muted
                else -> palette.text
            }
            number.typeface = if (date == today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(d.toString(), cx, cy - (number.descent() + number.ascent()) / 2, number)
        }
        return bmp
    }
}
