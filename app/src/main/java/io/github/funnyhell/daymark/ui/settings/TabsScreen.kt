package io.github.funnyhell.daymark.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.ui.AppTabs
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TabsViewModel(application: Application) : AppViewModel(application) {
    val settings: StateFlow<Settings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(routes: List<String>) = launchSafely { container.settings.setTabs(routes) }

    companion object {
        val Factory = appViewModelFactory { app, _ -> TabsViewModel(app) }
    }
}

/**
 * Which screens are in the bottom bar after Home, and in what order. Screens left out are opened from Home.
 * At most [AppTabs.MAX] fit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsScreen(onBack: () -> Unit, vm: TabsViewModel = viewModel(factory = TabsViewModel.Factory)) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_tabs)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        val chosen = AppTabs.chosen(s).map { it.route }
        // Chosen tabs first in their order, then the rest.
        val all = AppTabs.chosen(s) + AppTabs.notInBar(s)
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.settings_tabs_desc, AppTabs.MAX),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            all.forEach { tab ->
                val inBar = tab.route in chosen
                val index = chosen.indexOf(tab.route)
                val label = stringResource(tab.label)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = inBar,
                            enabled = inBar || chosen.size < AppTabs.MAX,
                            role = Role.Checkbox,
                            onValueChange = { add -> vm.save(if (add) chosen + tab.route else chosen - tab.route) },
                        )
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = inBar, onCheckedChange = null, enabled = inBar || chosen.size < AppTabs.MAX)
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    if (inBar) {
                        IconButton(
                            onClick = { vm.save(chosen.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                            enabled = index > 0,
                        ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up, label)) }
                        IconButton(
                            onClick = { vm.save(chosen.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                            enabled = index < chosen.lastIndex,
                        ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down, label)) }
                    }
                }
            }
        }
    }
}
