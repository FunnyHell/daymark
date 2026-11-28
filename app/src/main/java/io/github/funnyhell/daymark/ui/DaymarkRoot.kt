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
import io.github.funnyhell.daymark.ui.history.HistoryScreen
import io.github.funnyhell.daymark.ui.onboarding.OnboardingScreen
import io.github.funnyhell.daymark.ui.settings.BackupSettingsScreen
import io.github.funnyhell.daymark.ui.settings.RemindersSettingsScreen
import io.github.funnyhell.daymark.ui.settings.SettingsScreen
import io.github.funnyhell.daymark.ui.sync.SyncScreen
import io.github.funnyhell.daymark.module.ModuleHost
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleNavigator
import io.github.funnyhell.daymark.module.ModuleRegistry
import io.github.funnyhell.daymark.ui.home.HomeScreen
import io.github.funnyhell.daymark.ui.settings.ModulesScreen
import io.github.funnyhell.daymark.ui.settings.TabsScreen
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Screens of the app shell; module screens bring their own routes (see DaymarkModule.screens). */
private object Routes {
    const val SETTINGS = "settings"
    const val SYNC = "sync"
    const val REMINDERS = "settings/reminders"
    const val BACKUP = "settings/backup"
    const val MODULES = "settings/modules"
    const val TABS = "settings/tabs"
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
    val show = showMessage
    val navigator = remember(nav) {
        object : ModuleNavigator {
            override fun navigate(route: String) = nav.navigate(route)
            override fun back(from: NavBackStackEntry) = nav.popFrom(from)
            override suspend fun showMessage(message: UiMessage) = show(message)
        }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "main") {
            composable("main") { MainTabs(navigator, showMessage) }
            composable(Routes.SYNC) { entry -> SyncScreen(onBack = { nav.popFrom(entry) }) }
            composable(Routes.REMINDERS) { entry -> RemindersSettingsScreen(onBack = { nav.popFrom(entry) }, showMessage = showMessage) }
            composable(Routes.BACKUP) { entry -> BackupSettingsScreen(onBack = { nav.popFrom(entry) }, showMessage = showMessage) }
            composable(Routes.MODULES) { entry -> ModulesScreen(onBack = { nav.popFrom(entry) }, onOpen = { nav.navigate(it) }) }
            composable(Routes.TABS) { entry -> TabsScreen(onBack = { nav.popFrom(entry) }) }
            composable(Routes.SETTINGS) { entry ->
                SettingsRoot(
                    onBack = { nav.popFrom(entry) },
                    showMessage = showMessage,
                    onOpen = { nav.navigate(it) },
                )
            }
            // Every module registers its screens, also while it is off, so open links and the back stack stay valid.
            ModuleRegistry.all.forEach { module -> with(module) { screens(navigator) } }
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


/** Settings, opened from the gear on Home. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(onBack: () -> Unit, showMessage: suspend (UiMessage) -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        SettingsScreen(
            padding,
            showMessage,
            onOpenReminders = { onOpen(Routes.REMINDERS) },
            onOpenBackup = { onOpen(Routes.BACKUP) },
            onOpenSync = { onOpen(Routes.SYNC) },
            onOpenModules = { onOpen(Routes.MODULES) },
            onOpenTabs = { onOpen(Routes.TABS) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabs(navigator: ModuleNavigator, showMessage: suspend (UiMessage) -> Unit) {
    val context = LocalContext.current
    val settings by context.container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val current = settings ?: return
    val tabsNav = rememberNavController()
    val openTab: (String) -> Unit = { route ->
        tabsNav.navigate(route) {
            popUpTo(tabsNav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val moduleTabs = ModuleRegistry.enabled(current).mapNotNull { m -> m.tab }.associateBy { it.route }
    val barTabs = listOf(TabSpec(AppTabs.HOME, R.string.tab_today, ModuleIcon.Vector(Icons.Filled.Home))) + AppTabs.chosen(current)
    val entry by tabsNav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: AppTabs.HOME
    val open = AppTabs.available(current).map { it.route } + AppTabs.HOME
    // A tab of a module that was just turned off is left for Home.
    LaunchedEffect(open, route) {
        if (route !in open) tabsNav.navigate(AppTabs.HOME) { popUpTo(tabsNav.graph.findStartDestination().id) { inclusive = false } }
    }
    val title = when (route) {
        AppTabs.HOME -> R.string.app_name
        AppTabs.HISTORY -> R.string.tab_history
        else -> moduleTabs[route]?.label ?: R.string.app_name
    }
    val fab = moduleTabs[route]?.fab
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (route == AppTabs.HOME) {
                        IconButton(onClick = { navigator.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.tab_settings))
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                barTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab.route == route,
                        onClick = { openTab(tab.route) },
                        icon = {
                            when (val icon = tab.icon) {
                                is ModuleIcon.Vector -> Icon(icon.image, contentDescription = null)
                                is ModuleIcon.Drawable -> Icon(painterResource(icon.res), contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(tab.label), maxLines = 1) },
                    )
                }
            }
        },
        floatingActionButton = {
            fab?.let {
                ExtendedFloatingActionButton(
                    onClick = { it.onClick(navigator) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(it.label)) },
                )
            }
        },
    ) { padding ->
        val host = object : ModuleHost {
            override val navigator = navigator
            override val contentPadding = padding
        }
        val tabsByRoute by rememberUpdatedState(moduleTabs)
        // Routes of every screen that can be a tab are registered once, so turning a module on or off or changing
        // the bottom bar does not rebuild the graph. A screen not in the bar is opened from Home.
        val allRoutes = remember { ModuleRegistry.all.mapNotNull { it.tab?.route } }
        NavHost(tabsNav, startDestination = AppTabs.HOME) {
            composable(AppTabs.HOME) {
                HomeScreen(padding, navigator, onOpenModules = { navigator.navigate(Routes.MODULES) }, onOpenTab = openTab)
            }
            composable(AppTabs.HISTORY) { HistoryScreen(padding, showMessage) }
            allRoutes.forEach { r ->
                composable(r) { tabsByRoute[r]?.let { tab -> tab.Content(host) } }
            }
        }
    }
}
