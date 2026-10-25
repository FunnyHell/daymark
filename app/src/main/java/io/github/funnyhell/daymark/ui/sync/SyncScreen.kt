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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.funnyhell.daymark.R
import io.github.funnyhell.daymark.data.sync.SyncException
import io.github.funnyhell.daymark.data.sync.SyncProtocol
import io.github.funnyhell.daymark.platform.sync.LocalSyncNetwork
import io.github.funnyhell.daymark.platform.sync.SyncPeer
import io.github.funnyhell.daymark.ui.common.AppCard
import io.github.funnyhell.daymark.ui.common.AppViewModel
import io.github.funnyhell.daymark.ui.common.SectionHeader
import io.github.funnyhell.daymark.ui.common.appViewModelFactory
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data class Hosting(val code: String, val address: String?, val port: Int) : SyncUiState
    data object Working : SyncUiState
    data class Done(val peer: String, val changes: Int) : SyncUiState
    data class Failed(val reason: SyncException.Reason) : SyncUiState
}

class SyncViewModel(application: Application) : AppViewModel(application) {
    private val network = LocalSyncNetwork(application)
    private val deviceName = network.deviceName

    var state by mutableStateOf<SyncUiState>(SyncUiState.Idle)
        private set

    val peers: StateFlow<List<SyncPeer>> = network.peers(deviceName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    /** Shows a code and waits for the other device; a few wrong codes are allowed before it stops. */
    fun host() {
        job?.cancel()
        job = viewModelScope.launch {
            val server = withContext(Dispatchers.IO) { ServerSocket(0).apply { soTimeout = WAIT_MS } }
            // Closing the socket is the only way to stop a blocked accept().
            coroutineContext.job.invokeOnCompletion { runCatching { server.close() } }
            val code = SyncProtocol.newCode()
            val waiting = SyncUiState.Hosting(code, network.localAddress(), server.localPort)
            val announcement = network.advertise(deviceName, server.localPort)
            try {
                state = waiting
                var wrongCodes = 0
                while (true) {
                    val socket = withContext(Dispatchers.IO) { server.accept() }
                    state = SyncUiState.Working
                    try {
                        var changes = 0
                        val peer = SyncProtocol.host(socket, code, deviceName, { container.sync.payload() }) {
                            changes = container.sync.applyRemote(it)
                        }
                        state = SyncUiState.Done(peer, changes)
                        break
                    } catch (e: SyncException) {
                        if (e.reason == SyncException.Reason.WRONG_CODE && ++wrongCodes < MAX_WRONG_CODES) {
                            state = waiting
                        } else {
                            state = SyncUiState.Failed(e.reason)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (state !is SyncUiState.Idle) state = SyncUiState.Failed(SyncException.Reason.NETWORK)
            } finally {
                announcement.close()
                runCatching { server.close() }
            }
        }
    }

    fun join(address: String, port: Int, code: String) {
        job?.cancel()
        job = viewModelScope.launch {
            state = SyncUiState.Working
            state = try {
                var changes = 0
                val peer = SyncProtocol.join(address, port, code.trim(), deviceName, { container.sync.payload() }) {
                    changes = container.sync.applyRemote(it)
                }
                SyncUiState.Done(peer, changes)
            } catch (e: SyncException) {
                SyncUiState.Failed(e.reason)
            } catch (e: Exception) {
                SyncUiState.Failed(SyncException.Reason.NETWORK)
            }
        }
    }

    fun cancel() {
        state = SyncUiState.Idle
        job?.cancel()
        job = null
    }

    companion object {
        private const val WAIT_MS = 10 * 60 * 1000
        private const val MAX_WRONG_CODES = 5
        val Factory = appViewModelFactory { app, _ -> SyncViewModel(app) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit, vm: SyncViewModel = viewModel(factory = SyncViewModel.Factory)) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    var connectTo by remember { mutableStateOf<SyncPeer?>(null) }
    var manual by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.sync_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            StatusCard(vm.state, onHost = vm::host, onCancel = vm::cancel)

            SectionHeader(stringResource(R.string.sync_nearby))
            if (peers.isEmpty()) {
                Text(
                    stringResource(R.string.sync_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            peers.forEach { peer ->
                Row(
                    Modifier.fillMaxWidth().clickable { connectTo = peer }.padding(horizontal = 20.dp, vertical = 14.dp),
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
    connectTo?.let { peer ->
        ConnectDialog(
            title = peer.name,
            askAddress = false,
            onConnect = { _, code -> vm.join(peer.address, peer.port, code) },
            onDismiss = { connectTo = null },
        )
    }
    if (manual) {
        ConnectDialog(
            title = stringResource(R.string.sync_manual),
            askAddress = true,
            onConnect = { address, code ->
                val host = address.substringBeforeLast(':').trim()
                val port = address.substringAfterLast(':', "").trim().toIntOrNull()
                if (host.isNotEmpty() && port != null) vm.join(host, port, code)
            },
            onDismiss = { manual = false },
        )
    }
}

@Composable
private fun StatusCard(state: SyncUiState, onHost: () -> Unit, onCancel: () -> Unit) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                SyncUiState.Idle -> {
                    Text(stringResource(R.string.sync_this_device), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onHost) { Text(stringResource(R.string.sync_show_code)) }
                }
                is SyncUiState.Hosting -> {
                    Text(stringResource(R.string.sync_code_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    state.address?.let {
                        Text("$it:${state.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                SyncUiState.Working -> {
                    Text(stringResource(R.string.sync_working), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is SyncUiState.Done -> {
                    Text(stringResource(R.string.sync_done, state.peer), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.changes == 0) stringResource(R.string.sync_no_changes)
                        else pluralStringResource(R.plurals.sync_changes, state.changes, state.changes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.ok)) }
                }
                is SyncUiState.Failed -> {
                    Text(
                        stringResource(
                            when (state.reason) {
                                SyncException.Reason.WRONG_CODE -> R.string.sync_error_wrong_code
                                SyncException.Reason.INCOMPATIBLE -> R.string.sync_error_version
                                SyncException.Reason.INVALID_DATA -> R.string.sync_error_data
                                SyncException.Reason.NETWORK -> R.string.sync_error_network
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectDialog(title: String, askAddress: Boolean, onConnect: (address: String, code: String) -> Unit, onDismiss: () -> Unit) {
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
