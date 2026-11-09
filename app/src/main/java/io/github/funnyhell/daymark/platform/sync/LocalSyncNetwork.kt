package io.github.funnyhell.daymark.platform.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Another device offering sync on the local network; [id] is its stable device id (null if it did not say). */
data class SyncPeer(val name: String, val address: String, val port: Int, val id: String?)

/**
 * Finds and announces devices on the same Wi-Fi with DNS-SD (Android's NsdManager). Discovery only helps to
 * fill in the address; the code shown on the other device is what authorizes the exchange.
 */
class LocalSyncNetwork(private val context: Context) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)

    /** A readable name for this device, e.g. "Galaxy A52". */
    val deviceName: String
        get() = Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** This device's IPv4 address on the current network, for typing it on the other device. */
    fun localAddress(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val properties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
        return properties.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Announces this device until the returned handle is closed. */
    fun advertise(name: String, port: Int, deviceId: String): AutoCloseable {
        val manager = nsd ?: return AutoCloseable { }
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ATTRIBUTE_ID, deviceId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Announcing failed: $errorCode")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return AutoCloseable { runCatching { manager.unregisterService(listener) } }
    }

    /** Devices currently offering sync (not this one), updated as they appear and disappear. */
    fun peers(ownId: String): Flow<List<SyncPeer>> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val found = LinkedHashMap<String, SyncPeer>()
        val resolver = Resolver(manager) { peer ->
            if (peer.id == ownId) return@Resolver
            synchronized(found) {
                found[peer.name] = peer
                trySend(found.values.toList())
            }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) {
                    found.remove(info.serviceName)
                    trySend(found.values.toList())
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        trySend(emptyList())
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        awaitClose {
            runCatching { manager.stopServiceDiscovery(discovery) }
            resolver.close()
        }
    }

    /**
     * Resolves found services to an address. Android 14+ has a callback API that can run several at once; on
     * older versions `resolveService` handles one request at a time, so requests are queued.
     */
    private class Resolver(private val manager: NsdManager, private val onResolved: (SyncPeer) -> Unit) : AutoCloseable {
        // Runs callbacks on NsdManager's own thread. NsdManager may still deliver a callback after it was
        // unregistered; an executor that could be shut down would then reject it and crash the process.
        private val executor = Executor { it.run() }
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var busy = false
        private val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()

        fun resolve(info: NsdServiceInfo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val address = info.hostAddresses.firstOrNull { it is Inet4Address } ?: info.hostAddresses.firstOrNull()
                        if (address != null) onResolved(SyncPeer(info.serviceName, address.hostAddress.orEmpty(), info.port, idOf(info)))
                    }
                    override fun onServiceLost() = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                synchronized(callbacks) { callbacks += callback }
                runCatching { manager.registerServiceInfoCallback(info, executor, callback) }
            } else {
                synchronized(queue) {
                    queue.addLast(info)
                    if (!busy) next()
                }
            }
        }

        private fun next() {
            val info = queue.removeFirstOrNull()
            busy = info != null
            if (info != null) resolveLegacy(info)
        }

        @Suppress("DEPRECATION") // resolveService is the only resolver API before Android 14.
        private fun resolveLegacy(info: NsdServiceInfo) {
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host: InetAddress? = info.host
                        if (host != null) onResolved(SyncPeer(info.serviceName, host.hostAddress.orEmpty(), info.port, idOf(info)))
                        synchronized(queue) { next() }
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(queue) { next() }
                    }
                },
            )
        }

        override fun close() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                synchronized(callbacks) {
                    callbacks.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
                    callbacks.clear()
                }
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_daymark-sync._tcp"
        private const val ATTRIBUTE_ID = "id"

        private fun idOf(info: NsdServiceInfo): String? = info.attributes[ATTRIBUTE_ID]?.toString(Charsets.UTF_8)
        private const val TAG = "DaymarkSync"
    }
}
