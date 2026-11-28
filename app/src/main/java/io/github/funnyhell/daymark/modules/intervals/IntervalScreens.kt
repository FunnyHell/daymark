package io.github.funnyhell.daymark.modules.intervals

import android.app.Application
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.module.HomeHost
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleHost
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.EmptyState
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TimePickerDialog
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** One reminder as shown in lists: its schedule in words, the next time and today's count. */
data class IntervalItem(val reminder: IntervalReminder, val schedule: String, val next: String?, val doneToday: Int)

data class IntervalListState(val loading: Boolean = true, val items: List<IntervalItem> = emptyList())

class IntervalListViewModel(application: Application) : AppViewModel(application) {
    private val store = IntervalStore(application)

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val state: StateFlow<IntervalListState> = combine(store.reminders, ticker) { list, _ ->
        IntervalListState(loading = false, items = list.map { item(app, it) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntervalListState())

    fun setEnabled(id: String, enabled: Boolean) = launchSafely {
        store.setEnabled(id, enabled)
        if (!enabled) IntervalsModule.cancelNotification(app, id)
        container.reminders.reschedule()
    }

    fun done(id: String) = launchSafely { IntervalsModule.done(app, id) }

    companion object {
        val Factory = appViewModelFactory { app, _ -> IntervalListViewModel(app) }

        fun item(context: Context, r: IntervalReminder): IntervalItem {
            val c = context.container
            val now = c.time.now()
            val zone = c.time.zone()
            val next: Instant? = if (r.enabled) BreakRules.upcoming(r.plan, r.anchor, now, zone, days = 8).firstOrNull { it.isAfter(now) } else null
            val nextText = next?.let {
                val date = it.atZone(zone).toLocalDate()
                val time = Formats.time(context, it, zone)
                if (date == c.time.today()) context.getString(R.string.intervals_next_today, time)
                else context.getString(R.string.intervals_next_on, Formats.dateMedium(context, date), time)
            }
            return IntervalItem(r, scheduleText(context, r.plan), nextText, r.countOn(c.time.today()))
        }

        fun scheduleText(context: Context, plan: BreakPlan): String {
            val every = context.resources.getQuantityString(R.plurals.minutes_short, plan.intervalMinutes, plan.intervalMinutes)
            val hours = "${Formats.time(context, plan.from)}–${Formats.time(context, plan.until)}"
            val days = if (plan.days.size == 7) context.getString(R.string.recurrence_daily)
            else DayOfWeek.entries.filter { it in plan.days }.joinToString(", ") { Formats.dayShort(context, it) }
            return context.getString(R.string.intervals_schedule, every, hours, days)
        }
    }
}

/** The Intervals tab: every reminder with a switch; tap one to edit it. */
@Composable
fun IntervalListScreen(host: ModuleHost, vm: IntervalListViewModel = viewModel(factory = IntervalListViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(top = host.contentPadding.calculateTopPadding(), bottom = host.contentPadding.calculateBottomPadding() + 88.dp)) {
        if (!state.loading && state.items.isEmpty()) {
            item { EmptyState(stringResource(R.string.intervals_empty_title), stringResource(R.string.intervals_empty_body)) }
        }
        items(state.items, key = { it.reminder.id }) { item ->
            AppCard(onClick = { host.navigator.navigate(IntervalsModule.editRoute(item.reminder.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.reminder.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(item.schedule, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.next?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                    }
                    Switch(checked = item.reminder.enabled, onCheckedChange = { vm.setEnabled(item.reminder.id, it) })
                }
            }
        }
    }
}

/** Home: the enabled interval reminders with their next time and a "Done" button. */
object IntervalsDaySection : HomeSection {
    override val id = "intervals.day"
    override val title = R.string.module_intervals

    @Composable
    override fun rememberItems(host: HomeHost): LazyListScope.() -> Unit {
        val vm: IntervalListViewModel = viewModel(factory = IntervalListViewModel.Factory)
        val state by vm.state.collectAsStateWithLifecycle()
        val isToday = host.isToday
        val enabled = state.items.filter { it.reminder.enabled }
        return {
            if (!state.loading && isToday) {
                item(key = "intervals.header") { SectionHeader(stringResource(R.string.module_intervals)) }
                if (enabled.isEmpty()) {
                    item(key = "intervals.empty") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.intervals_none_on),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { host.navigator.navigate(IntervalsModule.editRoute()) }) { Text(stringResource(R.string.intervals_new)) }
                        }
                    }
                }
                items(enabled, key = { "interval" + it.reminder.id }) { item ->
                    AppCard(onClick = { host.navigator.navigate(IntervalsModule.editRoute(item.reminder.id)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.reminder.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOfNotNull(item.next ?: stringResource(R.string.intervals_none_planned), stringResource(R.string.intervals_done_today, item.doneToday))
                                        .joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { vm.done(item.reminder.id) }) { Text(stringResource(R.string.intervals_done)) }
                        }
                    }
                }
            }
        }
    }
}

class IntervalEditViewModel(application: Application) : AppViewModel(application) {
    private val store = IntervalStore(application)
    var loaded by mutableStateOf(false)
        private set
    var existing: IntervalReminder? = null
        private set
    var title by mutableStateOf("")
    var plan by mutableStateOf(BreakPlan())

    fun load(id: String?) = launchSafely {
        if (loaded) return@launchSafely
        val found = id?.let { store.get(it) }
        existing = found
        title = found?.title ?: string(R.string.intervals_default_title)
        plan = found?.plan ?: BreakPlan()
        loaded = true
    }

    val valid: Boolean get() = title.isNotBlank() && plan.until.isAfter(plan.from) && plan.days.isNotEmpty()

    fun save(onDone: () -> Unit) = launchSafely {
        val base = existing ?: IntervalReminder(id = UUID.randomUUID().toString(), title = title)
        store.save(base.copy(title = title.trim(), plan = plan))
        container.reminders.reschedule()
        onDone()
    }

    fun delete(onDone: () -> Unit) = launchSafely {
        val id = existing?.id ?: return@launchSafely onDone()
        store.delete(id)
        IntervalsModule.cancelNotification(app, id)
        container.reminders.reschedule()
        onDone()
    }

    companion object {
        val Factory = appViewModelFactory { app, _ -> IntervalEditViewModel(app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IntervalEditScreen(id: String?, onDone: () -> Unit, vm: IntervalEditViewModel = viewModel(factory = IntervalEditViewModel.Factory)) {
    LaunchedEffect(id) { vm.load(id) }
    val context = LocalContext.current
    var editFrom by remember { mutableStateOf(false) }
    var editUntil by remember { mutableStateOf(false) }
    val plan = vm.plan
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (id == null) R.string.intervals_new else R.string.intervals_edit)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (id != null) {
                        IconButton(onClick = { vm.delete(onDone) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete_permanently)) }
                    }
                    TextButton(onClick = { vm.save(onDone) }, enabled = vm.loaded && vm.valid) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            OutlinedTextField(
                value = vm.title,
                onValueChange = { vm.title = it },
                label = { Text(stringResource(R.string.intervals_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SectionHeader(stringResource(R.string.intervals_every))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BreakPlan.INTERVAL_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected = plan.intervalMinutes == minutes,
                        onClick = { vm.plan = plan.copy(intervalMinutes = minutes) },
                        label = { Text(pluralStringResource(R.plurals.minutes_short, minutes, minutes)) },
                    )
                }
            }
            SectionHeader(stringResource(R.string.intervals_hours))
            TimeRow(stringResource(R.string.intervals_from), Formats.time(context, plan.from)) { editFrom = true }
            TimeRow(stringResource(R.string.intervals_until), Formats.time(context, plan.until)) { editUntil = true }
            if (!plan.until.isAfter(plan.from)) {
                Text(
                    stringResource(R.string.intervals_hours_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            SectionHeader(stringResource(R.string.intervals_days))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val on = day in plan.days
                    FilterChip(
                        selected = on,
                        onClick = { vm.plan = plan.copy(days = if (on) plan.days - day else plan.days + day) },
                        label = { Text(Formats.dayShort(context, day)) },
                    )
                }
            }
        }
    }
    if (editFrom) TimePickerDialog(plan.from, onConfirm = { vm.plan = vm.plan.copy(from = it) }, onDismiss = { editFrom = false })
    if (editUntil) TimePickerDialog(plan.until, onConfirm = { vm.plan = vm.plan.copy(until = it) }, onDismiss = { editUntil = false })
}

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
