package io.github.funnyhell.daymark.ui.medications

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
import io.github.funnyhell.daymark.domain.medication.InventoryAdjustment
import io.github.funnyhell.daymark.domain.medication.MedicationData
import io.github.funnyhell.daymark.domain.medication.MedicationEvent
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.ConfirmDialog
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.common.NumberInputDialog
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TrackingViewModel
import io.github.funnyhell.daymark.ui.common.UiMessage
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class DayDoses(val date: LocalDate, val doses: List<DoseItem>)

data class MedicationDetailState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val data: MedicationData? = null,
    val today: LocalDate = LocalDate.now(),
    val scheduleText: String = "",
    val scheduleSince: LocalDate? = null,
    val stock: StockInfo? = null,
    val recentDays: List<DayDoses> = emptyList(),
    val events: List<MedicationEvent> = emptyList(),
    val adjustments: List<InventoryAdjustment> = emptyList(),
)

class MedicationDetailViewModel(application: Application, handle: SavedStateHandle) : TrackingViewModel(application) {
    val medicationId: String = checkNotNull(handle["id"])
    var deleted by mutableStateOf(false)
        private set

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val state: StateFlow<MedicationDetailState> = combine(container.medications.observe(medicationId), ticker) { data, _ ->
        if (data == null) return@combine MedicationDetailState(loading = false, missing = true)
        val today = container.time.today()
        val now = container.time.now()
        val zone = container.time.zone()
        val schedule = OccurrenceRules.scheduleOn(data.schedules, today) ?: data.schedules.lastOrNull()
        MedicationDetailState(
            loading = false,
            data = data,
            today = today,
            scheduleText = MedicationPresentation.scheduleText(app, schedule),
            scheduleSince = schedule?.effectiveFrom,
            stock = MedicationPresentation.stockInfo(app, data, today),
            recentDays = (0L..6L).map { today.minusDays(it) }.map { date ->
                DayDoses(date, OccurrenceRules.statusesOn(data, date, now, zone).map { MedicationPresentation.doseItem(app, data, it, zone) })
            }.filter { it.doses.isNotEmpty() },
            events = data.events.sortedByDescending { it.recordedAt }.take(60),
            adjustments = data.adjustments.sortedByDescending { it.recordedAt }.take(30),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MedicationDetailState())

    fun addPackage(quantity: Double, note: String?) = launchSafely {
        if (container.medications.addPackage(medicationId, quantity, note)) say(string(R.string.msg_package_added)) else say(string(R.string.error_number))
    }

    fun setStock(counted: Double, note: String?) = launchSafely {
        if (container.medications.setStock(medicationId, counted, note)) say(string(R.string.msg_stock_updated)) else say(string(R.string.error_number))
    }

    fun pause() = launchSafely { container.medications.pause(medicationId); say(string(R.string.msg_paused)) }
    fun resume() = launchSafely { container.medications.resume(medicationId); say(string(R.string.msg_resumed)) }
    fun archive() = launchSafely { container.medications.archive(medicationId); say(string(R.string.msg_archived_done)) }
    fun delete() = launchSafely {
        container.medications.delete(medicationId)
        deleted = true
    }

    companion object {
        val Factory = appViewModelFactory { app, handle -> MedicationDetailViewModel(app, handle) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    showMessage: suspend (UiMessage) -> Unit,
    vm: MedicationDetailViewModel = viewModel(factory = MedicationDetailViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    MessageEffect(vm.messages, showMessage)
    LaunchedEffect(vm.deleted, state.missing) { if (vm.deleted || state.missing) onBack() }
    val context = LocalContext.current
    val data = state.data
    var menu by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var correctEvent by remember { mutableStateOf<String?>(null) }
    var packageDialog by remember { mutableStateOf(false) }
    var stockDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(data?.medication?.name.orEmpty(), maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (data != null) {
                        IconButton(onClick = { onEdit(vm.medicationId) }) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more_actions)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            when (data.medication.status) {
                                LifecycleStatus.ACTIVE -> {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.pause_reminders)) }, onClick = { menu = false; vm.pause() })
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
        val med = data.medication
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    med.strength?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    Text(state.scheduleText, style = MaterialTheme.typography.bodyMedium)
                    state.scheduleSince?.let {
                        Text(stringResource(R.string.schedule_since, Formats.dateMedium(context, it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    med.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (med.status != LifecycleStatus.ACTIVE) {
                        Text(
                            stringResource(if (med.status == LifecycleStatus.PAUSED) R.string.med_paused_note else R.string.med_archived_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            state.stock?.let { stock ->
                item { SectionHeader(stringResource(R.string.section_inventory)) }
                item {
                    AppCard {
                        Text(stock.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                            color = if (stock.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (stock.low) Text(stringResource(R.string.low_stock_reached), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        stock.daysText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        med.lowStockThreshold?.let {
                            Text(stringResource(R.string.threshold_is, it.formatQuantity(), med.inventoryUnit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.stock_estimate_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            OutlinedButton(onClick = { stockDialog = true }) { Text(stringResource(R.string.set_stock)) }
                            FilledTonalButton(onClick = { packageDialog = true }) { Text(stringResource(R.string.add_package)) }
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.section_recent_doses)) }
            if (state.recentDays.isEmpty()) {
                item { Text(stringResource(R.string.no_recent_doses), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            state.recentDays.forEach { day ->
                item(key = "d" + day.date) {
                    Text(
                        Formats.dateFull(context, day.date),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                items(day.doses, key = { "o" + it.occurrenceKey }) { dose ->
                    DoseRow(
                        item = dose,
                        onTake = { vm.takeDose(dose.medicationId, dose.occurrenceKey, dose.name) },
                        onSkip = { vm.skipDose(dose.medicationId, dose.occurrenceKey, dose.name) },
                        onSnooze = if (dose.date == state.today) ({ vm.snoozeDose(dose.medicationId, dose.occurrenceKey) }) else null,
                        onCorrect = { correctEvent = it },
                        showName = false,
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.section_event_log)) }
            if (state.events.isEmpty()) {
                item { Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            items(state.events, key = { "e" + it.id }) { ev -> MedicationEventLine(ev, onCorrect = { correctEvent = ev.id }) }
            if (state.adjustments.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.section_inventory_log)) }
                items(state.adjustments, key = { "a" + it.id }) { adj ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(MedicationPresentation.adjustmentText(context, adj.type), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(Formats.dateTime(context, adj.recordedAt), adj.note).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text((if (adj.delta > 0) "+" else "") + adj.delta.formatQuantity(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
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

    if (confirmArchive) {
        ConfirmDialog(stringResource(R.string.archive_title), stringResource(R.string.archive_med_body), stringResource(R.string.archive), vm::archive, { confirmArchive = false })
    }
    if (confirmDelete) {
        ConfirmDialog(
            stringResource(R.string.delete_title), stringResource(R.string.delete_med_body), stringResource(R.string.delete_permanently),
            vm::delete, { confirmDelete = false }, destructive = true,
        )
    }
    correctEvent?.let { id ->
        ConfirmDialog(
            stringResource(R.string.correct_title), stringResource(R.string.correct_med_body), stringResource(R.string.correct),
            { vm.undoMedicationEvent(id) }, { correctEvent = null },
        )
    }
    if (packageDialog && data != null) {
        NumberInputDialog(
            title = stringResource(R.string.add_package),
            label = stringResource(R.string.package_quantity, data.medication.inventoryUnit),
            withNote = true,
            onConfirm = vm::addPackage,
            onDismiss = { packageDialog = false },
        )
    }
    if (stockDialog && data != null) {
        NumberInputDialog(
            title = stringResource(R.string.set_stock),
            label = stringResource(R.string.counted_quantity, data.medication.inventoryUnit),
            initial = state.stock?.stock?.formatQuantity().orEmpty(),
            allowZero = true,
            withNote = true,
            onConfirm = vm::setStock,
            onDismiss = { stockDialog = false },
        )
    }
}

@Composable
private fun MedicationEventLine(event: MedicationEvent, onCorrect: () -> Unit) {
    val context = LocalContext.current
    val scheduled = listOfNotNull(event.scheduledDate?.let { Formats.dateMedium(context, it) }, event.scheduledTime?.let { Formats.time(context, it) })
        .joinToString(" ")
    val recorded = stringResource(R.string.recorded_at, Formats.dateTime(context, event.recordedAt))
    val corrected = stringResource(R.string.corrected)
    val snoozed = event.snoozedUntil?.let { stringResource(R.string.snoozed_until, Formats.time(context, it)) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${MedicationPresentation.eventTypeText(context, event.type)} · $scheduled",
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (event.isActive) null else TextDecoration.LineThrough,
            )
            Text(
                listOfNotNull(recorded, MedicationPresentation.sourceText(context, event.source), snoozed, corrected.takeIf { !event.isActive }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.isActive && event.isResolution) TextButton(onClick = onCorrect) { Text(stringResource(R.string.correct)) }
    }
}
