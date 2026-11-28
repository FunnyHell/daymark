package io.github.funnyhell.daymark.modules.outdoors

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

enum class QuestStatus { OPEN, DONE, SKIPPED }

/** How a quest was completed: confirmed by location, or marked by the user (GPS trouble, indoors nearby). */
enum class QuestMethod { LOCATION, MANUAL }

data class Quest(
    val date: LocalDate,
    val place: Place,
    val task: Int,
    val status: QuestStatus,
    val method: QuestMethod? = null,
    val doneAtMillis: Long? = null,
    val note: String? = null,
)

data class QuestState(
    /** The user agreed to use location and to look places up online. */
    val consent: Boolean = false,
    val prefs: QuestPrefs = QuestPrefs(),
    val quests: List<Quest> = emptyList(),
    /** Places found last time, with where and when they were looked up, to avoid asking again too often. */
    val cache: List<Place> = emptyList(),
    val cacheLat: Double? = null,
    val cacheLon: Double? = null,
    val cacheRadius: Int = 0,
    val cacheDate: LocalDate? = null,
) {
    fun questOn(date: LocalDate): Quest? = quests.lastOrNull { it.date == date && it.status != QuestStatus.SKIPPED }

    /** Last visit (done quest) of each place. */
    val visited: Map<String, LocalDate>
        get() = quests.filter { it.status != QuestStatus.OPEN }.groupBy { it.place.id }.mapValues { (_, q) -> q.maxOf { it.date } }
}

@Serializable
private data class PlaceRecord(
    val id: String, val name: String? = null, val kind: String, val lat: Double, val lon: Double,
    val minLat: Double? = null, val minLon: Double? = null, val maxLat: Double? = null, val maxLon: Double? = null,
)

@Serializable
private data class QuestRecord(
    val date: String, val place: PlaceRecord, val task: Int, val status: String,
    val method: String? = null, val doneAt: Long? = null, val note: String? = null,
)

private val Context.questStore: DataStore<Preferences> by preferencesDataStore(name = "module_outdoors")

/** The module's own file. Quests are not health records and are not part of backups or sync. */
class QuestStore(private val context: Context) {
    private object Keys {
        val consent = booleanPreferencesKey("consent")
        val weekday = intPreferencesKey("weekday_minutes")
        val weekend = intPreferencesKey("weekend_minutes")
        val trips = booleanPreferencesKey("weekend_trips")
        val quests = stringPreferencesKey("quests_json")
        val cache = stringPreferencesKey("cache_json")
        val cacheAt = stringPreferencesKey("cache_at")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val questsSerializer = ListSerializer(QuestRecord.serializer())
    private val placesSerializer = ListSerializer(PlaceRecord.serializer())

    private fun PlaceRecord.toPlace(): Place? {
        val kind = runCatching { PlaceKind.valueOf(kind) }.getOrNull() ?: return null
        val b = if (minLat != null && minLon != null && maxLat != null && maxLon != null) Bounds(minLat, minLon, maxLat, maxLon) else null
        return Place(id, name, kind, lat, lon, b)
    }

    private fun Place.toRecord() = PlaceRecord(id, name, kind.name, lat, lon, bounds?.minLat, bounds?.minLon, bounds?.maxLat, bounds?.maxLon)

    private fun decodeQuests(text: String?): List<Quest> =
        text?.let { runCatching { json.decodeFromString(questsSerializer, it) }.getOrNull() }.orEmpty().mapNotNull { r ->
            Quest(
                date = runCatching { LocalDate.parse(r.date) }.getOrNull() ?: return@mapNotNull null,
                place = r.place.toPlace() ?: return@mapNotNull null,
                task = r.task,
                status = runCatching { QuestStatus.valueOf(r.status) }.getOrNull() ?: return@mapNotNull null,
                method = r.method?.let { runCatching { QuestMethod.valueOf(it) }.getOrNull() },
                doneAtMillis = r.doneAt,
                note = r.note,
            )
        }

    private fun encodeQuests(quests: List<Quest>): String = json.encodeToString(
        questsSerializer,
        quests.map { QuestRecord(it.date.toString(), it.place.toRecord(), it.task, it.status.name, it.method?.name, it.doneAtMillis, it.note) },
    )

    val state: Flow<QuestState> = context.questStore.data.map { p ->
        val d = QuestPrefs()
        val at = p[Keys.cacheAt]?.split('|')
        QuestState(
            consent = p[Keys.consent] ?: false,
            prefs = QuestPrefs(p[Keys.weekday] ?: d.weekdayMinutes, p[Keys.weekend] ?: d.weekendMinutes, p[Keys.trips] ?: d.weekendTrips),
            quests = decodeQuests(p[Keys.quests]),
            cache = p[Keys.cache]?.let { runCatching { json.decodeFromString(placesSerializer, it) }.getOrNull() }.orEmpty().mapNotNull { it.toPlace() },
            cacheLat = at?.getOrNull(0)?.toDoubleOrNull(),
            cacheLon = at?.getOrNull(1)?.toDoubleOrNull(),
            cacheRadius = at?.getOrNull(2)?.toIntOrNull() ?: 0,
            cacheDate = at?.getOrNull(3)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    suspend fun current(): QuestState = state.first()

    suspend fun setConsent(on: Boolean) = context.questStore.edit { it[Keys.consent] = on }

    suspend fun setPrefs(prefs: QuestPrefs) = context.questStore.edit {
        it[Keys.weekday] = prefs.weekdayMinutes
        it[Keys.weekend] = prefs.weekendMinutes
        it[Keys.trips] = prefs.weekendTrips
    }

    suspend fun setCache(places: List<Place>, lat: Double, lon: Double, radius: Int, date: LocalDate) = context.questStore.edit {
        it[Keys.cache] = json.encodeToString(placesSerializer, places.map { p -> p.toRecord() })
        it[Keys.cacheAt] = "$lat|$lon|$radius|$date"
    }

    /** Applies [change] to the quest list (the newest 365 quests are kept). */
    suspend fun updateQuests(change: (List<Quest>) -> List<Quest>) = context.questStore.edit {
        it[Keys.quests] = encodeQuests(change(decodeQuests(it[Keys.quests])).takeLast(365))
    }
}
