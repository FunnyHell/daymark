package io.github.funnyhell.daymark.modules.outdoors

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What kind of public outdoor place a quest goes to; private and indoor places are never offered. */
enum class PlaceKind { PARK, GARDEN, NATURE, SQUARE, VIEWPOINT, ARTWORK, MONUMENT, FOUNTAIN, WATERFRONT }

/** A public outdoor place from OpenStreetMap. [bounds] is the outline's box for areas (a park), null for points. */
data class Place(
    val id: String,
    val name: String?,
    val kind: PlaceKind,
    val lat: Double,
    val lon: Double,
    val bounds: Bounds? = null,
)

data class Bounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

/** How much time the user gives a quest, from the settings. */
data class QuestPrefs(
    val weekdayMinutes: Int = 15,
    val weekendMinutes: Int = 30,
    /** On weekends, places farther away (a trip to the city centre) can be offered too. */
    val weekendTrips: Boolean = false,
)

object QuestRules {
    /** Walking speed used to turn minutes into distance: 4.8 km/h. */
    const val METERS_PER_MINUTE = 80.0

    /** A trip on the weekend may go this many times farther than walking time alone allows. */
    const val TRIP_FACTOR = 4.0

    /** A point place counts as reached within this distance (GPS is often 10–50 m off). */
    const val ARRIVAL_METERS = 120.0

    /** How far out to look for places on [date]: one way of the walking time the user gives. */
    fun radiusMeters(prefs: QuestPrefs, date: LocalDate): Double {
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val minutes = if (weekend) prefs.weekendMinutes else prefs.weekdayMinutes
        // Half of the time to go there, half to come back.
        val walk = minutes / 2.0 * METERS_PER_MINUTE
        return (if (weekend && prefs.weekendTrips) walk * TRIP_FACTOR else walk).coerceIn(200.0, 20_000.0)
    }

    /** Great-circle distance in meters. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /**
     * Whether a position counts as being at [place]: inside the outline of an area (with a margin for GPS error),
     * or within [ARRIVAL_METERS] (plus the reported accuracy, up to 100 m more) of a point.
     */
    fun isAt(place: Place, lat: Double, lon: Double, accuracyMeters: Double): Boolean {
        val slack = ARRIVAL_METERS + min(max(accuracyMeters, 0.0), 100.0)
        val b = place.bounds
        if (b != null) {
            val margin = slack / 111_000.0
            val lonMargin = margin / max(cos(Math.toRadians(lat)), 0.2)
            if (lat in (b.minLat - margin)..(b.maxLat + margin) && lon in (b.minLon - lonMargin)..(b.maxLon + lonMargin)) return true
        }
        return distance(lat, lon, place.lat, place.lon) <= slack
    }

    /**
     * Picks the place for a day: among places within [radius] that were not visited in the last [recentDays] days,
     * one chosen by the date (the same day always gives the same quest), preferring named places. Null when none.
     */
    fun pick(
        places: List<Place>,
        lat: Double,
        lon: Double,
        radius: Double,
        visited: Map<String, LocalDate>,
        date: LocalDate,
        recentDays: Long = 14,
    ): Place? {
        // An area (a large park) counts when its middle is a bit outside the radius: its edge is nearer.
        val near = places.filter { distance(lat, lon, it.lat, it.lon) <= if (it.bounds != null) radius * 1.5 else radius }
        val fresh = near.filter { p -> visited[p.id]?.let { it.isBefore(date.minusDays(recentDays)) } ?: true }
        val pool = (fresh.ifEmpty { near }).let { list -> list.filter { it.name != null }.ifEmpty { list } }.sortedBy { it.id }
        if (pool.isEmpty()) return null
        return pool[Math.floorMod(date.toEpochDay(), pool.size.toLong()).toInt()]
    }

    /** The task text template for a place (index into the quest strings), chosen by date for some variety. */
    fun taskIndex(place: Place, date: LocalDate, count: Int): Int = Math.floorMod(date.toEpochDay() + place.id.hashCode(), count.toLong()).toInt()
}
