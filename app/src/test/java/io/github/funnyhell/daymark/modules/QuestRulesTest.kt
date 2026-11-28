package io.github.funnyhell.daymark.modules

import io.github.funnyhell.daymark.modules.outdoors.Bounds
import io.github.funnyhell.daymark.modules.outdoors.Overpass
import io.github.funnyhell.daymark.modules.outdoors.Place
import io.github.funnyhell.daymark.modules.outdoors.PlaceKind
import io.github.funnyhell.daymark.modules.outdoors.QuestPrefs
import io.github.funnyhell.daymark.modules.outdoors.QuestRules
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestRulesTest {
    /** 2024-03-04 is a Monday. */
    private val monday = LocalDate.of(2024, 3, 4)
    private val saturday = monday.plusDays(5)

    @Test
    fun radiusFollowsWalkingTimeAndWeekendTrips() {
        val prefs = QuestPrefs(weekdayMinutes = 20, weekendMinutes = 60, weekendTrips = false)
        assertEquals(800.0, QuestRules.radiusMeters(prefs, monday), 0.1)
        assertEquals(2400.0, QuestRules.radiusMeters(prefs, saturday), 0.1)
        assertEquals(9600.0, QuestRules.radiusMeters(prefs.copy(weekendTrips = true), saturday), 0.1)
    }

    @Test
    fun arrivalCountsInsideAnAreaOrNearAPoint() {
        val park = Place("way/1", "Park", PlaceKind.PARK, 55.75, 37.62, Bounds(55.745, 37.61, 55.755, 37.63))
        // Inside the park but 500 m from its middle.
        assertTrue(QuestRules.isAt(park, 55.7505, 37.6285, accuracyMeters = 20.0))
        assertFalse(QuestRules.isAt(park, 55.77, 37.62, accuracyMeters = 20.0))
        val fountain = Place("node/2", null, PlaceKind.FOUNTAIN, 55.75, 37.62)
        assertTrue(QuestRules.isAt(fountain, 55.7508, 37.62, accuracyMeters = 10.0))
        assertFalse(QuestRules.isAt(fountain, 55.753, 37.62, accuracyMeters = 10.0))
    }

    @Test
    fun pickPrefersNearbyNamedPlacesNotVisitedRecentlyAndIsStableForADay() {
        val here = 55.75 to 37.62
        val near = Place("node/1", "Square", PlaceKind.SQUARE, 55.751, 37.62)
        val unnamed = Place("node/2", null, PlaceKind.FOUNTAIN, 55.752, 37.62)
        val visited = Place("node/3", "Old", PlaceKind.MONUMENT, 55.749, 37.62)
        val far = Place("node/4", "Far", PlaceKind.PARK, 55.80, 37.62)
        val places = listOf(near, unnamed, visited, far)
        val picked = QuestRules.pick(places, here.first, here.second, 800.0, mapOf("node/3" to monday.minusDays(2)), monday)
        assertEquals(near, picked)
        assertEquals(picked, QuestRules.pick(places.reversed(), here.first, here.second, 800.0, mapOf("node/3" to monday.minusDays(2)), monday))
        assertNull(QuestRules.pick(listOf(far), here.first, here.second, 800.0, emptyMap(), monday))
    }

    @Test
    fun overpassAnswerKeepsOnlyPublicOutdoorPlaces() {
        val json = """
            {"elements":[
              {"type":"way","id":10,"bounds":{"minlat":55.1,"minlon":37.1,"maxlat":55.3,"maxlon":37.3},"tags":{"leisure":"park","name":"Central"}},
              {"type":"node","id":11,"lat":55.2,"lon":37.2,"tags":{"amenity":"fountain"}},
              {"type":"way","id":12,"bounds":{"minlat":55.1,"minlon":37.1,"maxlat":55.2,"maxlon":37.2},"tags":{"leisure":"garden","access":"private"}},
              {"type":"node","id":13,"lat":55.2,"lon":37.2,"tags":{"amenity":"clinic","name":"Clinic"}},
              {"type":"node","id":14,"lat":55.2,"lon":37.2,"tags":{"tourism":"viewpoint","name":"View","name:ru":"Вид"}}
            ]}
        """.trimIndent()
        val places = Overpass.parse(json, "ru")
        assertEquals(listOf("way/10", "node/11", "node/14"), places.map { it.id })
        assertEquals(55.2, places[0].lat, 1e-9)
        assertEquals(PlaceKind.PARK, places[0].kind)
        assertEquals("Вид", places[2].name)
        assertEquals("View", Overpass.parse(json, "pl")[2].name)
        assertEquals("Central", Overpass.parse(json, "ru")[0].name)
    }

    /** Real request to the public servers; runs only when OVERPASS_LIVE is set (a CI step), never in normal builds. */
    @Test
    fun liveSearchFindsPlacesInSeveralCountries() {
        if (System.getenv("OVERPASS_LIVE").isNullOrBlank()) return
        val cities = mapOf(
            "Warsaw" to (52.2297 to 21.0122), "Helsinki" to (60.1699 to 24.9384), "Berlin" to (52.5200 to 13.4050),
            "Moscow" to (55.7558 to 37.6173), "Kyiv" to (50.4501 to 30.5234),
        )
        for ((city, at) in cities) {
            val places = try {
                Overpass.fetch(at.first, at.second, 1500, "en")
            } catch (e: java.io.IOException) {
                throw AssertionError("$city: ${e.message}", e)
            }
            println("$city: ${places.size} places, e.g. ${places.take(3).map { "${it.kind} ${it.name}" }}")
            assertTrue("$city: nothing found", places.isNotEmpty())
        }
    }
}
