package io.github.funnyhell.daymark.modules.intervals

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A reminder that repeats every so often during active hours, such as "Stretch every hour from 9 to 18". */
data class IntervalReminder(
    val id: String,
    val title: String,
    val plan: BreakPlan = BreakPlan(),
    val enabled: Boolean = true,
    /** When it was last done (or put off with "Later"); the next reminder is counted from here. */
    val anchor: Instant? = null,
    val countDate: LocalDate? = null,
    val count: Int = 0,
) {
    fun countOn(date: LocalDate): Int = if (countDate == date) count else 0

    fun done(at: Instant, date: LocalDate) = copy(
        anchor = BreakRules.anchorAfterDone(at),
        countDate = date,
        count = countOn(date) + 1,
    )

    fun later(at: Instant) = copy(anchor = BreakRules.anchorForLater(plan, at))
}

@Serializable
private data class IntervalReminderRecord(
    val id: String,
    val title: String,
    val intervalMinutes: Int,
    val from: String,
    val until: String,
    val days: List<String>,
    val enabled: Boolean = true,
    val anchor: Long? = null,
    val countDate: String? = null,
    val count: Int = 0,
)

/** Stores the list as JSON; unknown or broken entries are skipped instead of losing the whole list. */
object IntervalCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(reminders: List<IntervalReminder>): String = json.encodeToString(
        reminders.map { r ->
            IntervalReminderRecord(
                id = r.id, title = r.title, intervalMinutes = r.plan.intervalMinutes, from = r.plan.from.toString(),
                until = r.plan.until.toString(), days = r.plan.days.map { it.name }.sorted(), enabled = r.enabled,
                anchor = r.anchor?.toEpochMilli(), countDate = r.countDate?.toString(), count = r.count,
            )
        },
    )

    fun decode(text: String?): List<IntervalReminder> {
        if (text.isNullOrBlank()) return emptyList()
        val records = runCatching { json.decodeFromString<List<IntervalReminderRecord>>(text) }.getOrNull() ?: return emptyList()
        return records.mapNotNull { r ->
            runCatching {
                IntervalReminder(
                    id = r.id,
                    title = r.title,
                    plan = BreakPlan(
                        intervalMinutes = r.intervalMinutes,
                        from = LocalTime.parse(r.from),
                        until = LocalTime.parse(r.until),
                        days = r.days.mapNotNull { d -> runCatching { DayOfWeek.valueOf(d) }.getOrNull() }.toSet(),
                    ),
                    enabled = r.enabled,
                    anchor = r.anchor?.let(Instant::ofEpochMilli),
                    countDate = r.countDate?.let(LocalDate::parse),
                    count = r.count,
                )
            }.getOrNull()
        }
    }
}
