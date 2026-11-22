package io.github.funnyhell.daymark.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.module.DaymarkModule
import io.github.funnyhell.daymark.module.ModuleIcon
import io.github.funnyhell.daymark.module.ModuleRegistry
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ModulesViewModel(application: Application) : AppViewModel(application) {
    val settings: StateFlow<Settings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    /** Turning a module off hides its tab, Home sections and reminders; its data stays. */
    fun setEnabled(module: DaymarkModule, enabled: Boolean) = launchSafely {
        container.settings.setModuleEnabled(module.id, enabled)
        if (!enabled) module.onDisabled(app)
        container.reminders.reschedule()
    }

    companion object {
        val Factory = appViewModelFactory { app, _ -> ModulesViewModel(app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModulesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: ModulesViewModel = viewModel(factory = ModulesViewModel.Factory)) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_modules)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.settings_modules_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ModuleRegistry.all.forEach { module ->
                val on = ModuleRegistry.isEnabled(module, settings)
                val title = stringResource(module.title)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = on, role = Role.Switch, onValueChange = { vm.setEnabled(module, it) })
                        .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (val icon = module.icon) {
                        is ModuleIcon.Vector -> Icon(icon.image, contentDescription = null, modifier = Modifier.size(24.dp))
                        is ModuleIcon.Drawable -> Icon(painterResource(icon.res), contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(module.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = null)
                }
                val route = module.settingsRoute
                if (on && route != null) {
                    TextButton(onClick = { onOpen(route) }, modifier = Modifier.padding(start = 56.dp)) { Text(stringResource(R.string.module_settings, title)) }
                }
            }
        }
    }
}
