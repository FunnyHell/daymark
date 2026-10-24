package io.github.funnyhell.daymark.ui.today

import android.app.Application
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class LowStockItem(val medicationId: String, val text: String)

data class TodayState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val doses: List<DoseItem> = emptyList(),
    val habits: List<HabitTodayItem> = emptyList(),
    val lowStock: List<LowStockItem> = emptyList(),
    val hasAnyRecords: Boolean = false,
    val hasReminders: Boolean = false,
) {
    val doneCount: Int get() = doses.count { it.resolved } + habits.count { it.done }
    val totalCount: Int get() = doses.size + habits.size
}

class TodayViewModel(application: Application) : TrackingViewModel(application) {
    /** Re-evaluates states once a minute (due → missed, day rollover) without polling the database. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val state: StateFlow<TodayState> = combine(
        container.habits.observeAll(), container.medications.observeAll(), container.settings.settings, ticker,
    ) { habits, meds, settings, _ ->
        val now = container.time.now()
        val zone = container.time.zone()
        val today = container.time.today()
        val activeMeds = meds.filter { it.medication.status == LifecycleStatus.ACTIVE }
        TodayState(
            loading = false,
            date = today,
            doses = MedicationPresentation.dosesOn(app, activeMeds, today, now, zone),
            habits = habits.mapNotNull { HabitPresentation.todayItem(app, it, today, settings.firstDayOfWeek) },
            lowStock = activeMeds.mapNotNull { data ->
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
    LazyColumn(contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(Formats.dateFull(context, state.date), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (state.totalCount > 0) {
                    Text(
                        stringResource(R.string.today_progress, state.doneCount, state.totalCount),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            items(state.doses, key = { "dose" + it.occurrenceKey }) { dose ->
                DoseRow(
                    item = dose,
                    onTake = { vm.takeDose(dose.medicationId, dose.occurrenceKey, dose.name) },
                    onSkip = { vm.skipDose(dose.medicationId, dose.occurrenceKey, dose.name) },
                    onSnooze = { vm.snoozeDose(dose.medicationId, dose.occurrenceKey) },
                    onCorrect = { vm.undoMedicationEvent(it) },
                    onOpen = { onOpenMedication(dose.medicationId) },
                )
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
                onComplete = { vm.completeHabit(habit.habitId) },
                onAddAmount = { vm.addHabitProgress(habit.habitId, it) },
                onSkip = { vm.skipHabit(habit.habitId) },
                onLapse = { note -> vm.recordLapse(habit.habitId, note = note) },
                onUndo = { vm.undoHabitEvent(it) },
            )
        }
    }
}
