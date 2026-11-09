package io.github.funnyhell.daymark.platform.sync

import android.content.Context
import android.util.Log
import io.github.funnyhell.daymark.data.repo.SettingsRepository
import io.github.funnyhell.daymark.data.sync.PairedDevice
import io.github.funnyhell.daymark.data.sync.PairedDevices
import io.github.funnyhell.daymark.data.sync.SyncAuth
import io.github.funnyhell.daymark.data.sync.SyncException
import io.github.funnyhell.daymark.data.sync.SyncExchange
import io.github.funnyhell.daymark.data.sync.SyncIdentity
import io.github.funnyhell.daymark.data.sync.SyncProtocol
import io.github.funnyhell.daymark.data.sync.SyncRepository
import io.github.funnyhell.daymark.domain.common.TimeSource
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class ShowingCode(val code: String, val address: String?, val port: Int) : SyncStatus
    data class Working(val peerName: String?) : SyncStatus
    data class Done(val peerName: String, val changes: Int) : SyncStatus
    data class Failed(val reason: SyncException.Reason) : SyncStatus
}

/**
 * Runs device sync on the local network (ADR-033). While Daymark is open (or the sync screen shows a code) it
 * listens for other devices and announces itself; it also looks for paired devices and syncs with them on its
 * own when automatic sync is on. Only one exchange runs at a time. Nothing runs while the app is closed.
 */
class SyncCoordinator(
    context: Context,
    private val settings: SettingsRepository,
    private val sync: SyncRepository,
    val paired: PairedDevices,
    private val time: TimeSource,
    private val scope: CoroutineScope,
) {
    val network = LocalSyncNetwork(context)
    private val exchangeLock = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _nearby = MutableStateFlow<List<SyncPeer>>(emptyList())
    val nearby: StateFlow<List<SyncPeer>> = _nearby.asStateFlow()

    @Volatile private var code: String? = null
    @Volatile private var foreground = false
    private var listener: Listener? = null
    private var discovery: Job? = null
    private val lastAutoSync = mutableMapOf<String, Instant>()

    private class Listener {
        @Volatile var server: ServerSocket? = null
        var job: Job? = null
    }

    private suspend fun me() = SyncIdentity(settings.deviceId(), network.deviceName)

    /** The app came to the foreground: listen, look around, and sync with paired devices that are there. */
    @Synchronized
    fun onForeground() {
        foreground = true
        startListener()
        if (discovery?.isActive != true) {
            discovery = scope.launch {
                network.peers(settings.deviceId()).collect { peers ->
                    _nearby.value = peers
                    autoSync(peers)
                }
            }
        }
    }

    /** The app went to the background: stop everything unless a code is being shown. */
    @Synchronized
    fun onBackground() {
        foreground = false
        if (code == null) stopListening()
    }

    /** Shows a code so a new device can pair; the code is valid until it is used or cancelled. */
    fun showCode() {
        code = SyncProtocol.newCode()
        startListener()
        publishCode()
    }

    fun cancelCode() {
        code = null
        if (_status.value is SyncStatus.ShowingCode) _status.value = SyncStatus.Idle
        if (!foreground) stopListening()
    }

    fun clearStatus() {
        if (_status.value !is SyncStatus.ShowingCode) _status.value = SyncStatus.Idle
    }

    /** Pairs with [address] using the code shown there. */
    fun pair(address: String, port: Int, code: String) = scope.launch { join(address, port, SyncAuth.Code(code.trim()), null) }

    /** Syncs now with a paired device if it is on the network with Daymark open. */
    fun syncNow(device: PairedDevice) = scope.launch {
        val peer = _nearby.value.firstOrNull { it.id == device.id }
        if (peer == null) {
            _status.value = SyncStatus.Failed(SyncException.Reason.NOT_FOUND)
            return@launch
        }
        join(peer.address, peer.port, SyncAuth.Key(device.key), device.name)
    }

    fun forget(device: PairedDevice) = scope.launch { paired.forget(device.id) }

    private suspend fun autoSync(peers: List<SyncPeer>) {
        if (!settings.current().autoSync) return
        val me = settings.deviceId()
        val now = time.now()
        for (device in paired.all()) {
            val peer = peers.firstOrNull { it.id == device.id } ?: continue
            // When both devices are open both see each other; only one of them starts the exchange.
            if (me > device.id) continue
            val last = lastAutoSync[device.id]
            if (last != null && Duration.between(last, now) < AUTO_SYNC_INTERVAL) continue
            lastAutoSync[device.id] = now
            join(peer.address, peer.port, SyncAuth.Key(device.key), device.name)
        }
    }

    private suspend fun join(address: String, port: Int, auth: SyncAuth, name: String?) = exchangeLock.withLock {
        _status.value = SyncStatus.Working(name)
        var changes = 0
        try {
            val result = SyncProtocol.join(address, port, me(), auth, sync::payload) { changes = sync.applyRemote(it) }
            finished(result, changes)
        } catch (e: SyncException) {
            _status.value = SyncStatus.Failed(e.reason)
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            _status.value = SyncStatus.Failed(SyncException.Reason.NETWORK)
        }
    }

    private suspend fun finished(result: SyncExchange, changes: Int) {
        result.newKey?.let { paired.save(result.peerId, result.peerName, it) }
        paired.markSynced(result.peerId, result.peerName, time.now())
        lastAutoSync[result.peerId] = time.now()
        _status.value = SyncStatus.Done(result.peerName, changes)
    }

    @Synchronized
    private fun startListener() {
        if (listener?.job?.isActive == true) return
        val holder = Listener()
        listener = holder
        holder.job = scope.launch(Dispatchers.IO) {
            var announcement: AutoCloseable? = null
            try {
                val server = ServerSocket(0)
                holder.server = server
                announcement = network.advertise(network.deviceName, server.localPort, settings.deviceId())
                if (code != null) publishCode()
                var wrongCodes = 0
                while (isActive) {
                    val socket = server.accept()
                    wrongCodes = handle(socket, wrongCodes)
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "Listener stopped", e)
            } finally {
                announcement?.close()
                runCatching { holder.server?.close() }
            }
        }
    }

    @Synchronized
    private fun stopListening() {
        listener?.let {
            it.job?.cancel()
            // A blocked accept() only returns when its socket is closed.
            runCatching { it.server?.close() }
        }
        listener = null
        discovery?.cancel()
        discovery = null
        _nearby.value = emptyList()
    }

    private fun publishCode() {
        val current = code ?: return
        val port = listener?.server?.localPort ?: return
        _status.value = SyncStatus.ShowingCode(current, network.localAddress(), port)
    }

    /** Handles one incoming connection; returns the updated count of wrong codes. */
    private suspend fun handle(socket: Socket, wrongCodes: Int): Int = exchangeLock.withLock {
        val shownCode = code
        if (shownCode != null) _status.value = SyncStatus.Working(null)
        var changes = 0
        try {
            val result = SyncProtocol.host(socket, me(), shownCode, paired::keyFor, sync::payload) { changes = sync.applyRemote(it) }
            if (result.newKey != null) code = null
            finished(result, changes)
            0
        } catch (e: SyncException) {
            val failures = if (e.reason == SyncException.Reason.WRONG_CODE) wrongCodes + 1 else wrongCodes
            if (shownCode != null && failures < MAX_WRONG_CODES && e.reason == SyncException.Reason.WRONG_CODE) {
                publishCode()
            } else {
                if (failures >= MAX_WRONG_CODES) code = null
                if (shownCode != null) _status.value = SyncStatus.Failed(e.reason)
            }
            failures
        } catch (e: Exception) {
            Log.w(TAG, "Incoming sync failed", e)
            withContext(Dispatchers.IO) { runCatching { socket.close() } }
            wrongCodes
        }
    }

    private companion object {
        const val TAG = "DaymarkSync"
        const val MAX_WRONG_CODES = 5
        val AUTO_SYNC_INTERVAL: Duration = Duration.ofMinutes(2)
    }
}
