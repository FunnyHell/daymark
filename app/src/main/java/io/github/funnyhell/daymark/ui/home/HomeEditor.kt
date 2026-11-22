package io.github.funnyhell.daymark.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.repo.Settings
import io.github.funnyhell.daymark.module.HomeLayout
import io.github.funnyhell.daymark.module.HomeSection
import io.github.funnyhell.daymark.module.ModuleRegistry

/** Show, hide and reorder the Home sections of the enabled modules. Changes are saved as they are made. */
@Composable
fun HomeEditor(settings: Settings, onSave: (List<String>, Set<String>) -> Unit, onOpenModules: () -> Unit, onDismiss: () -> Unit) {
    val modules = ModuleRegistry.enabled(settings)
    val sections = modules.flatMap { it.homeSections }
    val moduleOf = modules.flatMap { m -> m.homeSections.map { it.id to m } }.toMap()
    var order by remember { mutableStateOf(HomeLayout.order(sections, HomeSection::id, settings.homeOrder).map { it.id }) }
    var hidden by remember { mutableStateOf(settings.homeHidden) }
    fun save() = onSave(order, hidden)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (order.isEmpty()) {
                    Text(stringResource(R.string.home_no_sections), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                order.forEachIndexed { index, id ->
                    val section = sections.first { it.id == id }
                    val module = moduleOf[id]
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = id !in hidden,
                            onCheckedChange = { show -> hidden = if (show) hidden - id else hidden + id; save() },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(section.title), style = MaterialTheme.typography.bodyLarge)
                            if (module != null && module.title != section.title) {
                                Text(stringResource(module.title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(
                            onClick = { order = order.toMutableList().apply { add(index - 1, removeAt(index)) }; save() },
                            enabled = index > 0,
                        ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up, stringResource(section.title))) }
                        IconButton(
                            onClick = { order = order.toMutableList().apply { add(index + 1, removeAt(index)) }; save() },
                            enabled = index < order.lastIndex,
                        ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down, stringResource(section.title))) }
                    }
                }
                TextButton(onClick = onOpenModules, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.home_manage_modules)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_done)) } },
    )
}
