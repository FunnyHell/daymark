package io.github.funnyhell.daymark.ui.format

import android.content.Context
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Locale- and 12/24-hour-aware formatting shared by screens, notifications and widgets. */
object Formats {
    fun time(context: Context, time: LocalTime): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, locale(context)))
    }

    fun time(context: Context, instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        time(context, instant.atZone(zone).toLocalTime())

    fun dateMedium(context: Context, date: LocalDate): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(context)))

    fun dateFull(context: Context, date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale(context))).replaceFirstChar { it.titlecase(locale(context)) }

    fun dateTime(context: Context, instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = instant.atZone(zone)
        return "${dateMedium(context, local.toLocalDate())} ${time(context, local.toLocalTime())}"
    }

    fun dayShort(context: Context, day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale(context))

    fun monthYear(context: Context, date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale(context))).replaceFirstChar { it.titlecase(locale(context)) }

    fun locale(context: Context): Locale = context.resources.configuration.locales[0]
}
