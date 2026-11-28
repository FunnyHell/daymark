package io.github.funnyhell.daymark.ui.medications

import io.github.funnyhell.daymark.ui.common.NoteDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.StatusChip

/** One scheduled dose: a tap-to-toggle taken mark, Skip and Snooze while open, and its recorded state. */
@Composable
fun DoseRow(
    item: DoseItem,
    onTake: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: (() -> Unit)?,
    onCorrect: (String) -> Unit,
    onOpen: (() -> Unit)? = null,
    showName: Boolean = true,
    /** Writes the note of a recorded dose; null where notes are not offered. */
    onNote: ((String?) -> Unit)? = null,
) {
    var noteDialog by remember { mutableStateOf(false) }
    val toggleLabel = stringResource(if (item.resolved) R.string.cd_unmark else R.string.widget_take_cd, item.name)
    AppCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tap the circle to mark the dose taken; tap again to remove a taken or skipped mark.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .toggleable(
                        value = item.resolved,
                        role = Role.Checkbox,
                        onValueChange = { take -> if (take) onTake() else item.resolutionEventId?.let(onCorrect) },
                    )
                    .semantics { contentDescription = toggleLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(
                        when (item.state) {
                            OccurrenceState.TAKEN -> R.drawable.ic_widget_check_filled
                            OccurrenceState.SKIPPED -> R.drawable.ic_widget_skipped
                            else -> R.drawable.ic_widget_circle
                        },
                    ),
                    contentDescription = null,
                    tint = if (item.state == OccurrenceState.SKIPPED) MaterialTheme.colorScheme.onSurfaceVariant else Color(item.colorArgb),
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (showName) item.name else item.timeText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (showName) "${item.timeText} · ${item.doseText}" else item.doseText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            }
            // The circle already shows taken and skipped; the chip only adds what it does not show.
            if (item.state in setOf(OccurrenceState.DUE, OccurrenceState.MISSED, OccurrenceState.SNOOZED)) StateChip(item)
            if (!item.resolved || (onNote != null && item.resolutionEventId != null)) {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!item.resolved) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_skip)) }, onClick = { menu = false; onSkip() })
                        if (onSnooze != null) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_snooze)) }, onClick = { menu = false; onSnooze() })
                        }
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (item.note == null) R.string.note_add else R.string.note_edit)) },
                            onClick = { menu = false; noteDialog = true },
                        )
                    }
                }
            }
        }
    }
    if (noteDialog && onNote != null) {
        NoteDialog(item.name, item.note, onSave = onNote, onDismiss = { noteDialog = false })
    }
}

@Composable
private fun StateChip(item: DoseItem) {
    val scheme = MaterialTheme.colorScheme
    val text = if (item.state == OccurrenceState.SNOOZED && item.snoozedUntilText != null) item.snoozedUntilText else item.stateText
    val (icon, container, content) = when (item.state) {
        OccurrenceState.TAKEN -> Triple(Icons.Filled.Check, scheme.secondaryContainer, scheme.onSecondaryContainer)
        OccurrenceState.SKIPPED -> Triple(Icons.Filled.Close, scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
        OccurrenceState.MISSED -> Triple(Icons.Filled.Warning, scheme.errorContainer, scheme.onErrorContainer)
        OccurrenceState.DUE -> Triple(Icons.Filled.Notifications, scheme.primaryContainer, scheme.onPrimaryContainer)
        OccurrenceState.SNOOZED -> Triple(Icons.Filled.Notifications, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        OccurrenceState.UPCOMING -> Triple(null, scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
    }
    StatusChip(text, icon, container, content)
}
