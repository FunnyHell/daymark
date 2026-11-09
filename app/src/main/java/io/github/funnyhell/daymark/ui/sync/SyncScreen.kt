package io.github.funnyhell.daymark.ui.sync

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.sync.PairedDevice
import io.github.funnyhell.daymark.data.sync.SyncException
import io.github.funnyhell.daymark.platform.sync.SyncPeer
import io.github.funnyhell.daymark.platform.sync.SyncStatus
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.ConfirmDialog
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import io.github.funnyhell.daymark.ui.format.Formats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SyncViewModel(application: Application) : AppViewModel(application) {
    private val coordinator = container.syncCoordinator

    val status: StateFlow<SyncStatus> = coordinator.status
    val nearby: StateFlow<List<SyncPeer>> = coordinator.nearby
    val paired: StateFlow<List<PairedDevice>> = coordinator.paired.devices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val autoSync: StateFlow<Boolean> = container.settings.settings.map { it.autoSync }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun showCode() = coordinator.showCode()
    fun dismissStatus() = if (status.value is SyncStatus.ShowingCode) coordinator.cancelCode() else coordinator.clearStatus()
    fun pair(peer: SyncPeer, code: String) = coordinator.pair(peer.address, peer.port, code)
    fun pair(address: String, port: Int, code: String) = coordinator.pair(address, port, code)
    fun syncNow(device: PairedDevice) = coordinator.syncNow(device)
    fun forget(device: PairedDevice) = coordinator.forget(device)
    fun setAutoSync(on: Boolean) = launchSafely { container.settings.setAutoSync(on) }

    override fun onCleared() {
        coordinator.cancelCode()
        coordinator.clearStatus()
    }

    companion object {
        val Factory = appViewModelFactory { app, _ -> SyncViewModel(app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit, vm: SyncViewModel = viewModel(factory = SyncViewModel.Factory)) {
    val status by vm.status.collectAsStateWithLifecycle()
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val autoSync by vm.autoSync.collectAsStateWithLifecycle()
    var pairWith by remember { mutableStateOf<SyncPeer?>(null) }
    var manual by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<PairedDevice?>(null) }
    val pairedIds = paired.map { it.id }.toSet()
    val unpaired = nearby.filter { it.id !in pairedIds }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (status != SyncStatus.Idle) StatusCard(status, onDismiss = vm::dismissStatus)

            SectionHeader(stringResource(R.string.sync_paired))
            if (paired.isEmpty()) {
                Hint(stringResource(R.string.sync_none_paired))
            }
            paired.forEach { device ->
                PairedRow(device, isNearby = nearby.any { it.id == device.id }, onSync = { vm.syncNow(device) }, onForget = { forget = device })
            }
            Row(
                Modifier.fillMaxWidth().clickable { vm.setAutoSync(!autoSync) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_auto), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.sync_auto_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = autoSync, onCheckedChange = vm::setAutoSync)
            }

            SectionHeader(stringResource(R.string.sync_add))
            Hint(stringResource(R.string.sync_intro))
            if (status !is SyncStatus.ShowingCode) {
                Button(onClick = vm::showCode, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.sync_show_code))
                }
            }
            unpaired.forEach { peer ->
                Row(
                    Modifier.fillMaxWidth().clickable { pairWith = peer }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(peer.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.sync_connect), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = { manual = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.sync_manual))
            }
        }
    }
    pairWith?.let { peer ->
        CodeDialog(title = peer.name, askAddress = false, onConnect = { _, code -> vm.pair(peer, code) }, onDismiss = { pairWith = null })
    }
    if (manual) {
        CodeDialog(
            title = stringResource(R.string.sync_manual),
            askAddress = true,
            onConnect = { address, code ->
                val host = address.substringBeforeLast(':').trim()
                val port = address.substringAfterLast(':', "").trim().toIntOrNull()
                if (host.isNotEmpty() && port != null) vm.pair(host, port, code)
            },
            onDismiss = { manual = false },
        )
    }
    forget?.let { device ->
        ConfirmDialog(
            title = stringResource(R.string.sync_forget_title, device.name),
            text = stringResource(R.string.sync_forget_body),
            confirmLabel = stringResource(R.string.sync_forget),
            onConfirm = { vm.forget(device) },
            onDismiss = { forget = null },
            destructive = true,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun PairedRow(device: PairedDevice, isNearby: Boolean, onSync: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            val last = device.lastSyncAt?.let { stringResource(R.string.sync_last, Formats.dateTime(context, it)) } ?: stringResource(R.string.sync_never)
            Text(
                if (isNearby) stringResource(R.string.sync_nearby_now) + " · " + last else last,
                style = MaterialTheme.typography.bodySmall,
                color = if (isNearby) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSync) { Text(stringResource(R.string.sync_now)) }
        IconButton(onClick = onForget) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.sync_forget)) }
    }
}

@Composable
private fun StatusCard(status: SyncStatus, onDismiss: () -> Unit) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                SyncStatus.Idle -> Unit
                is SyncStatus.ShowingCode -> {
                    Text(stringResource(R.string.sync_code_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        status.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    status.address?.let {
                        Text("$it:${status.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
                is SyncStatus.Working -> {
                    Text(
                        status.peerName?.let { stringResource(R.string.sync_working_with, it) } ?: stringResource(R.string.sync_working),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is SyncStatus.Done -> {
                    Text(stringResource(R.string.sync_done, status.peerName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (status.changes == 0) stringResource(R.string.sync_no_changes)
                        else pluralStringResource(R.plurals.sync_changes, status.changes, status.changes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
                }
                is SyncStatus.Failed -> {
                    Text(
                        stringResource(
                            when (status.reason) {
                                SyncException.Reason.WRONG_CODE -> R.string.sync_error_wrong_code
                                SyncException.Reason.NOT_PAIRED -> R.string.sync_error_not_paired
                                SyncException.Reason.NOT_FOUND -> R.string.sync_error_not_found
                                SyncException.Reason.INCOMPATIBLE -> R.string.sync_error_version
                                SyncException.Reason.INVALID_DATA -> R.string.sync_error_data
                                SyncException.Reason.NETWORK -> R.string.sync_error_network
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

@Composable
private fun CodeDialog(title: String, askAddress: Boolean, onConnect: (address: String, code: String) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val valid = code.length == 6 && (!askAddress || address.contains(':'))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (askAddress) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.take(40) },
                        label = { Text(stringResource(R.string.sync_address)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text(stringResource(R.string.sync_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(address, code); onDismiss() }, enabled = valid) { Text(stringResource(R.string.sync_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
