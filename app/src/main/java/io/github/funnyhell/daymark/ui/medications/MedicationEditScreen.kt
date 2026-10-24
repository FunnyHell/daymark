package io.github.funnyhell.daymark.ui.medications

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.MedicationDraftException
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.medication.DoseDraft
import io.github.funnyhell.daymark.domain.medication.InventoryRules
import io.github.funnyhell.daymark.domain.medication.MedicationDraft
import io.github.funnyhell.daymark.domain.medication.MedicationDraftError
import io.github.funnyhell.daymark.domain.medication.MedicationDraftValidator
import io.github.funnyhell.daymark.domain.medication.OccurrenceRules
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.ColorPicker
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TimePickerDialog
import io.github.funnyhell.daymark.ui.common.UiMessage
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.common.parseNumber
import io.github.funnyhell.daymark.ui.common.rememberQuietNotificationPermissionRequest
import io.github.funnyhell.daymark.ui.format.Formats
import io.github.funnyhell.daymark.ui.theme.ItemColors
import java.time.DayOfWeek
import java.time.LocalTime

/** Editable dose row; quantity is kept as text until saved so invalid input can be shown. */
data class DoseInput(val time: LocalTime, val label: String, val quantityText: String)

class MedicationEditViewModel(application: Application, handle: SavedStateHandle) : AppViewModel(application) {
    val medicationId: String? = handle.get<String>("id")?.takeIf { it.isNotBlank() }
    val isNew = medicationId == null

    var loaded by mutableStateOf(isNew)
    var name by mutableStateOf("")
    var strength by mutableStateOf("")
    var notes by mutableStateOf("")
    var color by mutableStateOf(ItemColors[1])
    var days by mutableStateOf(DayOfWeek.entries.toSet())
    var doses by mutableStateOf(listOf(DoseInput(LocalTime.of(8, 0), "", "1")))
    var inventoryEnabled by mutableStateOf(false)
    var unit by mutableStateOf("")
    var stockText by mutableStateOf("")
    var currentStockText by mutableStateOf<String?>(null)
    var thresholdText by mutableStateOf("")
    var lowStockAlerts by mutableStateOf(true)
    var errors by mutableStateOf<List<MedicationDraftError>>(emptyList())
    var editingNote by mutableStateOf<String?>(null)
    var saved by mutableStateOf(false)

    init {
        if (unit.isEmpty()) unit = string(R.string.default_unit)
        if (medicationId != null) launchSafely {
            val data = container.medications.get(medicationId) ?: return@launchSafely
            val m = data.medication
            name = m.name
            strength = m.strength.orEmpty()
            notes = m.notes.orEmpty()
            color = m.colorArgb
            inventoryEnabled = m.inventoryEnabled
            unit = m.inventoryUnit.ifBlank { string(R.string.default_unit) }
            thresholdText = m.lowStockThreshold?.formatQuantity().orEmpty()
            lowStockAlerts = m.lowStockAlerts
            if (data.adjustments.isNotEmpty()) currentStockText = InventoryRules.stock(data.adjustments).formatQuantity()
            val today = container.time.today()
            val schedule = OccurrenceRules.scheduleOn(data.schedules, today) ?: data.schedules.lastOrNull()
            if (schedule != null) {
                days = schedule.days
                doses = schedule.doses.map { DoseInput(it.time, it.doseLabel, it.quantity.formatQuantity()) }
                val effective = OccurrenceRules.editEffectiveDate(data, today)
                if (effective.isAfter(today)) editingNote = string(R.string.med_edit_tomorrow_note)
            }
            loaded = true
        }
    }

    fun addDose() {
        val last = doses.maxOfOrNull { it.time } ?: LocalTime.of(8, 0)
        var next = last.plusHours(4)
        while (doses.any { it.time == next }) next = next.plusMinutes(30)
        doses = doses + DoseInput(next, doses.lastOrNull()?.label.orEmpty(), doses.lastOrNull()?.quantityText ?: "1")
    }

    fun updateDose(index: Int, dose: DoseInput) {
        doses = doses.toMutableList().also { it[index] = dose }
    }

    fun removeDose(index: Int) {
        doses = doses.toMutableList().also { it.removeAt(index) }
    }

    fun save() {
        val parsedDoses = doses.map { DoseDraft(it.time, it.label.trim(), parseNumber(it.quantityText) ?: Double.NaN) }
        val threshold = thresholdText.takeIf { it.isNotBlank() }?.let { parseNumber(it) ?: Double.NaN }
        val stock = stockText.takeIf { it.isNotBlank() }?.let { parseNumber(it) ?: Double.NaN }
        val draft = MedicationDraft(
            name = name, strength = strength, notes = notes, colorArgb = color, days = days, doses = parsedDoses,
            inventoryEnabled = inventoryEnabled, inventoryUnit = unit, lowStockThreshold = threshold, lowStockAlerts = lowStockAlerts,
            initialStock = stock,
        )
        errors = MedicationDraftValidator.validate(draft)
        if (errors.isNotEmpty()) return
        launchSafely {
            try {
                if (medicationId == null) container.medications.create(draft) else container.medications.update(medicationId, draft)
                saved = true
            } catch (e: MedicationDraftException) {
                errors = e.errors
            }
        }
    }

    companion object {
        val Factory = appViewModelFactory { app, handle -> MedicationEditViewModel(app, handle) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicationEditScreen(
    onDone: () -> Unit,
    showMessage: suspend (UiMessage) -> Unit,
    vm: MedicationEditViewModel = viewModel(factory = MedicationEditViewModel.Factory),
) {
    MessageEffect(vm.messages, showMessage)
    LaunchedEffect(vm.saved) { if (vm.saved) onDone() }
    val context = LocalContext.current
    var timeDialogFor by remember { mutableStateOf<Int?>(null) }
    val requestNotifications = rememberQuietNotificationPermissionRequest()
    val errors = vm.errors

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.isNew) R.string.new_medication else R.string.edit_medication)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { TextButton(onClick = vm::save, enabled = vm.loaded) { Text(stringResource(R.string.save)) } },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { vm.name = it.take(MedicationDraftValidator.MAX_NAME) },
                    label = { Text(stringResource(R.string.field_med_name)) },
                    singleLine = true,
                    isError = MedicationDraftError.NAME_REQUIRED in errors,
                    supportingText = { if (MedicationDraftError.NAME_REQUIRED in errors) Text(stringResource(R.string.error_name_required)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.strength,
                    onValueChange = { vm.strength = it.take(60) },
                    label = { Text(stringResource(R.string.field_strength)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.notes,
                    onValueChange = { vm.notes = it.take(500) },
                    label = { Text(stringResource(R.string.field_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPicker(ItemColors, vm.color) { vm.color = it }
            }

            SectionHeader(stringResource(R.string.field_dose_times))
            vm.editingNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(horizontal = 20.dp))
            }
            vm.doses.forEachIndexed { index, dose ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { timeDialogFor = index }) { Text(Formats.time(context, dose.time)) }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.removeDose(index) }) { Icon(Icons.Filled.Delete, stringResource(R.string.remove_dose)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dose.label,
                            onValueChange = { vm.updateDose(index, dose.copy(label = it.take(40))) },
                            label = { Text(stringResource(R.string.field_dose_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f),
                        )
                        OutlinedTextField(
                            value = dose.quantityText,
                            onValueChange = { vm.updateDose(index, dose.copy(quantityText = it.take(8))) },
                            label = { Text(stringResource(R.string.field_quantity)) },
                            singleLine = true,
                            isError = parseNumber(dose.quantityText)?.let { it < 0 || !it.isFinite() } ?: true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (MedicationDraftError.DUPLICATE_TIME in errors) ErrorText(stringResource(R.string.error_duplicate_time))
            if (MedicationDraftError.DOSE_QUANTITY_INVALID in errors) ErrorText(stringResource(R.string.error_dose_quantity))
            TextButton(onClick = { vm.addDose(); requestNotifications() }, modifier = Modifier.padding(horizontal = 8.dp), enabled = vm.doses.size < MedicationDraftValidator.MAX_DOSES) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.add_dose_time))
            }

            SectionHeader(stringResource(R.string.field_days))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    FilterChip(
                        selected = d in vm.days,
                        onClick = { vm.days = if (d in vm.days) vm.days - d else vm.days + d },
                        label = { Text(Formats.dayShort(context, d)) },
                    )
                }
            }
            if (MedicationDraftError.DAYS_REQUIRED in errors) ErrorText(stringResource(R.string.error_days_required))

            SectionHeader(stringResource(R.string.section_inventory))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.track_inventory), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = vm.inventoryEnabled, onCheckedChange = { vm.inventoryEnabled = it })
            }
            if (vm.inventoryEnabled) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.unit,
                        onValueChange = { vm.unit = it.take(20) },
                        label = { Text(stringResource(R.string.field_inventory_unit)) },
                        singleLine = true,
                        isError = MedicationDraftError.UNIT_REQUIRED in errors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = vm.stockText,
                        onValueChange = { vm.stockText = it.take(10) },
                        label = { Text(stringResource(if (vm.currentStockText == null) R.string.field_current_stock else R.string.field_counted_stock)) },
                        supportingText = { vm.currentStockText?.let { Text(stringResource(R.string.current_stock_hint, it, vm.unit)) } },
                        singleLine = true,
                        isError = MedicationDraftError.STOCK_INVALID in errors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = vm.thresholdText,
                        onValueChange = { vm.thresholdText = it.take(10) },
                        label = { Text(stringResource(R.string.field_threshold)) },
                        singleLine = true,
                        isError = MedicationDraftError.THRESHOLD_INVALID in errors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.low_stock_alerts), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = vm.lowStockAlerts, onCheckedChange = { vm.lowStockAlerts = it })
                    }
                }
            }
            Button(onClick = vm::save, modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
    }

    timeDialogFor?.let { index ->
        val dose = vm.doses.getOrNull(index)
        if (dose != null) {
            TimePickerDialog(dose.time, onConfirm = { vm.updateDose(index, dose.copy(time = it)) }, onDismiss = { timeDialogFor = null })
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
}
