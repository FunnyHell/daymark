package io.github.funnyhell.daymark.modules.habits

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.module.DaymarkModule
import io.github.funnyhell.daymark.module.HomeHost
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleFab
import io.github.funnyhell.daymark.module.ModuleHost
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleTab
import io.github.funnyhell.daymark.ui.common.MessageEffect
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.TrackingViewModel
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.habits.HabitDetailScreen
import io.github.funnyhell.daymark.ui.habits.HabitEditScreen
import io.github.funnyhell.daymark.ui.habits.HabitPresentation
import io.github.funnyhell.daymark.ui.habits.HabitTodayItem
import io.github.funnyhell.daymark.ui.habits.HabitTodayRow
import io.github.funnyhell.daymark.ui.habits.HabitsScreen
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** Habits: the Habits tab, habit screens and the habits of the day on Home. Reminders are built in (ReminderEngine). */
object HabitsModule : DaymarkModule {
    const val ID = "habits"
    override val id = ID
    override val title = R.string.module_habits
    override val description = R.string.module_habits_desc
    override val icon = ModuleIcon.Vector(Icons.Filled.CheckCircle)

    object Routes {
        const val EDIT = "habit/edit?id={id}"
        const val DETAIL = "habit/{id}"
        fun edit(id: String? = null) = "habit/edit?id=${id.orEmpty()}"
        fun detail(id: String) = "habit/$id"
    }

    override val tab = object : ModuleTab {
        override val route = "habits"
        override val label = R.string.tab_habits
        override val fab = ModuleFab(R.string.new_habit) { it.navigate(Routes.edit()) }

        @Composable
        override fun Content(host: ModuleHost) {
            HabitsScreen(host.contentPadding, onOpen = { host.navigator.navigate(Routes.detail(it)) }, onNew = { host.navigator.navigate(Routes.edit()) })
        }
    }

    override val homeSections: List<HomeSection> = listOf(HabitsDaySection)

    override fun NavGraphBuilder.screens(nav: ModuleNavigator) {
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
            HabitEditScreen(onDone = { nav.back(entry) }, showMessage = nav::showMessage)
        }
        composable(Routes.DETAIL) { entry ->
            HabitDetailScreen(onBack = { nav.back(entry) }, onEdit = { nav.navigate(Routes.edit(it)) }, showMessage = nav::showMessage)
        }
    }
}

data class HabitsDayState(val loading: Boolean = true, val items: List<HabitTodayItem> = emptyList(), val hasHabits: Boolean = false)

class HabitsDayViewModel(application: Application) : TrackingViewModel(application) {
    private val day = MutableStateFlow<LocalDate?>(null)

    fun show(date: LocalDate) {
        day.value = date
    }

    val state: StateFlow<HabitsDayState> = combine(container.habits.observeAll(), container.settings.settings, day) { habits, settings, chosen ->
        val date = chosen ?: container.time.today()
        HabitsDayState(
            loading = false,
            items = habits.mapNotNull { HabitPresentation.todayItem(app, it, date, settings.firstDayOfWeek) },
            hasHabits = habits.isNotEmpty(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsDayState())

    companion object {
        val Factory = appViewModelFactory { app, _ -> HabitsDayViewModel(app) }
    }
}

/** The habits due on the shown day, with the same actions as before Home became configurable. */
object HabitsDaySection : HomeSection {
    override val id = "habits.day"
    override val title = R.string.section_habits

    @Composable
    override fun rememberItems(host: HomeHost): LazyListScope.() -> Unit {
        val vm: HabitsDayViewModel = viewModel(factory = HabitsDayViewModel.Factory)
        LaunchedEffect(host.day) { vm.show(host.day) }
        val state by vm.state.collectAsStateWithLifecycle()
        MessageEffect(vm.messages) { host.navigator.showMessage(it) }
        val day = host.day.takeIf { !host.isToday }
        return {
            if (!state.loading) {
                item(key = "habits.header") {
                    SectionHeader(stringResource(R.string.section_habits)) {
                        if (state.items.isNotEmpty()) {
                            Text(
                                stringResource(R.string.today_progress, state.items.count { it.done }, state.items.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.items.isEmpty()) {
                    item(key = "habits.empty") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                stringResource(if (state.hasHabits) R.string.today_no_habits else R.string.home_no_habits_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(top = 12.dp),
                            )
                            if (!state.hasHabits) {
                                TextButton(onClick = { host.navigator.navigate(HabitsModule.Routes.edit()) }) { Text(stringResource(R.string.new_habit)) }
                            }
                        }
                    }
                }
                items(state.items, key = { "habit" + it.habitId }) { habit ->
                    HabitTodayRow(
                        item = habit,
                        onOpen = { host.navigator.navigate(HabitsModule.Routes.detail(habit.habitId)) },
                        onSetDone = { done -> vm.setHabitDone(habit.habitId, done, day) },
                        onAddAmount = { vm.addHabitProgress(habit.habitId, it, day) },
                        onSkip = { vm.skipHabit(habit.habitId, day) },
                        onLapse = { note -> vm.recordLapse(habit.habitId, day, note) },
                        onUndo = { vm.undoHabitEvent(it) },
                    )
                }
            }
        }
    }
}
