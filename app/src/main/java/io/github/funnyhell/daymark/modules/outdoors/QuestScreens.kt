package io.github.funnyhell.daymark.modules.outdoors

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.NoteDialog
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class QuestViewModel(application: Application) : AppViewModel(application) {
    private val store = QuestStore(application)
    val state: StateFlow<QuestState> = store.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuestState())

    var busy by mutableStateOf(false)
        private set

    /** Why the last search failed, shown on the screen; null after a successful one. */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Distance to today's place from the last position read, in meters. */
    var lastDistance by mutableStateOf<Double?>(null)
        private set

    val tasks = listOf(R.string.quest_task_photo, R.string.quest_task_bench, R.string.quest_task_detail, R.string.quest_task_walk_around)

    fun setConsent(on: Boolean) = launchSafely { store.setConsent(on) }

    fun setPrefs(prefs: QuestPrefs) = launchSafely { store.setPrefs(prefs) }

    /** Finds today's quest: reads the position, looks places up (at most once a day per area), picks one. */
    fun findQuest(skipCurrent: Boolean = false) = launchSafely {
        if (busy) return@launchSafely
        busy = true
        try {
            lastError = null
            val today = container.time.today()
            val location = OutdoorsModule.searchLocation(app)
            if (location == null) {
                lastError = string(R.string.quest_no_location)
                return@launchSafely
            }
            val s = store.current()
            val language = java.util.Locale.getDefault().language
            var radius = QuestRules.radiusMeters(s.prefs, today).roundToInt()
            val cached = s.cacheDate == today && s.cacheRadius >= radius && s.cacheLat != null && s.cacheLon != null &&
                QuestRules.distance(location.latitude, location.longitude, s.cacheLat, s.cacheLon) < 300
            var places = if (cached) {
                s.cache
            } else {
                try {
                    withContext(Dispatchers.IO) { Overpass.fetch(location.latitude, location.longitude, radius, language) }
                } catch (e: Exception) {
                    lastError = string(R.string.quest_search_failed, e.message ?: e.javaClass.simpleName)
                    return@launchSafely
                }
            }
            // Nothing public outdoors within the walking time: look a little farther (twice, then four times).
            var widened = 0
            while (places.none { QuestRules.distance(location.latitude, location.longitude, it.lat, it.lon) <= radius * 1.5 } && widened < 2) {
                radius = (radius * 2).coerceAtMost(15_000)
                widened++
                places = try {
                    withContext(Dispatchers.IO) { Overpass.fetch(location.latitude, location.longitude, radius, language) }
                } catch (e: Exception) {
                    lastError = string(R.string.quest_search_failed, e.message ?: e.javaClass.simpleName)
                    return@launchSafely
                }
            }
            store.setCache(places, location.latitude, location.longitude, radius, today)
            if (skipCurrent) {
                store.updateQuests { list -> list.map { if (it.date == today && it.status == QuestStatus.OPEN) it.copy(status = QuestStatus.SKIPPED) else it } }
            }
            val after = store.current()
            val skippedToday = after.quests.filter { it.date == today && it.status == QuestStatus.SKIPPED }.associate { it.place.id to today }
            val place = QuestRules.pick(places, location.latitude, location.longitude, radius.toDouble(), after.visited + skippedToday, today)
            if (place == null) {
                lastError = string(R.string.quest_no_places)
                return@launchSafely
            }
            store.updateQuests { it + Quest(today, place, QuestRules.taskIndex(place, today, tasks.size), QuestStatus.OPEN) }
            lastDistance = QuestRules.distance(location.latitude, location.longitude, place.lat, place.lon)
        } finally {
            busy = false
        }
    }

    /** "I'm here": the quest is done when the current position is at the place. */
    fun checkIn() = launchSafely {
        if (busy) return@launchSafely
        busy = true
        try {
            val today = container.time.today()
            val quest = store.current().questOn(today) ?: return@launchSafely
            val location = OutdoorsModule.currentLocation(app)
            if (location == null) {
                say(string(R.string.quest_no_location))
                return@launchSafely
            }
            val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 50.0
            lastDistance = QuestRules.distance(location.latitude, location.longitude, quest.place.lat, quest.place.lon)
            if (QuestRules.isAt(quest.place, location.latitude, location.longitude, accuracy)) {
                complete(today, QuestMethod.LOCATION)
                say(string(R.string.quest_done))
            } else {
                say(string(R.string.quest_not_there, lastDistance!!.roundToInt()))
            }
        } finally {
            busy = false
        }
    }

    /** Marked by hand, for when location does not work there. */
    fun markManually() = launchSafely { complete(container.time.today(), QuestMethod.MANUAL) }

    private suspend fun complete(date: LocalDate, method: QuestMethod) {
        val now = container.time.now().toEpochMilli()
        store.updateQuests { list ->
            list.map { if (it.date == date && it.status == QuestStatus.OPEN) it.copy(status = QuestStatus.DONE, method = method, doneAtMillis = now) else it }
        }
    }

    fun setNote(date: LocalDate, note: String?) = launchSafely {
        store.updateQuests { list -> list.map { if (it.date == date && it.status != QuestStatus.SKIPPED) it.copy(note = note?.trim()?.ifEmpty { null }) else it } }
    }

    companion object {
        val Factory = appViewModelFactory { app, _ -> QuestViewModel(app) }
    }
}

@Composable
fun QuestScreen(contentPadding: PaddingValues, vm: QuestViewModel = viewModel(factory = QuestViewModel.Factory)) =
    QuestContent(PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 24.dp), vm)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestStandaloneScreen(onBack: () -> Unit, vm: QuestViewModel = viewModel(factory = QuestViewModel.Factory)) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.module_outdoors)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding -> QuestContent(PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp), vm) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestContent(padding: PaddingValues, vm: QuestViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(OutdoorsModule.hasLocationPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result.values.any { it } || OutdoorsModule.hasLocationPermission(context)
    }
    var confirmManual by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<Quest?>(null) }
    val today = remember { context.container.time.today() }
    val quest = state.questOn(today)
    LazyColumn(contentPadding = padding) {
        if (!state.consent) {
            item {
                AppCard {
                    Text(stringResource(R.string.quest_intro_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.quest_intro_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    Button(onClick = { vm.setConsent(true) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.quest_intro_agree)) }
                }
            }
            return@LazyColumn
        }
        if (!hasPermission) {
            item {
                AppCard {
                    Text(stringResource(R.string.quest_location_needed), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { permission.launch(OutdoorsModule.LOCATION_PERMISSIONS) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.quest_allow_location))
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.quest_today)) }
        item {
            if (quest == null) {
                AppCard {
                    Text(stringResource(R.string.quest_none_yet), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.findQuest() }, enabled = hasPermission && !vm.busy, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(if (vm.busy) R.string.quest_searching else R.string.quest_find))
                    }
                    vm.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                }
            } else {
                QuestCard(
                    context, quest, vm.tasks, vm.lastDistance, vm.busy, hasPermission,
                    onCheckIn = vm::checkIn,
                    onManual = { confirmManual = true },
                    onAnother = { vm.findQuest(skipCurrent = true) },
                    onNote = { noteFor = quest },
                )
            }
        }
        item { SectionHeader(stringResource(R.string.quest_time)) }
        item { PrefsEditor(state.prefs, vm::setPrefs) }
        val history = state.quests.filter { it.status == QuestStatus.DONE }.sortedByDescending { it.date }.take(30)
        if (history.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.quest_history, history.size)) }
            items(history, key = { it.date.toString() + it.place.id }) { q ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(
                        "${Formats.dateMedium(context, q.date)} · ${placeTitle(context, q.place)}" +
                            (if (q.method == QuestMethod.MANUAL) " · " + stringResource(R.string.quest_marked_by_hand) else "") +
                            (q.note?.let { "\n$it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
    if (confirmManual) {
        AlertDialog(
            onDismissRequest = { confirmManual = false },
            title = { Text(stringResource(R.string.quest_manual_title)) },
            text = { Text(stringResource(R.string.quest_manual_body)) },
            confirmButton = { TextButton(onClick = { confirmManual = false; vm.markManually() }) { Text(stringResource(R.string.quest_manual_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmManual = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    noteFor?.let { q -> NoteDialog(placeTitle(context, q.place), q.note, onSave = { vm.setNote(q.date, it) }, onDismiss = { noteFor = null }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestCard(
    context: Context,
    quest: Quest,
    tasks: List<Int>,
    distance: Double?,
    busy: Boolean,
    hasPermission: Boolean,
    onCheckIn: () -> Unit,
    onManual: () -> Unit,
    onAnother: () -> Unit,
    onNote: () -> Unit,
) {
    AppCard {
        Text(placeTitle(context, quest.place), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(tasks[quest.task.coerceIn(0, tasks.lastIndex)]), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        distance?.let {
            Text(stringResource(R.string.quest_distance, it.roundToInt()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        quest.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        if (quest.status == QuestStatus.DONE) {
            Text(
                stringResource(if (quest.method == QuestMethod.MANUAL) R.string.quest_done_manual else R.string.quest_done),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openMap(context, quest.place) }) { Text(stringResource(R.string.quest_open_map)) }
            if (quest.status == QuestStatus.OPEN) {
                Button(onClick = onCheckIn, enabled = hasPermission && !busy) { Text(stringResource(R.string.quest_check_in)) }
                TextButton(onClick = onManual) { Text(stringResource(R.string.quest_mark_by_hand)) }
                TextButton(onClick = onAnother, enabled = hasPermission && !busy) { Text(stringResource(R.string.quest_another)) }
            }
            TextButton(onClick = onNote) { Text(stringResource(if (quest.note == null) R.string.note_add else R.string.note_edit)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrefsEditor(prefs: QuestPrefs, onChange: (QuestPrefs) -> Unit) {
    val choices = listOf(10, 15, 20, 30, 45, 60, 90)
    Text(stringResource(R.string.quest_weekdays), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { m ->
            FilterChip(prefs.weekdayMinutes == m, { onChange(prefs.copy(weekdayMinutes = m)) }, { Text(pluralStringResource(R.plurals.minutes_short, m, m)) })
        }
    }
    Text(stringResource(R.string.quest_weekends), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { m ->
            FilterChip(prefs.weekendMinutes == m, { onChange(prefs.copy(weekendMinutes = m)) }, { Text(pluralStringResource(R.plurals.minutes_short, m, m)) })
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.quest_weekend_trips), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = prefs.weekendTrips, onCheckedChange = { onChange(prefs.copy(weekendTrips = it)) })
    }
}

private fun openMap(context: Context, place: Place) {
    val label = Uri.encode(place.name ?: placeTitle(context, place))
    val uri = Uri.parse("geo:${place.lat},${place.lon}?q=${place.lat},${place.lon}($label)")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun placeTitle(context: Context, place: Place): String {
    val kind = context.getString(
        when (place.kind) {
            PlaceKind.PARK -> R.string.place_park
            PlaceKind.GARDEN -> R.string.place_garden
            PlaceKind.NATURE -> R.string.place_nature
            PlaceKind.SQUARE -> R.string.place_square
            PlaceKind.VIEWPOINT -> R.string.place_viewpoint
            PlaceKind.ARTWORK -> R.string.place_artwork
            PlaceKind.MONUMENT -> R.string.place_monument
            PlaceKind.FOUNTAIN -> R.string.place_fountain
            PlaceKind.WATERFRONT -> R.string.place_waterfront
        },
    )
    return place.name?.let { "$it ($kind)" } ?: kind
}

/** Home: today's quest, or a link to find one. */
object QuestDaySection : HomeSection {
    override val id = "outdoors.today"
    override val title = R.string.module_outdoors

    @Composable
    override fun rememberItems(host: HomeHost): LazyListScope.() -> Unit {
        val vm: QuestViewModel = viewModel(factory = QuestViewModel.Factory)
        val state by vm.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val quest = state.questOn(host.day)
        return {
            item(key = "outdoors.header") { SectionHeader(stringResource(R.string.module_outdoors)) }
            item(key = "outdoors.card") {
                AppCard(onClick = { host.navigator.navigate(OutdoorsModule.SCREEN_ROUTE) }) {
                    if (quest == null) {
                        Text(stringResource(if (host.isToday) R.string.quest_home_find else R.string.quest_home_none), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(placeTitle(context, quest.place), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(
                                when (quest.status) {
                                    QuestStatus.DONE -> if (quest.method == QuestMethod.MANUAL) R.string.quest_done_manual else R.string.quest_done
                                    else -> vm.tasks[quest.task.coerceIn(0, vm.tasks.lastIndex)]
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
