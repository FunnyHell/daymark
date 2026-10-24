package io.github.funnyhell.daymark.ui.habits

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.habit.CleanRun
import io.github.funnyhell.daymark.domain.habit.DayResult
import io.github.funnyhell.daymark.domain.habit.DayState
import io.github.funnyhell.daymark.domain.habit.HabitData
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.domain.habit.HabitEvent
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.PeriodOutcome
import io.github.funnyhell.daymark.domain.habit.WeekResult
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.ConfirmDialog
import io.github.funnyhell.daymark.ui.common.HeatCell
import io.github.funnyhell.daymark.ui.common.Heatmap
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.common.NumberInputDialog
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.StatTile
import io.github.funnyhell.daymark.ui.common.TrackingViewModel
import io.github.funnyhell.daymark.ui.common.UiMessage
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class HabitDetailState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val data: HabitData? = null,
    val today: LocalDate = LocalDate.now(),
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val scheduleText: String = "",
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val rate30: Double? = null,
    val totalText: String = "",
    val cells: Map<LocalDate, HeatCell> = emptyMap(),
    val selected: LocalDate = LocalDate.now(),
    val selectedDay: DayResult? = null,
    val selectedEvents: List<HabitEvent> = emptyList(),
    val weeks: List<WeekResult> = emptyList(),
    val cleanRuns: List<CleanRun> = emptyList(),
    val recentEvents: List<HabitEvent> = emptyList(),
)

class HabitDetailViewModel(application: Application, handle: SavedStateHandle) : TrackingViewModel(application) {
    val habitId: String = checkNotNull(handle["id"])
    private val selectedDate = MutableStateFlow(container.time.today())
    var deleted by mutableStateOf(false)
        private set

    val state: StateFlow<HabitDetailState> = combine(
        container.habits.observe(habitId), container.settings.settings, selectedDate,
    ) { data, settings, selected ->
        if (data == null) return@combine HabitDetailState(loading = false, missing = true)
        val today = container.time.today()
        val e = HabitEvaluator(data, settings.firstDayOfWeek)
        val streak = e.streaks(today)
        val rate = e.completionRate(today.minusDays(29), today, today).rate
        val heatStart = today.minusWeeks(HEATMAP_WEEKS.toLong())
        val cells = e.days(heatStart, today, today).associate { day -> day.date to cell(data, day) }
        val selectedDay = e.dayResult(selected, today)
        HabitDetailState(
            loading = false,
            data = data,
            today = today,
            firstDayOfWeek = settings.firstDayOfWeek,
            scheduleText = HabitPresentation.scheduleText(app, data, today),
            currentStreak = streak.current,
            bestStreak = streak.best,
            rate30 = rate,
            totalText = when (data.habit.type) {
                HabitType.QUANTITY -> "${e.totalProgress().formatQuantity()} ${data.habit.unit.orEmpty()}"
                HabitType.ABSTINENCE -> e.lapses().size.toString()
                else -> e.totalProgress().formatQuantity()
            },
            cells = cells,
            selected = selected,
            selectedDay = selectedDay,
            selectedEvents = data.events.filter { it.date == selected }.sortedBy { it.recordedAt },
            weeks = if (data.habit.type == HabitType.WEEKLY_GOAL) e.weeks(today.minusWeeks(11), today).reversed() else emptyList(),
            cleanRuns = e.cleanRuns(today).reversed(),
            recentEvents = data.events.sortedByDescending { it.recordedAt }.take(30),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitDetailState())

    private fun cell(data: HabitData, day: DayResult): HeatCell {
        val level: Int? = when (day.state) {
            DayState.DONE -> if (data.habit.type == HabitType.ABSTINENCE) 2 else 4
            DayState.PARTIAL -> if (day.fraction >= 0.5) 2 else 1
            DayState.MISSED, DayState.LAPSED -> 0
            DayState.PENDING -> if (day.progress > 0) 1 else null
            else -> null
        }
        val description = "${Formats.dateMedium(app, day.date)}: ${HabitPresentation.dayStateText(app, day.state)}"
        return HeatCell(day.date, level, description, marker = day.state == DayState.LAPSED || day.state == DayState.PAUSED)
    }

    fun select(date: LocalDate) {
        selectedDate.value = date
    }

    fun pause() = launchSafely { container.habits.pause(habitId); say(string(R.string.msg_paused)) }
    fun resume() = launchSafely { container.habits.resume(habitId); say(string(R.string.msg_resumed)) }
    fun archive() = launchSafely { container.habits.archive(habitId); say(string(R.string.msg_archived_done)) }
    fun delete() = launchSafely {
        container.habits.delete(habitId)
        deleted = true
    }

    companion object {
        const val HEATMAP_WEEKS = 20
        val Factory = appViewModelFactory { app, handle -> HabitDetailViewModel(app, handle) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    showMessage: suspend (UiMessage) -> Unit,
    vm: HabitDetailViewModel = viewModel(factory = HabitDetailViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    MessageEffect(vm.messages, showMessage)
    LaunchedEffect(vm.deleted, state.missing) { if (vm.deleted || state.missing) onBack() }
    val context = LocalContext.current
    val data = state.data
    var menu by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var correctEvent by remember { mutableStateOf<HabitEvent?>(null) }
    var amountDialog by remember { mutableStateOf(false) }
    var lapseDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data?.habit?.name.orEmpty(), maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (data != null) {
                        IconButton(onClick = { onEdit(vm.habitId) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            when (data.habit.status) {
                                LifecycleStatus.ACTIVE -> {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pause)) }, onClick = { menu = false; vm.pause() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.archive)) }, onClick = { menu = false; confirmArchive = true })
                                }
                                LifecycleStatus.PAUSED -> {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.resume)) }, onClick = { menu = false; vm.resume() })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.archive)) }, onClick = { menu = false; confirmArchive = true })
                                }
                                LifecycleStatus.ARCHIVED ->
                                    DropdownMenuItem(text = { Text(stringResource(R.string.unarchive)) }, onClick = { menu = false; vm.resume() })
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_permanently), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (data == null) return@Scaffold
        val habit = data.habit
        val color = Color(habit.colorArgb)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${HabitPresentation.typeLabel(context, habit.type)} · ${state.scheduleText}", style = MaterialTheme.typography.bodyMedium)
                    habit.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (habit.status != LifecycleStatus.ACTIVE) {
                        Text(
                            stringResource(if (habit.status == LifecycleStatus.PAUSED) R.string.habit_paused_note else R.string.habit_archived_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            item {
                val unitLabel = if (habit.type == HabitType.WEEKLY_GOAL) R.plurals.unit_weeks else R.plurals.unit_days
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(
                        context.resources.getQuantityString(unitLabel, state.currentStreak, state.currentStreak),
                        stringResource(if (habit.type == HabitType.ABSTINENCE) R.string.stat_clean_streak else R.string.stat_current_streak),
                        Modifier.weight(1f),
                    )
                    StatTile(
                        context.resources.getQuantityString(unitLabel, state.bestStreak, state.bestStreak),
                        stringResource(R.string.stat_best_streak),
                        Modifier.weight(1f),
                    )
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile(state.rate30?.let { "${(it * 100).toInt()}%" } ?: "—", stringResource(R.string.stat_rate_30), Modifier.weight(1f))
                    StatTile(state.totalText, stringResource(if (habit.type == HabitType.ABSTINENCE) R.string.stat_lapses else R.string.stat_total), Modifier.weight(1f))
                }
            }
            item { SectionHeader(stringResource(R.string.section_calendar)) }
            item {
                Heatmap(
                    cells = state.cells,
                    end = state.today,
                    weeks = HabitDetailViewModel.HEATMAP_WEEKS,
                    firstDayOfWeek = state.firstDayOfWeek,
                    color = color,
                    selected = state.selected,
                    onSelect = vm::select,
                )
            }
            item {
                val day = state.selectedDay
                AppCard(Modifier.padding(top = 8.dp)) {
                    Text(Formats.dateFull(context, state.selected), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (day != null) {
                        val progress = when (habit.type) {
                            HabitType.QUANTITY -> " · ${day.progress.formatQuantity()}/${day.target.formatQuantity()} ${habit.unit.orEmpty()}"
                            HabitType.MULTI -> " · ${day.progress.formatQuantity()}/${day.target.formatQuantity()}"
                            else -> ""
                        }
                        Text(HabitPresentation.dayStateText(context, day.state) + progress, style = MaterialTheme.typography.bodyMedium)
                    }
                    state.selectedEvents.forEach { ev -> EventLine(data, ev, onCorrect = { correctEvent = ev }) }
                    val canRecord = day != null && habit.status != LifecycleStatus.ARCHIVED &&
                        day.state !in setOf(DayState.FUTURE, DayState.BEFORE_START, DayState.PAUSED)
                    if (canRecord) {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            when (habit.type) {
                                HabitType.QUANTITY -> FilledTonalButton(onClick = { amountDialog = true }) { Text(stringResource(R.string.add_amount)) }
                                HabitType.ABSTINENCE -> OutlinedButton(onClick = { lapseDialog = true }) { Text(stringResource(R.string.action_lapse)) }
                                else -> FilledTonalButton(onClick = { vm.completeHabit(vm.habitId, state.selected) }) { Text(stringResource(R.string.add_completion)) }
                            }
                            if (habit.type in setOf(HabitType.CHECK, HabitType.MULTI, HabitType.QUANTITY) && day?.state != DayState.SKIPPED && day?.state != DayState.DONE) {
                                OutlinedButton(onClick = { vm.skipHabit(vm.habitId, state.selected) }) { Text(stringResource(R.string.action_skip)) }
                            }
                        }
                    }
                }
            }
            if (state.weeks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_weeks)) }
                items(state.weeks, key = { "w" + it.weekStart }) { week ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.week_of, Formats.dateMedium(context, week.weekStart)), modifier = Modifier.weight(1f))
                        Text("${week.completions.formatQuantity()}/${week.goal.formatQuantity()} · ${outcomeText(week.outcome)}")
                    }
                }
            }
            if (habit.type == HabitType.ABSTINENCE && state.cleanRuns.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_clean_runs)) }
                items(state.cleanRuns, key = { "r" + it.start }) { run ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text("${Formats.dateMedium(context, run.start)} – ${Formats.dateMedium(context, run.endInclusive)}", modifier = Modifier.weight(1f))
                        Text(
                            context.resources.getQuantityString(R.plurals.unit_days, run.days, run.days) +
                                if (run.endedByLapse) " · " + stringResource(R.string.ended_by_lapse) else "",
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.section_history)) }
            if (state.recentEvents.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(state.recentEvents, key = { "e" + it.id }) { ev ->
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(Formats.dateMedium(context, ev.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    EventLine(data, ev, onCorrect = { correctEvent = ev })
                }
            }
        }
    }

    if (confirmArchive) {
        ConfirmDialog(
            title = stringResource(R.string.archive_title),
            text = stringResource(R.string.archive_body),
            confirmLabel = stringResource(R.string.archive),
            onConfirm = vm::archive,
            onDismiss = { confirmArchive = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_title),
            text = stringResource(R.string.delete_habit_body),
            confirmLabel = stringResource(R.string.delete_permanently),
            onConfirm = vm::delete,
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
    correctEvent?.let { ev ->
        ConfirmDialog(
            title = stringResource(R.string.correct_title),
            text = stringResource(R.string.correct_habit_body),
            confirmLabel = stringResource(R.string.correct),
            onConfirm = { vm.undoHabitEvent(ev.id) },
            onDismiss = { correctEvent = null },
        )
    }
    if (amountDialog && data != null) {
        NumberInputDialog(
            title = stringResource(R.string.add_progress_title, data.habit.name),
            label = data.habit.unit.orEmpty(),
            withNote = true,
            onConfirm = { value, note -> vm.addHabitProgress(vm.habitId, value, state.selected, note) },
            onDismiss = { amountDialog = false },
        )
    }
    if (lapseDialog && data != null) {
        LapseDialog(data.habit.name, onConfirm = { note -> vm.recordLapse(vm.habitId, state.selected, note) }, onDismiss = { lapseDialog = false })
    }
}

@Composable
private fun EventLine(data: HabitData, event: HabitEvent, onCorrect: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                HabitPresentation.eventText(context, data, event),
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (event.isActive) null else TextDecoration.LineThrough,
            )
            val recorded = stringResource(R.string.recorded_at, Formats.dateTime(context, event.recordedAt))
            val corrected = stringResource(R.string.corrected)
            val meta = listOfNotNull(recorded, event.note, corrected.takeIf { !event.isActive }).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (event.isActive) TextButton(onClick = onCorrect) { Text(stringResource(R.string.correct)) }
    }
}

@Composable
private fun outcomeText(outcome: PeriodOutcome): String = stringResource(
    when (outcome) {
        PeriodOutcome.SUCCESS -> R.string.outcome_success
        PeriodOutcome.FAILURE -> R.string.outcome_failure
        PeriodOutcome.NEUTRAL -> R.string.outcome_neutral
        PeriodOutcome.IN_PROGRESS -> R.string.outcome_in_progress
    },
)
