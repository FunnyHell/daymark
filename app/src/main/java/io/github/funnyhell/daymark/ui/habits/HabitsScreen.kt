package io.github.funnyhell.daymark.ui.habits

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import io.github.funnyhell.daymark.domain.habit.HabitEvaluator
import io.github.funnyhell.daymark.ui.common.ActionableListCard
import io.github.funnyhell.daymark.ui.common.ItemActions
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.ColorDot
import io.github.funnyhell.daymark.ui.common.EmptyState
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class HabitListItem(
    val id: String,
    val name: String,
    val colorArgb: Long,
    val typeText: String,
    val scheduleText: String,
    val streakText: String?,
    val status: LifecycleStatus,
)

data class HabitsState(val loading: Boolean = true, val items: List<HabitListItem> = emptyList())

class HabitsViewModel(application: Application) : AppViewModel(application) {
    val state: StateFlow<HabitsState> = combine(container.habits.observeAll(), container.settings.settings) { habits, settings ->
        val today = container.time.today()
        HabitsState(
            loading = false,
            items = habits.map { data ->
                val streak = HabitEvaluator(data, settings.firstDayOfWeek).streaks(today)
                HabitListItem(
                    id = data.habit.id,
                    name = data.habit.name,
                    colorArgb = data.habit.colorArgb,
                    typeText = HabitPresentation.typeLabel(app, data.habit.type),
                    scheduleText = HabitPresentation.scheduleText(app, data, today),
                    streakText = streak.current.takeIf { it > 0 }?.let { string(R.string.streak_current, it) },
                    status = data.habit.status,
                )
            },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsState())

    fun actionsFor(item: HabitListItem) = ItemActions(
        status = item.status,
        onPause = { launchSafely { container.habits.pause(item.id); say(string(R.string.msg_paused)) } },
        onResume = { launchSafely { container.habits.resume(item.id); say(string(R.string.msg_resumed)) } },
        onArchive = { launchSafely { container.habits.archive(item.id); say(string(R.string.msg_archived_done), string(R.string.undo)) { launchSafely { container.habits.resume(item.id) } } } },
        onDelete = { launchSafely { container.habits.delete(item.id); say(string(R.string.msg_deleted)) } },
    )

    companion object {
        val Factory = appViewModelFactory { app, _ -> HabitsViewModel(app) }
    }
}

@Composable
fun HabitsScreen(
    contentPadding: PaddingValues,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    vm: HabitsViewModel = viewModel(factory = HabitsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showArchived by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 88.dp)) {
        if (!state.loading && state.items.isEmpty()) {
            item {
                EmptyState(stringResource(R.string.habits_empty_title), stringResource(R.string.habits_empty_body)) {
                    Button(onClick = onNew, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.new_habit)) }
                }
            }
        }
        if (state.items.isNotEmpty()) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.list_actions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
        val groups = listOf(
            LifecycleStatus.ACTIVE to R.string.status_active,
            LifecycleStatus.PAUSED to R.string.status_paused,
        )
        for ((status, title) in groups) {
            val list = state.items.filter { it.status == status }
            if (list.isEmpty()) continue
            item(key = "h-$status") { SectionHeader(stringResource(title)) }
            items(list, key = { it.id }) { HabitListRow(it, vm.actionsFor(it), onOpen) }
        }
        val archived = state.items.filter { it.status == LifecycleStatus.ARCHIVED }
        if (archived.isNotEmpty()) {
            item(key = "h-archived") {
                SectionHeader(stringResource(R.string.status_archived)) {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(stringResource(if (showArchived) R.string.hide else R.string.show_count, archived.size))
                    }
                }
            }
            if (showArchived) items(archived, key = { it.id }) { HabitListRow(it, vm.actionsFor(it), onOpen) }
        }
    }
}

@Composable
private fun HabitListRow(item: HabitListItem, actions: ItemActions, onOpen: (String) -> Unit) {
    ActionableListCard(
        actions = actions,
        deleteTitle = stringResource(R.string.delete_title),
        deleteBody = stringResource(R.string.delete_habit_body),
        onClick = { onOpen(item.id) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(item.colorArgb, size = 14)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${item.typeText} · ${item.scheduleText}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.streakText?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
