package io.github.funnyhell.daymark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.container
import io.github.funnyhell.daymark.ui.common.UiMessage
import io.github.funnyhell.daymark.ui.habits.HabitDetailScreen
import io.github.funnyhell.daymark.ui.habits.HabitEditScreen
import io.github.funnyhell.daymark.ui.habits.HabitsScreen
import io.github.funnyhell.daymark.ui.history.HistoryScreen
import io.github.funnyhell.daymark.ui.medications.MedicationDetailScreen
import io.github.funnyhell.daymark.ui.medications.MedicationEditScreen
import io.github.funnyhell.daymark.ui.medications.MedicationsScreen
import io.github.funnyhell.daymark.ui.onboarding.OnboardingScreen
import io.github.funnyhell.daymark.ui.settings.BackupSettingsScreen
import io.github.funnyhell.daymark.ui.settings.RemindersSettingsScreen
import io.github.funnyhell.daymark.ui.settings.SettingsScreen
import io.github.funnyhell.daymark.ui.sync.SyncScreen
import io.github.funnyhell.daymark.ui.today.TodayScreen
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: Int, val icon: ImageVector?, val iconRes: Int?) {
    TODAY("today", R.string.tab_today, Icons.Filled.Home, null),
    HABITS("habits", R.string.tab_habits, Icons.Filled.CheckCircle, null),
    MEDICATIONS("medications", R.string.tab_medications, null, R.drawable.ic_pill),
    HISTORY("history", R.string.tab_history, Icons.Filled.DateRange, null),
    SETTINGS("settings", R.string.tab_settings, Icons.Filled.Settings, null),
}

private object Routes {
    const val HABIT_EDIT = "habit/edit?id={id}"
    const val HABIT_DETAIL = "habit/{id}"
    const val MED_EDIT = "medication/edit?id={id}"
    const val MED_DETAIL = "medication/{id}"
    const val SYNC = "sync"
    const val REMINDERS = "settings/reminders"
    const val BACKUP = "settings/backup"
    fun habitEdit(id: String? = null) = "habit/edit?id=${id.orEmpty()}"
    fun habitDetail(id: String) = "habit/$id"
    fun medEdit(id: String? = null) = "medication/edit?id=${id.orEmpty()}"
    fun medDetail(id: String) = "medication/$id"
}

@Composable
fun DaymarkRoot(onboardingDone: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    if (!onboardingDone) {
        OnboardingScreen(onDone = { scope.launch { context.container.settings.setOnboardingDone() } })
        return
    }
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val showMessage: suspend (UiMessage) -> Unit = { msg ->
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(
            message = msg.text,
            actionLabel = msg.actionLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) msg.onAction?.invoke()
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "main") {
            composable("main") { MainTabs(nav, showMessage) }
            composable(Routes.HABIT_EDIT, arguments = listOf(optionalId())) { entry ->
                HabitEditScreen(onDone = { nav.popFrom(entry) }, showMessage = showMessage)
            }
            composable(Routes.HABIT_DETAIL) { entry ->
                HabitDetailScreen(onBack = { nav.popFrom(entry) }, onEdit = { nav.navigate(Routes.habitEdit(it)) }, showMessage = showMessage)
            }
            composable(Routes.MED_EDIT, arguments = listOf(optionalId())) { entry ->
                MedicationEditScreen(onDone = { nav.popFrom(entry) }, showMessage = showMessage)
            }
            composable(Routes.SYNC) { entry -> SyncScreen(onBack = { nav.popFrom(entry) }) }
            composable(Routes.REMINDERS) { entry -> RemindersSettingsScreen(onBack = { nav.popFrom(entry) }, showMessage = showMessage) }
            composable(Routes.BACKUP) { entry -> BackupSettingsScreen(onBack = { nav.popFrom(entry) }, showMessage = showMessage) }
            composable(Routes.MED_DETAIL) { entry ->
                MedicationDetailScreen(onBack = { nav.popFrom(entry) }, onEdit = { nav.navigate(Routes.medEdit(it)) }, showMessage = showMessage)
            }
        }
        // One snackbar host above all screens, kept clear of the bottom navigation bar.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)) { data ->
            key(data) { SwipeableSnackbar(data) }
        }
    }
}

/** A snackbar that can also be swiped away sideways. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSnackbar(data: SnackbarData) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) data.dismiss()
            true
        },
    )
    SwipeToDismissBox(state = state, backgroundContent = {}) { Snackbar(data) }
}

/**
 * Leaves [entry] only if it is still the screen on top. Repeated calls (a double tap on Back, or "deleted" and
 * "record disappeared" both asking to close) therefore never pop the main screen and leave a blank window.
 */
private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id && previousBackStackEntry != null) popBackStack()
}

private fun optionalId() = navArgument("id") {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(rootNav: NavHostController, showMessage: suspend (UiMessage) -> Unit) {
    val tabsNav = rememberNavController()
    val entry by tabsNav.currentBackStackEntryAsState()
    val current = Tab.entries.firstOrNull { it.route == entry?.destination?.route } ?: Tab.TODAY
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (current == Tab.TODAY) R.string.app_name else current.label), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = {
                            tabsNav.navigate(tab.route) {
                                popUpTo(tabsNav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (tab.icon != null) Icon(tab.icon, contentDescription = null) else Icon(painterResource(tab.iconRes!!), contentDescription = null)
                        },
                        label = { Text(stringResource(tab.label), maxLines = 1) },
                    )
                }
            }
        },
        floatingActionButton = {
            when (current) {
                Tab.HABITS -> ExtendedFloatingActionButton(
                    onClick = { rootNav.navigate(Routes.habitEdit()) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_habit)) },
                )
                Tab.MEDICATIONS -> ExtendedFloatingActionButton(
                    onClick = { rootNav.navigate(Routes.medEdit()) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_medication)) },
                )
                else -> Unit
            }
        },
    ) { padding ->
        NavHost(tabsNav, startDestination = Tab.TODAY.route, modifier = Modifier) {
            composable(Tab.TODAY.route) {
                TodayScreen(
                    contentPadding = padding,
                    onOpenHabit = { rootNav.navigate(Routes.habitDetail(it)) },
                    onOpenMedication = { rootNav.navigate(Routes.medDetail(it)) },
                    onNewHabit = { rootNav.navigate(Routes.habitEdit()) },
                    onNewMedication = { rootNav.navigate(Routes.medEdit()) },
                    showMessage = showMessage,
                )
            }
            composable(Tab.HABITS.route) {
                HabitsScreen(padding, onOpen = { rootNav.navigate(Routes.habitDetail(it)) }, onNew = { rootNav.navigate(Routes.habitEdit()) })
            }
            composable(Tab.MEDICATIONS.route) {
                MedicationsScreen(padding, onOpen = { rootNav.navigate(Routes.medDetail(it)) }, onNew = { rootNav.navigate(Routes.medEdit()) })
            }
            composable(Tab.HISTORY.route) { HistoryScreen(padding) }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(
                    padding,
                    showMessage,
                    onOpenReminders = { rootNav.navigate(Routes.REMINDERS) },
                    onOpenBackup = { rootNav.navigate(Routes.BACKUP) },
                    onOpenSync = { rootNav.navigate(Routes.SYNC) },
                )
            }
        }
    }
}
