package io.github.funnyhell.daymark.ui.medications

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.ColorDot
import io.github.funnyhell.daymark.ui.common.EmptyState
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MedicationListItem(
    val id: String,
    val name: String,
    val strength: String?,
    val colorArgb: Long,
    val scheduleText: String,
    val stock: StockInfo?,
    val status: LifecycleStatus,
)

data class MedicationsState(val loading: Boolean = true, val items: List<MedicationListItem> = emptyList())

class MedicationsViewModel(application: Application) : AppViewModel(application) {
    val state: StateFlow<MedicationsState> = container.medications.observeAll().map { meds ->
        val today = container.time.today()
        MedicationsState(
            loading = false,
            items = meds.map { data ->
                MedicationListItem(
                    id = data.medication.id,
                    name = data.medication.name,
                    strength = data.medication.strength,
                    colorArgb = data.medication.colorArgb,
                    scheduleText = MedicationPresentation.scheduleText(app, OccurrenceRules.scheduleOn(data.schedules, today) ?: data.schedules.lastOrNull()),
                    stock = MedicationPresentation.stockInfo(app, data, today),
                    status = data.medication.status,
                )
            },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationsState())

    companion object {
        val Factory = appViewModelFactory { app, _ -> MedicationsViewModel(app) }
    }
}

@Composable
fun MedicationsScreen(
    contentPadding: PaddingValues,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    vm: MedicationsViewModel = viewModel(factory = MedicationsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 88.dp)) {
        if (!state.loading && state.items.isEmpty()) {
            item {
                EmptyState(stringResource(R.string.meds_empty_title), stringResource(R.string.meds_empty_body)) {
                    Button(onClick = onNew, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.new_medication)) }
                }
            }
        }
        for ((status, title) in listOf(LifecycleStatus.ACTIVE to R.string.status_active, LifecycleStatus.PAUSED to R.string.status_paused)) {
            val list = state.items.filter { it.status == status }
            if (list.isEmpty()) continue
            item(key = "m-$status") { SectionHeader(stringResource(title)) }
            items(list, key = { it.id }) { MedicationRow(it, onOpen) }
        }
        val archived = state.items.filter { it.status == LifecycleStatus.ARCHIVED }
        if (archived.isNotEmpty()) {
            item(key = "m-archived") {
                SectionHeader(stringResource(R.string.status_archived)) {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(stringResource(if (showArchived) R.string.hide else R.string.show_count, archived.size))
                    }
                }
            }
            if (showArchived) items(archived, key = { it.id }) { MedicationRow(it, onOpen) }
        }
        if (state.items.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.medical_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@Composable
private fun MedicationRow(item: MedicationListItem, onOpen: (String) -> Unit) {
    AppCard(onClick = { onOpen(item.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(item.colorArgb, size = 14)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(item.name, item.strength).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(item.scheduleText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.stock?.let { stock ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (stock.low) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 4.dp))
                        }
                        Text(
                            if (stock.low) stringResource(R.string.low_stock_label, stock.text) else stock.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stock.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
