package io.github.funnyhell.daymark.ui.habits

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
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.rememberDatePickerState
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
import io.github.funnyhell.daymark.data.repo.HabitDraftException
import io.github.funnyhell.daymark.domain.common.formatQuantity
import io.github.funnyhell.daymark.domain.habit.HabitDraft
import io.github.funnyhell.daymark.domain.habit.HabitDraftError
import io.github.funnyhell.daymark.domain.habit.HabitDraftValidator
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.domain.habit.Recurrence
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.ColorPicker
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TimePickerDialog
import io.github.funnyhell.daymark.ui.common.UiMessage
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.common.parseNumber
import io.github.funnyhell.daymark.ui.common.rememberNotificationPermissionRequest
import io.github.funnyhell.daymark.ui.format.Formats
import io.github.funnyhell.daymark.ui.theme.ItemColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

enum class RecurrenceChoice { DAILY, WEEKDAYS, INTERVAL }

class HabitEditViewModel(application: Application, handle: SavedStateHandle) : AppViewModel(application) {
    val habitId: String? = handle.get<String>("id")?.takeIf { it.isNotBlank() }
    val isNew = habitId == null

    var loaded by mutableStateOf(isNew)
    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var color by mutableStateOf(ItemColors.first())
    var type by mutableStateOf(HabitType.CHECK)
    var unit by mutableStateOf("")
    var targetText by mutableStateOf("")
    var recurrenceChoice by mutableStateOf(RecurrenceChoice.DAILY)
    var weekdays by mutableStateOf(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
    var intervalText by mutableStateOf("2")
    var startDate by mutableStateOf(container.time.today())
    var hasHistory by mutableStateOf(false)
    var reminderTime by mutableStateOf<LocalTime?>(null)
    var showOnWidget by mutableStateOf(true)
    var errors by mutableStateOf<List<HabitDraftError>>(emptyList())
    var saved by mutableStateOf(false)

    init {
        if (habitId != null) launchSafely {
            val data = container.habits.get(habitId) ?: return@launchSafely
            val h = data.habit
            name = h.name
            description = h.description.orEmpty()
            color = h.colorArgb
            type = h.type
            unit = h.unit.orEmpty()
            startDate = h.startDate
            reminderTime = h.reminderTime
            showOnWidget = h.showOnWidget
            hasHistory = data.events.isNotEmpty()
            val schedule = data.schedules.lastOrNull()
            if (schedule != null) {
                targetText = schedule.target.formatQuantity()
                when (val r = schedule.recurrence) {
                    Recurrence.Daily -> recurrenceChoice = RecurrenceChoice.DAILY
                    is Recurrence.Weekdays -> { recurrenceChoice = RecurrenceChoice.WEEKDAYS; weekdays = r.days }
                    is Recurrence.EveryNDays -> { recurrenceChoice = RecurrenceChoice.INTERVAL; intervalText = r.interval.toString() }
                }
            }
            loaded = true
        }
    }

    fun selectType(t: HabitType) {
        type = t
        targetText = when (t) {
            HabitType.MULTI -> "3"
            HabitType.WEEKLY_GOAL -> "3"
            HabitType.QUANTITY -> ""
            else -> ""
        }
    }

    private fun recurrence(): Recurrence? = when (recurrenceChoice) {
        RecurrenceChoice.DAILY -> Recurrence.Daily
        RecurrenceChoice.WEEKDAYS -> weekdays.takeIf { it.isNotEmpty() }?.let { Recurrence.Weekdays(it) }
        RecurrenceChoice.INTERVAL -> intervalText.toIntOrNull()?.takeIf { it in 2..365 }?.let { Recurrence.EveryNDays(it) }
    }

    val recurrenceInvalid: Boolean get() = recurrence() == null

    fun save() {
        val rec = recurrence()
        if (rec == null) {
            say(string(R.string.error_recurrence))
            return
        }
        val target = when (type) {
            HabitType.CHECK, HabitType.ABSTINENCE -> 1.0
            else -> parseNumber(targetText) ?: Double.NaN
        }
        val draft = HabitDraft(
            name = name, description = description, colorArgb = color, type = type, unit = unit.ifBlank { null },
            startDate = startDate, recurrence = rec, target = target, reminderTime = reminderTime, showOnWidget = showOnWidget,
        )
        errors = HabitDraftValidator.validate(draft, container.time.today())
        if (errors.isNotEmpty()) return
        launchSafely {
            try {
                if (habitId == null) container.habits.create(draft) else container.habits.update(habitId, draft)
                saved = true
            } catch (e: HabitDraftException) {
                errors = e.errors
            }
        }
    }

    companion object {
        val Factory = appViewModelFactory { app, handle -> HabitEditViewModel(app, handle) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HabitEditScreen(
    onDone: () -> Unit,
    showMessage: suspend (UiMessage) -> Unit,
    vm: HabitEditViewModel = viewModel(factory = HabitEditViewModel.Factory),
) {
    MessageEffect(vm.messages, showMessage)
    LaunchedEffect(vm.saved) { if (vm.saved) onDone() }
    val context = LocalContext.current
    var timeDialog by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    val requestNotifications = rememberNotificationPermissionRequest()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.isNew) R.string.new_habit else R.string.edit_habit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                },
                actions = { TextButton(onClick = vm::save, enabled = vm.loaded) { Text(stringResource(R.string.save)) } },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            val errors = vm.errors
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { vm.name = it.take(HabitDraftValidator.MAX_NAME) },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    isError = HabitDraftError.NAME_REQUIRED in errors,
                    supportingText = { if (HabitDraftError.NAME_REQUIRED in errors) Text(stringResource(R.string.error_name_required)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.description,
                    onValueChange = { vm.description = it.take(300) },
                    label = { Text(stringResource(R.string.field_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ColorPicker(ItemColors, vm.color) { vm.color = it }
            }

            SectionHeader(stringResource(R.string.field_type))
            if (vm.isNew) {
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HabitType.entries.forEach { t ->
                        FilterChip(selected = vm.type == t, onClick = { vm.selectType(t) }, label = { Text(HabitPresentation.typeLabel(context, t)) })
                    }
                }
            } else {
                Text(
                    HabitPresentation.typeLabel(context, vm.type) + " · " + stringResource(R.string.type_locked),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Text(
                stringResource(typeHelp(vm.type)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            if (vm.type == HabitType.MULTI || vm.type == HabitType.QUANTITY || vm.type == HabitType.WEEKLY_GOAL) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = vm.targetText,
                        onValueChange = { vm.targetText = it.take(10) },
                        label = {
                            Text(
                                stringResource(
                                    when (vm.type) {
                                        HabitType.MULTI -> R.string.field_times_per_day
                                        HabitType.WEEKLY_GOAL -> R.string.field_times_per_week
                                        else -> R.string.field_daily_target
                                    },
                                ),
                            )
                        },
                        singleLine = true,
                        isError = HabitDraftError.TARGET_INVALID in errors,
                        supportingText = { if (HabitDraftError.TARGET_INVALID in errors) Text(stringResource(targetError(vm.type))) },
                        keyboardOptions = KeyboardOptions(keyboardType = if (vm.type == HabitType.QUANTITY) KeyboardType.Decimal else KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.type == HabitType.QUANTITY) {
                        OutlinedTextField(
                            value = vm.unit,
                            onValueChange = { vm.unit = it.take(16) },
                            label = { Text(stringResource(R.string.field_unit)) },
                            singleLine = true,
                            isError = HabitDraftError.UNIT_REQUIRED in errors,
                            supportingText = { if (HabitDraftError.UNIT_REQUIRED in errors) Text(stringResource(R.string.error_unit_required)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (vm.type == HabitType.CHECK || vm.type == HabitType.MULTI || vm.type == HabitType.QUANTITY) {
                SectionHeader(stringResource(R.string.field_schedule))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vm.recurrenceChoice == RecurrenceChoice.DAILY, { vm.recurrenceChoice = RecurrenceChoice.DAILY }, { Text(stringResource(R.string.recurrence_daily)) })
                    FilterChip(vm.recurrenceChoice == RecurrenceChoice.WEEKDAYS, { vm.recurrenceChoice = RecurrenceChoice.WEEKDAYS }, { Text(stringResource(R.string.recurrence_weekdays)) })
                    FilterChip(vm.recurrenceChoice == RecurrenceChoice.INTERVAL, { vm.recurrenceChoice = RecurrenceChoice.INTERVAL }, { Text(stringResource(R.string.recurrence_interval)) })
                }
                when (vm.recurrenceChoice) {
                    RecurrenceChoice.WEEKDAYS -> FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { d ->
                            FilterChip(
                                selected = d in vm.weekdays,
                                onClick = { vm.weekdays = if (d in vm.weekdays) vm.weekdays - d else vm.weekdays + d },
                                label = { Text(Formats.dayShort(context, d)) },
                            )
                        }
                    }
                    RecurrenceChoice.INTERVAL -> OutlinedTextField(
                        value = vm.intervalText,
                        onValueChange = { vm.intervalText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.field_interval_days)) },
                        singleLine = true,
                        isError = vm.recurrenceInvalid,
                        supportingText = { Text(stringResource(R.string.interval_help)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    )
                    RecurrenceChoice.DAILY -> Unit
                }
                if (vm.recurrenceChoice == RecurrenceChoice.WEEKDAYS && vm.weekdays.isEmpty()) {
                    Text(stringResource(R.string.error_recurrence), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
                }
                if (!vm.isNew) {
                    Text(
                        stringResource(R.string.schedule_change_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }

            SectionHeader(stringResource(R.string.field_start_date))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Formats.dateMedium(context, vm.startDate), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { dateDialog = true }, enabled = !vm.hasHistory) { Text(stringResource(R.string.change)) }
            }
            if (vm.hasHistory) {
                Text(
                    stringResource(R.string.start_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            if (vm.type != HabitType.ABSTINENCE) {
                SectionHeader(stringResource(R.string.field_reminder))
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        vm.reminderTime?.let { Formats.time(context, it) } ?: stringResource(R.string.reminder_off),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (vm.reminderTime != null) TextButton(onClick = { timeDialog = true }) { Text(stringResource(R.string.change)) }
                    Switch(
                        checked = vm.reminderTime != null,
                        onCheckedChange = { on ->
                            vm.reminderTime = if (on) LocalTime.of(20, 0) else null
                            if (on) requestNotifications()
                        },
                    )
                }
            }

            SectionHeader(stringResource(R.string.field_widget))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_on_widget), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = vm.showOnWidget, onCheckedChange = { vm.showOnWidget = it })
            }

            Button(onClick = vm::save, modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
    }

    if (timeDialog) {
        TimePickerDialog(vm.reminderTime ?: LocalTime.of(20, 0), onConfirm = { vm.reminderTime = it }, onDismiss = { timeDialog = false })
    }
    if (dateDialog) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.startDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { dateDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val picked = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.startDate = if (picked.isAfter(today.plusYears(1))) today else picked
                    }
                    dateDialog = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { dateDialog = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

private fun typeHelp(type: HabitType): Int = when (type) {
    HabitType.CHECK -> R.string.type_check_help
    HabitType.MULTI -> R.string.type_multi_help
    HabitType.QUANTITY -> R.string.type_quantity_help
    HabitType.WEEKLY_GOAL -> R.string.type_weekly_help
    HabitType.ABSTINENCE -> R.string.type_abstinence_help
}

private fun targetError(type: HabitType): Int = when (type) {
    HabitType.MULTI -> R.string.error_target_multi
    HabitType.WEEKLY_GOAL -> R.string.error_target_weekly
    else -> R.string.error_target_quantity
}
