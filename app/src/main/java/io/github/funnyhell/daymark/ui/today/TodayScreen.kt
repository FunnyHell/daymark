package io.github.funnyhell.daymark.ui.today

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.EmptyState
import io.github.funnyhell.daymark.ui.common.PermissionBanners
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TrackingViewModel
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.format.Formats
import io.github.funnyhell.daymark.ui.habits.HabitPresentation
import io.github.funnyhell.daymark.ui.habits.HabitTodayItem
import io.github.funnyhell.daymark.ui.habits.HabitTodayRow
import io.github.funnyhell.daymark.ui.medications.DoseItem
import io.github.funnyhell.daymark.ui.medications.DoseRow
import io.github.funnyhell.daymark.ui.medications.MedicationPresentation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class LowStockItem(val medicationId: String, val text: String)

data class TodayState(
    val loading: Boolean = true,
    /** The day shown: today, or an earlier day chosen to fill in what was not marked then. */
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val doses: List<DoseItem> = emptyList(),
    val habits: List<HabitTodayItem> = emptyList(),
    val lowStock: List<LowStockItem> = emptyList(),
    val hasAnyRecords: Boolean = false,
    val hasReminders: Boolean = false,
) {
    val doneCount: Int get() = doses.count { it.resolved } + habits.count { it.done }
    val totalCount: Int get() = doses.size + habits.size
    val isToday: Boolean get() = date == today
}

class TodayViewModel(application: Application) : TrackingViewModel(application) {
    /** Re-evaluates states once a minute (due → missed, day rollover) without polling the database. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /** The earlier day being shown; null follows today (also across midnight). */
    private val chosenDay = MutableStateFlow<LocalDate?>(null)

    /** Shows [date]; today or a later day goes back to following today. */
    fun showDay(date: LocalDate?) {
        chosenDay.value = date?.takeIf { it.isBefore(container.time.today()) }
    }

    val state: StateFlow<TodayState> = combine(
        container.habits.observeAll(), container.medications.observeAll(), container.settings.settings, ticker, chosenDay,
    ) { habits, meds, settings, _, chosen ->
        val now = container.time.now()
        val zone = container.time.zone()
        val realToday = container.time.today()
        val today = chosen ?: realToday
        val activeMeds = meds.filter { it.medication.status == LifecycleStatus.ACTIVE }
        TodayState(
            loading = false,
            date = today,
            today = realToday,
            doses = MedicationPresentation.dosesOn(app, activeMeds, today, now, zone),
            habits = habits.mapNotNull { HabitPresentation.todayItem(app, it, today, settings.firstDayOfWeek) },
            lowStock = activeMeds.takeIf { chosen == null }.orEmpty().mapNotNull { data ->
                MedicationPresentation.stockInfo(app, data, today)?.takeIf { it.low }?.let {
                    LowStockItem(data.medication.id, string(R.string.low_stock_banner, data.medication.name, it.text))
                }
            },
            hasAnyRecords = habits.isNotEmpty() || meds.isNotEmpty(),
            hasReminders = activeMeds.any { m -> m.schedules.any { it.doses.isNotEmpty() } } ||
                habits.any { it.habit.reminderTime != null && it.habit.status == LifecycleStatus.ACTIVE },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    companion object {
        val Factory = appViewModelFactory { app, _ -> TodayViewModel(app) }
    }
}

@Composable
fun TodayScreen(
    contentPadding: PaddingValues,
    onOpenHabit: (String) -> Unit,
    onOpenMedication: (String) -> Unit,
    onNewHabit: () -> Unit,
    onNewMedication: () -> Unit,
    showMessage: suspend (io.github.funnyhell.daymark.ui.common.UiMessage) -> Unit,
    vm: TodayViewModel = viewModel(factory = TodayViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    MessageEffect(vm.messages, showMessage)
    if (state.loading) {
        Column(Modifier.fillMaxSize().padding(contentPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator()
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var pickDay by remember { mutableStateOf(false) }
    if (pickDay) {
        PastDayPicker(
            shown = state.date,
            today = state.today,
            onPick = { vm.showDay(it); pickDay = false },
            onDismiss = { pickDay = false },
        )
    }
    val day = state.date.takeIf { !state.isToday }
    LazyColumn(contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.pick_day)) { pickDay = true }) {
                        Text(Formats.dateFull(context, state.date), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        if (state.totalCount > 0) {
                            Text(
                                stringResource(R.string.today_progress, state.doneCount, state.totalCount),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { vm.showDay(state.date.minusDays(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous_day))
                    }
                    IconButton(onClick = { vm.showDay(state.date.plusDays(1)) }, enabled = !state.isToday) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next_day))
                    }
                }
                if (!state.isToday) {
                    AssistChip(onClick = { vm.showDay(null) }, label = { Text(stringResource(R.string.back_to_today)) })
                }
            }
        }
        item { PermissionBanners(needed = state.hasReminders) }
        items(state.lowStock, key = { "low" + it.medicationId }) { low ->
            AppCard(onClick = { onOpenMedication(low.medicationId) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(low.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (!state.hasAnyRecords) {
            item {
                EmptyState(
                    title = stringResource(R.string.today_empty_title),
                    body = stringResource(R.string.today_empty_body),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = onNewHabit) { Text(stringResource(R.string.new_habit)) }
                        OutlinedButton(onClick = onNewMedication) { Text(stringResource(R.string.new_medication)) }
                    }
                }
            }
            return@LazyColumn
        }
        if (state.doses.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.section_medications)) }
            state.doses.groupBy { it.time }.forEach { (time, group) ->
                val open = group.filter { !it.resolved }
                if (group.size > 1) {
                    item(key = "group$time") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.dose_group, group.first().timeText, group.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (open.size > 1) {
                                TextButton(onClick = { vm.takeAll(open) }) { Text(stringResource(R.string.take_all)) }
                            }
                        }
                    }
                }
                doseItems(group, vm, onOpenMedication, canSnooze = state.isToday)
            }
        }
        item { SectionHeader(stringResource(R.string.section_habits)) }
        if (state.habits.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.today_no_habits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
        }
        items(state.habits, key = { "habit" + it.habitId }) { habit ->
            HabitTodayRow(
                item = habit,
                onOpen = { onOpenHabit(habit.habitId) },
                onSetDone = { done -> vm.setHabitDone(habit.habitId, done, day) },
                onAddAmount = { vm.addHabitProgress(habit.habitId, it, day) },
                onSkip = { vm.skipHabit(habit.habitId, day) },
                onLapse = { note -> vm.recordLapse(habit.habitId, day, note) },
                onUndo = { vm.undoHabitEvent(it) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.doseItems(
    doses: List<DoseItem>,
    vm: TodayViewModel,
    onOpenMedication: (String) -> Unit,
    canSnooze: Boolean,
) {
    items(doses, key = { "dose" + it.occurrenceKey }) { dose ->
        DoseRow(
            item = dose,
            onTake = { vm.takeDose(dose.medicationId, dose.occurrenceKey, dose.name) },
            onSkip = { vm.skipDose(dose.medicationId, dose.occurrenceKey, dose.name) },
            onSnooze = if (canSnooze) ({ vm.snoozeDose(dose.medicationId, dose.occurrenceKey) }) else null,
            onCorrect = { vm.undoMedicationEvent(it) },
            onOpen = { onOpenMedication(dose.medicationId) },
        )
    }
}

/** A calendar limited to today and earlier days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PastDayPicker(shown: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    fun millis(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val lastDay = millis(today)
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = millis(shown),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= lastDay
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = picker.selectedDateMillis != null,
                onClick = { picker.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } },
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = picker)
    }
}
