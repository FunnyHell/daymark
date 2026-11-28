package io.github.funnyhell.daymark.modules.outdoors

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Finds public outdoor places around a position in OpenStreetMap through the Overpass API. Only kinds that are
 * outdoors and open to everyone are asked for (parks, squares, viewpoints, monuments, fountains, public art,
 * waterfronts), so homes, shops, clinics and other private or indoor places never become quests. Places marked
 * as private or closed are dropped.
 */
object Overpass {
    const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    fun query(lat: Double, lon: Double, radius: Int): String {
        val around = "(around:$radius,${"%.4f".format(java.util.Locale.ROOT, lat)},${"%.4f".format(java.util.Locale.ROOT, lon)})"
        return """
            [out:json][timeout:25];
            (
              nwr["leisure"~"^(park|garden|nature_reserve)$"]$around;
              nwr["place"="square"]$around;
              nwr["tourism"~"^(viewpoint|artwork)$"]$around;
              nwr["historic"~"^(monument|memorial)$"]$around;
              nwr["amenity"="fountain"]$around;
              nwr["natural"="beach"]$around;
              nwr["leisure"="promenade"]$around;
            );
            out tags bb 200;
        """.trimIndent()
    }

    /** Fetches places; throws on network errors (the caller shows them). Sends only the rounded position. */
    fun fetch(lat: Double, lon: Double, radius: Int): List<Place> {
        val body = "data=" + URLEncoder.encode(query(lat, lon, radius), "UTF-8")
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "Daymark (Android habit app; outdoor quests)")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode != 200) error("Overpass HTTP ${connection.responseCode}")
            return parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Turns an Overpass JSON answer into places; unknown, private or position-less elements are skipped. */
    fun parse(text: String): List<Place> {
        val root = json.parseToJsonElement(text).jsonObject
        val elements = root["elements"]?.jsonArray ?: return emptyList()
        return elements.mapNotNull { e ->
            val o = e.jsonObject
            val tags = o["tags"]?.jsonObject ?: return@mapNotNull null
            fun tag(key: String) = tags[key]?.jsonPrimitive?.contentOrNull
            if (tag("access") in setOf("private", "no", "customers", "permit") || tag("disused") == "yes") return@mapNotNull null
            if (tag("opening_hours") == "closed") return@mapNotNull null
            val kind = kindOf(::tag) ?: return@mapNotNull null
            // Points have a position; areas (ways, relations) have a bounding box, whose middle is used.
            val bounds = (o["bounds"] as? JsonObject)?.let { b ->
                runCatching {
                    Bounds(b["minlat"]!!.jsonPrimitive.double, b["minlon"]!!.jsonPrimitive.double, b["maxlat"]!!.jsonPrimitive.double, b["maxlon"]!!.jsonPrimitive.double)
                }.getOrNull()
            }
            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: bounds?.let { (it.minLat + it.maxLat) / 2 } ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: bounds?.let { (it.minLon + it.maxLon) / 2 } ?: return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "node"
            val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            Place("$type/$id", tag("name:ru") ?: tag("name"), kind, lat, lon, bounds)
        }.distinctBy { it.id }
    }

    private fun kindOf(tag: (String) -> String?): PlaceKind? = when {
        tag("leisure") == "park" -> PlaceKind.PARK
        tag("leisure") == "garden" -> if (tag("garden:type") == "residential" || tag("access") == "private") null else PlaceKind.GARDEN
        tag("leisure") == "nature_reserve" -> PlaceKind.NATURE
        tag("leisure") == "promenade" || tag("natural") == "beach" -> PlaceKind.WATERFRONT
        tag("place") == "square" -> PlaceKind.SQUARE
        tag("tourism") == "viewpoint" -> PlaceKind.VIEWPOINT
        tag("tourism") == "artwork" -> PlaceKind.ARTWORK
        tag("historic") == "monument" || tag("historic") == "memorial" -> PlaceKind.MONUMENT
        tag("amenity") == "fountain" -> PlaceKind.FOUNTAIN
        else -> null
    }
}
