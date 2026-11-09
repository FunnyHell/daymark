package io.github.funnyhell.daymark.data.sync

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A device this one has been paired with; [key] is the shared 256-bit sync key. */
data class PairedDevice(val id: String, val name: String, val key: ByteArray, val lastSyncAt: Instant?)

private val Context.pairedStore: DataStore<Preferences> by preferencesDataStore(name = "sync_peers")

/** Devices paired for sync. Stored only on this device (Android backup of app data is disabled). */
class PairedDevices(private val context: Context) {
    @Serializable
    private data class Stored(val id: String, val name: String, val key: String, val lastSyncAt: Long? = null)

    private val listKey = stringPreferencesKey("devices")
    private val json = Json { ignoreUnknownKeys = true }

    val devices: Flow<List<PairedDevice>> = context.pairedStore.data.map { read(it) }

    suspend fun all(): List<PairedDevice> = devices.first()

    suspend fun keyFor(id: String): ByteArray? = all().firstOrNull { it.id == id }?.key

    /** Adds the device or replaces its name and key (pairing again). */
    suspend fun save(id: String, name: String, key: ByteArray) = update { list ->
        list.filterNot { it.id == id } + Stored(id, name, Base64.encodeToString(key, Base64.NO_WRAP), null)
    }

    suspend fun markSynced(id: String, name: String, at: Instant) = update { list ->
        list.map { if (it.id == id) it.copy(name = name, lastSyncAt = at.toEpochMilli()) else it }
    }

    suspend fun forget(id: String) = update { list -> list.filterNot { it.id == id } }

    private suspend fun update(change: (List<Stored>) -> List<Stored>) {
        context.pairedStore.edit { p ->
            val current = p[listKey]?.let { runCatching { json.decodeFromString<List<Stored>>(it) }.getOrNull() }.orEmpty()
            p[listKey] = json.encodeToString(change(current))
        }
    }

    private fun read(p: Preferences): List<PairedDevice> =
        p[listKey]?.let { runCatching { json.decodeFromString<List<Stored>>(it) }.getOrNull() }.orEmpty().map {
            PairedDevice(it.id, it.name, Base64.decode(it.key, Base64.NO_WRAP), it.lastSyncAt?.let(Instant::ofEpochMilli))
        }
}
