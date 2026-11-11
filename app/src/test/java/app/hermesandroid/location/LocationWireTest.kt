package app.hermesandroid.location

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationWireTest {
    private val home = SavedPlace("p_home", "Home", "home", 51.507400, -0.127800, radius = 150)
    private val secret = SavedPlace("p_x", "Clinic", "other", 51.52, -0.10, radius = 200, private = true)
    private val atHome = RawFix(51.507700, -0.127300, 12f, 1.27f, 0)
    private val away = RawFix(51.5054564, -0.0235590, 8f, 13.4f, 0)

    @Test
    fun distancesAndPlaces() {
        assertEquals(111_195.0, metres(0.0, 0.0, 1.0, 0.0), 5.0)
        assertEquals("p_home", placeAt(atHome, listOf(home, secret))?.id)
        assertNull(placeAt(atHome.copy(accuracy = 800f), listOf(home)))
        assertNull(placeAt(away, listOf(home)))
    }

    @Test
    fun precisionLevels() {
        // places: only the place id, and nothing at all away from every place.
        val p = fixWire("f1", "message", "t", atHome, PLACES, listOf(home), null)!!
        assertEquals(setOf("id", "source", "at", "place_id"), p.keys)
        assertNull(fixWire("f1", "message", "t", away, PLACES, listOf(home), null))
        // area: 2 decimals, ≥1 km, distances to 100 m, no speed.
        val a = fixWire("f2", "app", "t", away, AREA, listOf(home), Area("Centre", "London", "England", "UK", "gb"))!!
        assertEquals(51.51, a["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1000, a["accuracy_m"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, a["distances_m"]!!.jsonObject["p_home"]!!.jsonPrimitive.content.toInt() % 100)
        assertFalse("speed_mps" in a)
        assertEquals("GB", a["area"]!!.jsonObject["country_code"]!!.jsonPrimitive.content)
        // precise: 6 decimals, real accuracy, speed; coarse permission caps it at area and matches no place.
        val x = fixWire("f3", "locate", "t", away, PRECISE, listOf(home), null)!!
        assertEquals(51.505456, x["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(JsonPrimitive(13.4), x["speed_mps"])
        val c = fixWire("f4", "app", "t", atHome, PRECISE, listOf(home), null, coarseOnly = true)!!
        assertFalse("place_id" in c)
        assertEquals(1000, c["accuracy_m"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun privatePlacesStayPrivate() {
        val inSecret = RawFix(51.5201, -0.1001, 10f, null, 0)
        val f = fixWire("f5", "app", "t", inSecret, PRECISE, listOf(home, secret), null)!!
        assertEquals(setOf("id", "source", "at", "place_id"), f.keys)
        val wire = placesWire(listOf(home, secret))
        assertEquals("Private place", wire[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(wire.all { "lat" !in it.jsonObject })
    }
}
