package io.github.funnyhell.daymark.ui.history

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.common.startOfWeek
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.RateSummary
import io.github.funnyhell.daymark.domain.medication.MedicationEvent
import io.github.funnyhell.daymark.domain.stats.Adherence
import io.github.funnyhell.daymark.domain.stats.HabitStatistics
import io.github.funnyhell.daymark.domain.stats.MedicationStatistics
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.ColorDot
import io.github.funnyhell.daymark.ui.common.EmptyState
import io.github.funnyhell.daymark.ui.common.HeatCell
import io.github.funnyhell.daymark.ui.common.Heatmap
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.StatTile
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import io.github.funnyhell.daymark.ui.medications.MedicationPresentation
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class HabitStatRow(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val current: Int,
    val best: Int,
    val rate30: Double?,
    val unitPlural: Int,
    val extra: String?,
)

data class QuantitySeries(val name: String, val unit: String, val colorArgb: Long, val target: Double, val values: List<Pair<LocalDate, Double>>)

data class LoggedEvent(val event: MedicationEvent, val medicationName: String)

data class HistoryState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val hasHabits: Boolean = false,
    val hasMeds: Boolean = false,
    val cells: Map<LocalDate, HeatCell> = emptyMap(),
    val thisWeek: RateSummary = RateSummary(0, 0),
    val lastWeek: RateSummary = RateSummary(0, 0),
    val thisMonth: RateSummary = RateSummary(0, 0),
    val lastMonth: RateSummary = RateSummary(0, 0),
    val habitRows: List<HabitStatRow> = emptyList(),
    val quantities: List<QuantitySeries> = emptyList(),
    val adherence7: Adherence = Adherence(0, 0, 0, 0),
    val adherence30: Adherence = Adherence(0, 0, 0, 0),
    val events: List<LoggedEvent> = emptyList(),
)

class HistoryViewModel(application: Application) : AppViewModel(application) {
    val state: StateFlow<HistoryState> = combine(
        container.habits.observeAll(), container.medications.observeAll(), container.settings.settings,
    ) { habits, meds, settings ->
        val today = container.time.today()
        val now = container.time.now()
        val zone = container.time.zone()
        val wk = settings.firstDayOfWeek
        val weekStart = today.startOfWeek(wk)
        val monthStart = today.withDayOfMonth(1)
        val heatFrom = today.minusWeeks(HEATMAP_WEEKS.toLong())
        val activity = HabitStatistics.dailyActivity(habits, heatFrom, today, today, wk)
        val cells = generateSequence(heatFrom) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.associateWith { date ->
            val a = activity[date]
            val level = a?.let { if (it.due == 0) null else if (it.done == 0) 0 else 1 + (3 * it.done / it.due).coerceIn(0, 3) }
            val text = if (a == null) string(R.string.heat_nothing_due) else string(R.string.heat_done_of, a.done, a.due)
            HeatCell(date, level, "${Formats.dateMedium(app, date)}: $text")
        }
        HistoryState(
            loading = false,
            today = today,
            firstDayOfWeek = wk,
            hasHabits = habits.isNotEmpty(),
            hasMeds = meds.isNotEmpty(),
            cells = cells,
            thisWeek = HabitStatistics.summary(habits, weekStart, today, today, wk),
            lastWeek = HabitStatistics.summary(habits, weekStart.minusWeeks(1), weekStart.minusDays(1), today, wk),
            thisMonth = HabitStatistics.summary(habits, monthStart, today, today, wk),
            lastMonth = HabitStatistics.summary(habits, monthStart.minusMonths(1), monthStart.minusDays(1), today, wk),
            habitRows = habits.map { data ->
                val e = HabitEvaluator(data, wk)
                val s = e.streaks(today)
                HabitStatRow(
                    id = data.habit.id,
                    name = data.habit.name,
                    colorArgb = data.habit.colorArgb,
                    current = s.current,
                    best = s.best,
                    rate30 = e.completionRate(today.minusDays(29), today, today).rate,
                    unitPlural = if (data.habit.type == HabitType.WEEKLY_GOAL) R.plurals.unit_weeks else R.plurals.unit_days,
                    extra = when (data.habit.type) {
                        HabitType.ABSTINENCE -> string(R.string.lapses_count, e.lapses().size)
                        HabitType.QUANTITY -> string(R.string.total_amount, e.totalProgress().formatQuantity(), data.habit.unit.orEmpty())
                        else -> null
                    },
                )
            },
            quantities = habits.filter { it.habit.type == HabitType.QUANTITY }.map { data ->
                QuantitySeries(
                    name = data.habit.name,
                    unit = data.habit.unit.orEmpty(),
                    colorArgb = data.habit.colorArgb,
                    target = HabitEvaluator(data, wk).targetOn(today),
                    values = HabitStatistics.quantitySeries(data, today.minusDays(13), today),
                )
            },
            adherence7 = MedicationStatistics.adherence(meds, today.minusDays(6), today, now, zone),
            adherence30 = MedicationStatistics.adherence(meds, today.minusDays(29), today, now, zone),
            events = meds.flatMap { m -> m.events.map { LoggedEvent(it, m.medication.name) } }.sortedByDescending { it.event.recordedAt }.take(100),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())

    companion object {
        const val HEATMAP_WEEKS = 20
        val Factory = appViewModelFactory { app, _ -> HistoryViewModel(app) }
    }
}

@Composable
fun HistoryScreen(contentPadding: PaddingValues, vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.section_habits)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.section_medications)) })
        }
        val bottom = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)
        if (tab == 0) HabitHistory(state, bottom) else MedicationHistory(state, bottom)
    }
}

@Composable
private fun HabitHistory(state: HistoryState, padding: PaddingValues) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    LazyColumn(contentPadding = padding) {
        if (!state.loading && !state.hasHabits) {
            item { EmptyState(stringResource(R.string.history_no_habits_title), stringResource(R.string.history_no_habits_body)) }
            return@LazyColumn
        }
        item { SectionHeader(stringResource(R.string.section_activity)) }
        item {
            Heatmap(
                cells = state.cells,
                end = state.today,
                weeks = HistoryViewModel.HEATMAP_WEEKS,
                firstDayOfWeek = state.firstDayOfWeek,
                color = MaterialTheme.colorScheme.primary,
                selected = selected,
                onSelect = { selected = it },
            )
            selected?.let { date ->
                Text(
                    state.cells[date]?.description.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(rateText(state.thisWeek), stringResource(R.string.this_week_vs, rateText(state.lastWeek)), Modifier.weight(1f))
                StatTile(rateText(state.thisMonth), stringResource(R.string.this_month_vs, rateText(state.lastMonth)), Modifier.weight(1f))
            }
        }
        item { SectionHeader(stringResource(R.string.section_per_habit)) }
        items(state.habitRows, key = { it.id }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ColorDot(row.colorArgb, Modifier.padding(end = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge)
                    row.extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        context.resources.getQuantityString(row.unitPlural, row.current, row.current),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOfNotNull(
                            stringResource(R.string.best_short, row.best),
                            row.rate30?.let { stringResource(R.string.rate_30_short, (it * 100).toInt()) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.quantities.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_quantities)) }
            items(state.quantities, key = { "q" + it.name }) { q -> QuantityChart(q) }
        }
    }
}

@Composable
private fun QuantityChart(series: QuantitySeries) {
    val context = LocalContext.current
    val color = Color(series.colorArgb)
    val max = maxOf(series.values.maxOfOrNull { it.second } ?: 0.0, series.target, 1.0)
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val targetColor = MaterialTheme.colorScheme.onSurfaceVariant
    val description = series.values.joinToString("; ") { (d, v) -> "${Formats.dateMedium(context, d)} ${v.formatQuantity()} ${series.unit}" }
    AppCard {
        Text(series.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.quantity_chart_caption, series.target.formatQuantity(), series.unit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp).semantics { contentDescription = description }) {
            val n = series.values.size.coerceAtLeast(1)
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (n - 1)) / n
            series.values.forEachIndexed { i, (_, v) ->
                val x = i * (barWidth + gap)
                drawRoundRect(track, topLeft = Offset(x, 0f), size = Size(barWidth, size.height), cornerRadius = CornerRadius(6f, 6f))
                val h = (v / max * size.height).toFloat()
                drawRoundRect(color, topLeft = Offset(x, size.height - h), size = Size(barWidth, h), cornerRadius = CornerRadius(6f, 6f))
            }
            val ty = (size.height - series.target / max * size.height).toFloat()
            drawLine(targetColor, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 2f)
        }
    }
}

@Composable
private fun MedicationHistory(state: HistoryState, padding: PaddingValues) {
    val context = LocalContext.current
    LazyColumn(contentPadding = padding) {
        if (!state.loading && !state.hasMeds) {
            item { EmptyState(stringResource(R.string.history_no_meds_title), stringResource(R.string.history_no_meds_body)) }
            return@LazyColumn
        }
        item { SectionHeader(stringResource(R.string.section_adherence)) }
        item {
            AdherenceCard(stringResource(R.string.last_7_days), state.adherence7)
            AdherenceCard(stringResource(R.string.last_30_days), state.adherence30)
        }
        item { SectionHeader(stringResource(R.string.section_event_log)) }
        if (state.events.isEmpty()) {
            item { Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        items(state.events, key = { it.event.id }) { logged ->
            val ev = logged.event
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                val scheduled = listOfNotNull(ev.scheduledDate?.let { Formats.dateMedium(context, it) }, ev.scheduledTime?.let { Formats.time(context, it) }).joinToString(" ")
                Text(
                    "${logged.medicationName} · ${MedicationPresentation.eventTypeText(context, ev.type)} · $scheduled",
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (ev.isActive) null else TextDecoration.LineThrough,
                )
                Text(
                    listOfNotNull(
                        stringResource(R.string.recorded_at, Formats.dateTime(context, ev.recordedAt)),
                        MedicationPresentation.sourceText(context, ev.source),
                        stringResource(R.string.corrected).takeIf { !ev.isActive },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdherenceCard(title: String, a: Adherence) {
    AppCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.adherence_counts, a.taken, a.skipped, a.missed),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            a.takenRate?.let { stringResource(R.string.adherence_rate, (it * 100).toInt()) } ?: stringResource(R.string.not_enough_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun rateText(r: RateSummary): String = r.rate?.let { "${(it * 100).toInt()}%" } ?: "—"
