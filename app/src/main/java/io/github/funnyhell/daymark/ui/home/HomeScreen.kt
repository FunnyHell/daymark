package io.github.funnyhell.daymark.ui.home

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.module.HomeHost
import io.github.funnyhell.daymark.module.HomeLayout
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleRegistry
import io.github.funnyhell.daymark.modules.breaks.BreaksModule
import io.github.funnyhell.daymark.modules.habits.HabitsModule
import io.github.funnyhell.daymark.modules.medications.MedicationsModule
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.EmptyState
import io.github.funnyhell.daymark.ui.common.PermissionBanners
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
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

data class HomeState(
    val loading: Boolean = true,
    /** The day shown: today, or an earlier day chosen to fill in what was not marked then. */
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val settings: Settings = Settings(),
    /** Ids of the sections shown, in order. */
    val sections: List<String> = emptyList(),
    val needsReminderPermissions: Boolean = false,
) {
    val isToday: Boolean get() = date == today
}

class HomeViewModel(application: Application) : AppViewModel(application) {
    private val chosenDay = MutableStateFlow<LocalDate?>(null)

    /** Follows the date across midnight. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    fun showDay(date: LocalDate?) {
        chosenDay.value = date?.takeIf { it.isBefore(container.time.today()) }
    }

    val state: StateFlow<HomeState> = combine(
        container.settings.settings, chosenDay, ticker, container.habits.observeAll(), container.medications.observeAll(),
    ) { settings, chosen, _, habits, meds ->
        val today = container.time.today()
        val enabled = ModuleRegistry.enabled(settings)
        val available = enabled.flatMap { it.homeSections }
        val ids = enabled.map { it.id }.toSet()
        HomeState(
            loading = false,
            date = chosen ?: today,
            today = today,
            settings = settings,
            sections = HomeLayout.resolve(available, HomeSection::id, settings.homeOrder, settings.homeHidden).map { it.id },
            needsReminderPermissions = (MedicationsModule.ID in ids && meds.any { m -> m.medication.status == LifecycleStatus.ACTIVE && m.schedules.any { it.doses.isNotEmpty() } }) ||
                (HabitsModule.ID in ids && habits.any { it.habit.reminderTime != null && it.habit.status == LifecycleStatus.ACTIVE }) ||
                BreaksModule.ID in ids,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun saveLayout(order: List<String>, hidden: Set<String>) = launchSafely { container.settings.setHomeLayout(order, hidden) }

    companion object {
        val Factory = appViewModelFactory { app, _ -> HomeViewModel(app) }
    }
}

/** Home: the day's sections from the enabled modules, in the order the user chose, for today or an earlier day. */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    navigator: ModuleNavigator,
    onOpenModules: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.loading) return
    val context = LocalContext.current
    var pickDay by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val host = remember(state.date, state.isToday, navigator) {
        object : HomeHost {
            override val navigator = navigator
            override val day = state.date
            override val isToday = state.isToday
        }
    }
    val sectionsById = remember { ModuleRegistry.all.flatMap { it.homeSections }.associateBy { it.id } }
    val shown = state.sections.mapNotNull { sectionsById[it] }
    // Each section collects its own state here and hands back the list items to draw.
    val sectionItems = shown.map { section -> androidx.compose.runtime.key(section.id) { section.rememberItems(host) } }

    LazyColumn(contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item(key = "home.header") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Formats.dateFull(context, state.date),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).clickable(onClickLabel = stringResource(R.string.pick_day)) { pickDay = true },
                    )
                    IconButton(onClick = { vm.showDay(state.date.minusDays(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.previous_day))
                    }
                    IconButton(onClick = { vm.showDay(state.date.plusDays(1)) }, enabled = !state.isToday) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next_day))
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.home_edit)) }
                }
                if (!state.isToday) {
                    AssistChip(onClick = { vm.showDay(null) }, label = { Text(stringResource(R.string.back_to_today)) })
                }
            }
        }
        item(key = "home.permissions") { PermissionBanners(needed = state.needsReminderPermissions && state.isToday) }
        if (shown.isEmpty()) {
            item(key = "home.empty") {
                EmptyState(stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body)) {
                    Button(onClick = { editing = true }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.home_edit)) }
                }
            }
        }
        sectionItems.forEach { it() }
    }

    if (pickDay) {
        PastDayPicker(state.date, state.today, onPick = { vm.showDay(it); pickDay = false }, onDismiss = { pickDay = false })
    }
    if (editing) {
        HomeEditor(
            settings = state.settings,
            onSave = vm::saveLayout,
            onOpenModules = { editing = false; onOpenModules() },
            onDismiss = { editing = false },
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
