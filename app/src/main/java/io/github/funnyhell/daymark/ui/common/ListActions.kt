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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    val onDelete: () -> Unit,
)

/**
 * A list card with quick actions: swipe right to pause/resume, swipe left to archive (or restore from the
 * archive), long press for a menu that also offers permanent deletion (confirmed). The same actions are exposed
 * to accessibility services as custom actions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ActionableListCard(
    actions: ItemActions,
    deleteTitle: String,
    deleteBody: String,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pauseLabel = stringResource(if (actions.status == LifecycleStatus.ACTIVE) R.string.pause else R.string.resume)
    val archiveLabel = stringResource(if (actions.status == LifecycleStatus.ARCHIVED) R.string.unarchive else R.string.archive)
    val deleteLabel = stringResource(R.string.delete_permanently)
    val togglePause = { if (actions.status == LifecycleStatus.ACTIVE) actions.onPause() else actions.onResume() }
    val toggleArchive = { if (actions.status == LifecycleStatus.ARCHIVED) actions.onResume() else actions.onArchive() }

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
        backgroundContent = {
            val direction = state.dismissDirection
            val (label, icon, alignment) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(
                    pauseLabel,
                    if (actions.status == LifecycleStatus.ACTIVE) R.drawable.ic_pause else R.drawable.ic_play,
                    Alignment.CenterStart,
                )
                SwipeToDismissBoxValue.EndToStart -> Triple(
                    archiveLabel,
                    if (actions.status == LifecycleStatus.ARCHIVED) R.drawable.ic_play else R.drawable.ic_archive,
                    Alignment.CenterEnd,
                )
                SwipeToDismissBoxValue.Settled -> Triple("", null, Alignment.Center)
            }
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.large).padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(label, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(pauseLabel) { togglePause(); true },
                            CustomAccessibilityAction(archiveLabel) { toggleArchive(); true },
                            CustomAccessibilityAction(deleteLabel) { confirmDelete = true; true },
                        )
                    },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp), content = content)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (actions.status != LifecycleStatus.ARCHIVED) {
                    DropdownMenuItem(text = { Text(pauseLabel) }, onClick = { menu = false; togglePause() })
                }
                DropdownMenuItem(text = { Text(archiveLabel) }, onClick = { menu = false; toggleArchive() })
                DropdownMenuItem(
                    text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; confirmDelete = true },
                )
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = deleteTitle,
            text = deleteBody,
            confirmLabel = deleteLabel,
            onConfirm = actions.onDelete,
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}
