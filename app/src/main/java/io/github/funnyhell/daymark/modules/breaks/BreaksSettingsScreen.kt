package io.github.funnyhell.daymark.modules.breaks

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TimePickerDialog
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class BreaksSettingsViewModel(application: Application) : AppViewModel(application) {
    private val store = BreaksStore(application)
    val state: StateFlow<BreakState> = store.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BreakState())

    fun update(plan: BreakPlan) = launchSafely {
        store.setPlan(plan)
        container.reminders.reschedule()
    }

    companion object {
        val Factory = appViewModelFactory { app, _ -> BreaksSettingsViewModel(app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BreaksSettingsScreen(onBack: () -> Unit, vm: BreaksSettingsViewModel = viewModel(factory = BreaksSettingsViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val plan = state.plan
    val context = LocalContext.current
    var editFrom by remember { mutableStateOf(false) }
    var editUntil by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_breaks)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.module_breaks_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SectionHeader(stringResource(R.string.breaks_interval))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BreakPlan.INTERVAL_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = plan.intervalMinutes == minutes,
                        onClick = { vm.update(plan.copy(intervalMinutes = minutes)) },
                        label = { Text(pluralStringResource(R.plurals.minutes_short, minutes, minutes)) },
                    )
                }
            }
            SectionHeader(stringResource(R.string.breaks_hours))
            TimeRow(stringResource(R.string.breaks_from), Formats.time(context, plan.from)) { editFrom = true }
            TimeRow(stringResource(R.string.breaks_until), Formats.time(context, plan.until)) { editUntil = true }
            if (!plan.until.isAfter(plan.from)) {
                Text(
                    stringResource(R.string.breaks_hours_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            SectionHeader(stringResource(R.string.breaks_days))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val on = day in plan.days
                    FilterChip(
                        selected = on,
                        onClick = { vm.update(plan.copy(days = if (on) plan.days - day else plan.days + day)) },
                        label = { Text(Formats.dayShort(context, day)) },
                    )
                }
            }
        }
    }
    if (editFrom) TimePickerDialog(plan.from, onConfirm = { vm.update(plan.copy(from = it)); editFrom = false }, onDismiss = { editFrom = false })
    if (editUntil) TimePickerDialog(plan.until, onConfirm = { vm.update(plan.copy(until = it)); editUntil = false }, onDismiss = { editUntil = false })
}

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
