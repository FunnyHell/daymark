package io.github.funnyhell.daymark.ui.habits

import androidx.compose.foundation.layout.Arrangement
import io.github.funnyhell.daymark.ui.common.NoteDialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.habit.HabitType
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.NumberInputDialog
import io.github.funnyhell.daymark.ui.common.ProgressRing

/** A habit as shown on the Today screen, with the actions that fit its type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitTodayRow(
    item: HabitTodayItem,
    onOpen: () -> Unit,
    onSetDone: (Boolean) -> Unit,
    onAddAmount: (Double) -> Unit,
    onSkip: () -> Unit,
    onLapse: (String?) -> Unit,
    onUndo: (String) -> Unit,
    onMarkStep: (stepId: String, done: Boolean) -> Unit = { _, _ -> },
    onNote: (String?) -> Unit = {},
) {
    var noteDialog by remember { mutableStateOf(false) }
    var amountDialog by remember { mutableStateOf(false) }
    var lapseDialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val color = Color(item.colorArgb)
    val markLabel = stringResource(R.string.widget_done_cd, item.name)
    val unmarkLabel = stringResource(R.string.cd_unmark, item.name)
    AppCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val checked = item.checked
            val ringModifier = if (checked != null) {
                Modifier
                    .clip(CircleShape)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onSetDone(it) })
                    .semantics {
                        contentDescription = if (checked) unmarkLabel else markLabel
                    }
            } else {
                Modifier
            }
            ProgressRing(item.fraction, item.ringLabel, color, modifier = ringModifier, done = item.done && item.type != HabitType.ABSTINENCE)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(item.statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.streakText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                item.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { noteDialog = true },
                    )
                }
            }
            when (item.type) {
                HabitType.QUANTITY -> FilledTonalIconButton(onClick = { amountDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_amount, item.name))
                }
                HabitType.ABSTINENCE -> OutlinedButton(onClick = { lapseDialog = true }) { Text(stringResource(R.string.action_lapse)) }
                HabitType.CHECK, HabitType.MULTI, HabitType.WEEKLY_GOAL, HabitType.CHECKLIST, HabitType.WEEKLY_CHECKLIST -> Unit
            }
            run {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (item.note == null) R.string.note_add else R.string.note_edit)) },
                        onClick = { menu = false; noteDialog = true },
                    )
                    item.undoEventId?.let { id ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.undo_last)) }, onClick = { menu = false; onUndo(id) })
                    }
                    if (!item.done && !item.skipped && item.type in setOf(HabitType.CHECK, HabitType.MULTI, HabitType.QUANTITY)) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.skip_today)) }, onClick = { menu = false; onSkip() })
                    }
                }
            }
        }
        if (item.steps.isNotEmpty()) {
            // Each step: tap to mark it once more; when it is complete, tap to remove its latest mark.
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.steps.forEach { step ->
                    val label = if (step.target > 1) "${step.title} ${step.done.coerceAtMost(step.target)}/${step.target}" else step.title
                    FilterChip(
                        selected = step.complete,
                        onClick = { onMarkStep(step.stepId, !step.complete) },
                        label = { Text(label) },
                        leadingIcon = if (step.complete) ({ Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
                    )
                }
            }
        }
    }
    if (amountDialog) {
        NumberInputDialog(
            title = stringResource(R.string.add_progress_title, item.name),
            label = item.unit.orEmpty(),
            onConfirm = { value, _ -> onAddAmount(value) },
            onDismiss = { amountDialog = false },
        )
    }
    if (noteDialog) {
        NoteDialog(item.name, item.note, onSave = onNote, onDismiss = { noteDialog = false })
    }
    if (lapseDialog) {
        LapseDialog(item.name, onConfirm = onLapse, onDismiss = { lapseDialog = false })
    }
}

@Composable
fun LapseDialog(name: String, onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lapse_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lapse_body))
                androidx.compose.material3.OutlinedTextField(
                    value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.note_optional)) }, singleLine = true,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(note.ifBlank { null }); onDismiss() }) { Text(stringResource(R.string.action_lapse)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
