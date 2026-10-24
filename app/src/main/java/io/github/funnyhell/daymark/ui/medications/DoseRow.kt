package io.github.funnyhell.daymark.ui.medications

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.medication.OccurrenceState
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.ColorDot
import io.github.funnyhell.daymark.ui.common.StatusChip

/** One scheduled dose with Taken / Skip / Snooze, or its recorded state with a correction option. */
@Composable
fun DoseRow(
    item: DoseItem,
    onTake: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: (() -> Unit)?,
    onCorrect: (String) -> Unit,
    onOpen: (() -> Unit)? = null,
    showName: Boolean = true,
) {
    AppCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(item.colorArgb, size = 14)
            Spacer(Modifier.width(12.dp))
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
            }
            StateChip(item)
        }
        if (item.state == OccurrenceState.SNOOZED && item.snoozedUntilText != null) {
            Text(
                stringResource(R.string.snoozed_until, item.snoozedUntilText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.resolved) {
                TextButton(onClick = { item.resolutionEventId?.let(onCorrect) }) { Text(stringResource(R.string.correct)) }
            } else {
                var menu by remember { mutableStateOf(false) }
                OutlinedButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) }
                FilledTonalButton(onClick = onTake) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_taken))
                }
                if (onSnooze != null) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_snooze)) }, onClick = { menu = false; onSnooze() })
                    }
                }
            }
        }
    }
}

@Composable
private fun StateChip(item: DoseItem) {
    val scheme = MaterialTheme.colorScheme
    val (icon, container, content) = when (item.state) {
        OccurrenceState.TAKEN -> Triple(Icons.Filled.Check, scheme.secondaryContainer, scheme.onSecondaryContainer)
        OccurrenceState.SKIPPED -> Triple(Icons.Filled.Close, scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
        OccurrenceState.MISSED -> Triple(Icons.Filled.Warning, scheme.errorContainer, scheme.onErrorContainer)
        OccurrenceState.DUE -> Triple(Icons.Filled.Notifications, scheme.primaryContainer, scheme.onPrimaryContainer)
        OccurrenceState.SNOOZED -> Triple(Icons.Filled.Notifications, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        OccurrenceState.UPCOMING -> Triple(null, scheme.surfaceContainerHigh, scheme.onSurfaceVariant)
    }
    StatusChip(item.stateText, icon, container, content)
}
