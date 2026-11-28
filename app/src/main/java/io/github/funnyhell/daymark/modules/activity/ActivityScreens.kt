package io.github.funnyhell.daymark.modules.activity

import android.app.Application
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.domain.common.LifecycleStatus
import io.github.funnyhell.daymark.domain.habit.Habit
import io.github.funnyhell.daymark.module.HomeHost
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.NoteDialog
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What was read from Health Connect, refreshed when a screen with it opens. */
data class ActivityRead(
    val loading: Boolean = true,
    val access: HealthAccess? = null,
    val stepsToday: Long? = null,
    val workouts: List<Workout> = emptyList(),
    val error: String? = null,
)

data class ActivityUiState(
    val read: ActivityRead = ActivityRead(),
    val notes: Map<String, String> = emptyMap(),
    val linkedHabit: Habit? = null,
    val habits: List<Habit> = emptyList(),
)

class ActivityViewModel(application: Application) : AppViewModel(application) {
    private val data = HealthData(application)
    private val store = ActivityStore(application)
    private val read = MutableStateFlow(ActivityRead())

    val state: StateFlow<ActivityUiState> = combine(read, store.state, container.habits.observeAll()) { r, s, habits ->
        val active = habits.map { it.habit }.filter { it.status == LifecycleStatus.ACTIVE }
        ActivityUiState(r, s.notes, active.firstOrNull { it.id == s.linkedHabitId }, active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    /** Reads steps and the last two weeks of workouts again, and marks the linked habit for new workouts. */
    fun refresh() = viewModelScope.launch {
        val access = data.access()
        if (access != HealthAccess.GRANTED) {
            read.value = ActivityRead(loading = false, access = access)
            return@launch
        }
        val today = container.time.today()
        val zone = container.time.zone()
        read.value = try {
            ActivityRead(
                loading = false,
                access = access,
                stepsToday = data.steps(today, zone),
                workouts = data.workouts(today.minusDays(13), today, zone),
            )
        } catch (e: Exception) {
            ActivityRead(loading = false, access = access, error = e.message ?: e.javaClass.simpleName)
        }
        runCatching { ActivityModule.markLinkedHabit(app) }
    }

    fun setNote(workoutId: String, note: String?) = launchSafely { store.setNote(workoutId, note) }

    fun link(habitId: String?) = launchSafely {
        store.setLinkedHabit(habitId)
        ActivityModule.markLinkedHabit(app)
    }

    companion object {
        val Factory = appViewModelFactory { app, _ -> ActivityViewModel(app) }
    }
}

/** The module's screen inside the tab bar. */
@Composable
fun ActivityScreen(contentPadding: PaddingValues, navigator: ModuleNavigator, vm: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory)) {
    ActivityContent(PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp), vm)
}

/** The same screen opened from Home or the module list, with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityStandaloneScreen(navigator: ModuleNavigator, onBack: () -> Unit, vm: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory)) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_activity)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding -> ActivityContent(PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp), vm) }
}

@Composable
private fun ActivityContent(padding: PaddingValues, vm: ActivityViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(HealthData.permissionContract()) { vm.refresh() }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    var noteFor by remember { mutableStateOf<Workout?>(null) }
    var habitMenu by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = padding) {
        item {
            Text(
                stringResource(R.string.activity_about),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        accessItems(state.read, onInstall = { context.startActivity(HealthData.installIntent()) }, onAllow = { permissions.launch(HealthData.PERMISSIONS) })
        if (state.read.access == HealthAccess.GRANTED) {
            item {
                AppCard {
                    Text(stringResource(R.string.activity_steps_today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.read.stepsToday?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                SectionHeader(stringResource(R.string.activity_link_habit))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.linkedHabit?.name ?: stringResource(R.string.activity_link_none),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { habitMenu = true }) { Text(stringResource(R.string.activity_link_choose)) }
                    DropdownMenu(expanded = habitMenu, onDismissRequest = { habitMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.activity_link_none)) }, onClick = { habitMenu = false; vm.link(null) })
                        state.habits.forEach { h -> DropdownMenuItem(text = { Text(h.name) }, onClick = { habitMenu = false; vm.link(h.id) }) }
                    }
                }
                Text(
                    stringResource(R.string.activity_link_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item { SectionHeader(stringResource(R.string.activity_workouts)) }
            if (state.read.workouts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.activity_no_workouts),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(state.read.workouts, key = { it.id }) { w -> WorkoutCard(context, w, state.notes[w.id], onNote = { noteFor = w }) }
        }
    }
    noteFor?.let { w ->
        NoteDialog(ActivityModule.typeName(context, w.type), state.notes[w.id], onSave = { vm.setNote(w.id, it) }, onDismiss = { noteFor = null })
    }
}

private fun LazyListScope.accessItems(read: ActivityRead, onInstall: () -> Unit, onAllow: () -> Unit) {
    val access = read.access ?: return
    if (access == HealthAccess.GRANTED && read.error == null) return
    item {
        AppCard {
            Text(
                when {
                    read.error != null -> read.error.orEmpty()
                    access == HealthAccess.NOT_INSTALLED -> stringResource(R.string.activity_not_installed)
                    access == HealthAccess.NEEDS_UPDATE -> stringResource(R.string.activity_needs_update)
                    else -> stringResource(R.string.activity_no_permission)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            when (access) {
                HealthAccess.NOT_INSTALLED, HealthAccess.NEEDS_UPDATE ->
                    Button(onClick = onInstall, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.activity_install)) }
                HealthAccess.NO_PERMISSION ->
                    Button(onClick = onAllow, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.activity_allow)) }
                HealthAccess.GRANTED -> Unit
            }
        }
    }
}

@Composable
private fun WorkoutCard(context: Context, w: Workout, note: String?, onNote: () -> Unit) {
    val zone = context.container.time.zone()
    val minutes = Duration.between(w.start, w.end).toMinutes()
    AppCard(onClick = onNote) {
        Text(w.title?.takeIf { it.isNotBlank() } ?: ActivityModule.typeName(context, w.type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOf(
                Formats.dateMedium(context, w.start.atZone(zone).toLocalDate()),
                "${Formats.time(context, w.start, zone)}–${Formats.time(context, w.end, zone)}",
                context.resources.getQuantityString(R.plurals.minutes_short, minutes.toInt(), minutes.toInt()),
                sourceName(context, w.source),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            note ?: stringResource(R.string.note_add),
            style = MaterialTheme.typography.bodyMedium,
            color = if (note == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun sourceName(context: Context, pkg: String): String =
    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

/** Home: today's steps and the workouts of the shown day. */
object ActivityDaySection : HomeSection {
    override val id = "activity.day"
    override val title = R.string.module_activity

    @Composable
    override fun rememberItems(host: HomeHost): LazyListScope.() -> Unit {
        val vm: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory)
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(host.day) { vm.refresh() }
        val context = LocalContext.current
        val day: LocalDate = host.day
        val zone = context.container.time.zone()
        val workouts = state.read.workouts.filter { it.start.atZone(zone).toLocalDate() == day }
        return {
            if (!state.read.loading) {
                item(key = "activity.header") { SectionHeader(stringResource(R.string.module_activity)) }
                if (state.read.access != HealthAccess.GRANTED) {
                    item(key = "activity.connect") {
                        AppCard(onClick = { host.navigator.navigate(ActivityModule.SCREEN_ROUTE) }) {
                            Text(stringResource(R.string.activity_connect), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    item(key = "activity.summary") {
                        AppCard(onClick = { host.navigator.navigate(ActivityModule.SCREEN_ROUTE) }) {
                            if (host.isToday) {
                                Text(
                                    stringResource(R.string.activity_steps_value, state.read.stepsToday ?: 0L),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (workouts.isEmpty()) {
                                Text(stringResource(R.string.activity_no_workouts_day), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            workouts.forEach { w ->
                                Column(Modifier.padding(top = 4.dp).clickable { host.navigator.navigate(ActivityModule.SCREEN_ROUTE) }) {
                                    Text(
                                        "${w.title?.takeIf { it.isNotBlank() } ?: ActivityModule.typeName(context, w.type)} · " +
                                            "${Formats.time(context, w.start, zone)}–${Formats.time(context, w.end, zone)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    state.notes[w.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
