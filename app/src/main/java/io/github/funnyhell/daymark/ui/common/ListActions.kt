package io.github.funnyhell.daymark.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.domain.common.LifecycleStatus

/** Lifecycle actions offered for a habit or medication in a list. */
class ItemActions(
    val status: LifecycleStatus,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onArchive: () -> Unit,
)

/**
 * A list card with quick actions: swipe right to pause/resume, swipe left to archive (or restore from the
 * archive). A long press starts multi-selection (see [SelectionBar]); while selecting, taps toggle selection and
 * swipes are off. The same single-item actions are exposed to accessibility services as custom actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ActionableListCard(
    actions: ItemActions,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pauseLabel = stringResource(if (actions.status == LifecycleStatus.ACTIVE) R.string.pause else R.string.resume)
    val archiveLabel = stringResource(if (actions.status == LifecycleStatus.ARCHIVED) R.string.unarchive else R.string.archive)
    val selectLabel = stringResource(R.string.select)
    val togglePause = { if (actions.status == LifecycleStatus.ACTIVE) actions.onPause() else actions.onResume() }
    val toggleArchive = { if (actions.status == LifecycleStatus.ARCHIVED) actions.onResume() else actions.onArchive() }
    val shape = MaterialTheme.shapes.large

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> togglePause()
                SwipeToDismissBoxValue.EndToStart -> toggleArchive()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // The card always snaps back; the list then moves it to its new section.
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            val direction = state.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                val start = direction == SwipeToDismissBoxValue.StartToEnd
                val label = if (start) pauseLabel else archiveLabel
                val icon = when {
                    start && actions.status == LifecycleStatus.ACTIVE -> R.drawable.ic_pause
                    start -> R.drawable.ic_play
                    actions.status == LifecycleStatus.ARCHIVED -> R.drawable.ic_play
                    else -> R.drawable.ic_archive
                }
                Box(
                    Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
                    contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // Clip first so the press ripple follows the rounded card instead of a square.
                .clip(shape)
                .combinedClickable(
                    onClick = { if (selectionMode) onToggleSelect() else onClick() },
                    onLongClick = onToggleSelect,
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(pauseLabel) { togglePause(); true },
                        CustomAccessibilityAction(archiveLabel) { toggleArchive(); true },
                        CustomAccessibilityAction(selectLabel) { onToggleSelect(); true },
                    )
                },
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
                }
                Column(Modifier.weight(1f), content = content)
            }
        }
    }
}

/** Contextual bar shown while items are selected: bulk pause/resume, archive and delete (confirmed). */
@Composable
fun SelectionBar(
    count: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    deleteTitle: String,
    deleteBody: String,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cancel)) }
            Text(
                stringResource(R.string.selected_count, count),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPause) { Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.pause)) }
            IconButton(onClick = onResume) { Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.resume)) }
            IconButton(onClick = onArchive) { Icon(painterResource(R.drawable.ic_archive), contentDescription = stringResource(R.string.archive)) }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_permanently), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = deleteTitle,
            text = deleteBody,
            confirmLabel = stringResource(R.string.delete_permanently),
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

/** Actions available for a multi-selection in a list. */
enum class BulkAction { PAUSE, RESUME, ARCHIVE, DELETE }
