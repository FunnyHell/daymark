package io.github.funnyhell.daymark.ui.medications

import android.content.Context
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.medication.ActionSource
import io.github.funnyhell.daymark.domain.medication.AdjustmentType
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.MedicationEventType
import io.github.funnyhell.daymark.domain.medication.MedicationSchedule
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import io.github.funnyhell.daymark.domain.medication.OccurrenceStatus
import io.github.funnyhell.daymark.platform.widgets.WidgetContent
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class DoseItem(
    val medicationId: String,
    val occurrenceKey: String,
    val name: String,
    val colorArgb: Long,
    val date: LocalDate,
    val time: LocalTime,
    val timeText: String,
    val doseText: String,
    val state: OccurrenceState,
    val stateText: String,
    /** Active TAKEN/SKIPPED event that a correction would void. */
    val resolutionEventId: String?,
    val snoozedUntilText: String?,
    /** The note on the recorded dose. */
    val note: String? = null,
) {
    val resolved: Boolean get() = state == OccurrenceState.TAKEN || state == OccurrenceState.SKIPPED
}

data class StockInfo(val stock: Double, val unit: String, val low: Boolean, val text: String, val daysText: String?)

object MedicationPresentation {
    fun doseItem(context: Context, data: MedicationData, status: OccurrenceStatus, zone: ZoneId): DoseItem {
        val occ = status.occurrence
        return DoseItem(
            medicationId = data.medication.id,
            occurrenceKey = occ.key,
            name = data.medication.name,
            colorArgb = data.medication.colorArgb,
            date = occ.date,
            time = occ.time,
            timeText = Formats.time(context, occ.time),
            doseText = occ.doseLabel.ifBlank { context.getString(R.string.dose_default) },
            state = status.state,
            stateText = context.getString(WidgetContent.stateLabel(status.state)),
            resolutionEventId = status.resolution?.id,
            snoozedUntilText = status.snoozedUntil?.let { Formats.time(context, it, zone) },
            note = status.resolution?.note,
        )
    }

    fun dosesOn(context: Context, meds: List<MedicationData>, date: LocalDate, now: Instant, zone: ZoneId): List<DoseItem> =
        meds.filter { it.medication.status != LifecycleStatus.ARCHIVED }
            .flatMap { data -> OccurrenceRules.statusesOn(data, date, now, zone).map { doseItem(context, data, it, zone) } }
            .sortedWith(compareBy({ it.time }, { it.name }))

    fun stockInfo(context: Context, data: MedicationData, today: LocalDate): StockInfo? {
        val med = data.medication
        if (!med.inventoryEnabled) return null
        val stock = InventoryRules.stock(data.adjustments)
        val low = InventoryRules.isLow(stock, med.lowStockThreshold)
        val days = InventoryRules.daysOfSupply(stock, OccurrenceRules.scheduleOn(data.schedules, today))
        return StockInfo(
            stock = stock,
            unit = med.inventoryUnit,
            low = low,
            text = context.getString(R.string.stock_left, stock.formatQuantity(), med.inventoryUnit),
            daysText = days?.let { context.resources.getQuantityString(R.plurals.days_of_supply, it, it) },
        )
    }

    fun scheduleText(context: Context, schedule: MedicationSchedule?): String {
        if (schedule == null || schedule.doses.isEmpty()) return context.getString(R.string.med_no_schedule)
        val times = schedule.doses.joinToString(", ") { Formats.time(context, it.time) }
        val days = if (schedule.days.size == 7) context.getString(R.string.recurrence_daily)
        else schedule.days.sorted().joinToString(", ") { Formats.dayShort(context, it) }
        return "$days · $times"
    }

    fun eventTypeText(context: Context, type: MedicationEventType): String = context.getString(
        when (type) {
            MedicationEventType.TAKEN -> R.string.occ_taken
            MedicationEventType.SKIPPED -> R.string.occ_skipped
            MedicationEventType.SNOOZED -> R.string.occ_snoozed
        },
    )

    fun sourceText(context: Context, source: ActionSource): String = context.getString(
        when (source) {
            ActionSource.APP -> R.string.source_app
            ActionSource.NOTIFICATION -> R.string.source_notification
            ActionSource.WIDGET -> R.string.source_widget
            ActionSource.IMPORT -> R.string.source_import
        },
    )

    fun adjustmentText(context: Context, type: AdjustmentType): String = context.getString(
        when (type) {
            AdjustmentType.INITIAL -> R.string.adj_initial
            AdjustmentType.PACKAGE_ADDED -> R.string.adj_package
            AdjustmentType.MANUAL_CORRECTION -> R.string.adj_correction
            AdjustmentType.INTAKE -> R.string.adj_intake
            AdjustmentType.INTAKE_REVERSAL -> R.string.adj_reversal
        },
    )

    val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()
}
