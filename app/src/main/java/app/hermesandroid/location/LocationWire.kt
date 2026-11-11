package app.hermesandroid.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The location channel's wire shapes (location_store.py `_apply`): a raw fix is cut
// down to the precision the user chose before it is sealed; the host enforces the same rules again.
// Pure, so the JVM tests can pin the rounding.

/** A place the user saved. Its coordinates stay on the phone: the host only ever gets id, name, kind, private. */
@Serializable
data class SavedPlace(val id: String, val name: String, val kind: String, val lat: Double, val lon: Double, val radius: Int = 150, val private: Boolean = false)

/** What the phone measured. */
data class RawFix(val lat: Double, val lon: Double, val accuracy: Float, val speed: Float?, val at: Long)

/** The area a geocoder named (all optional). */
data class Area(val name: String?, val locality: String?, val region: String?, val country: String?, val countryCode: String?)

const val PLACES = "places"
const val AREA = "area"
const val PRECISE = "precise"

/** Great-circle metres (the host's own distances are only what the phone sends). */
fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** The saved place the fix is in: within its radius, and only for a fix sharper than 500 m. */
fun placeAt(fix: RawFix, places: List<SavedPlace>): SavedPlace? =
    if (fix.accuracy > 500) null else places.filter { metres(fix.lat, fix.lon, it.lat, it.lon) <= it.radius }.minByOrNull { metres(fix.lat, fix.lon, it.lat, it.lon) }

private fun round(v: Double, decimals: Int): Double { val f = 10.0.pow(decimals); return Math.round(v * f) / f }
private fun roundTo(v: Double, step: Int): Int = ((v / step).roundToInt() * step)

/**
 * The `fix` object at a precision: places → only the place (nothing when outside every place); area → 2 decimals,
 * accuracy ≥ 1 km, distances to 100 m, the area's names, no speed; precise → 6 decimals, distances to 10 m, speed.
 * Inside a private place only its id goes, whatever the precision. `coarseOnly` (approximate permission) caps at area
 * and matches no place.
 */
fun fixWire(id: String, source: String, at: String, fix: RawFix, precision: String, places: List<SavedPlace>, area: Area?, coarseOnly: Boolean = false): JsonObject? {
    val level = if (coarseOnly && precision == PRECISE) AREA else precision
    val place = if (coarseOnly) null else placeAt(fix, places)
    if (level == PLACES && place == null) return null
    return buildJsonObject {
        put("id", id); put("source", source); put("at", at)
        place?.let { put("place_id", it.id) }
        if (level == PLACES || place?.private == true) return@buildJsonObject
        val decimals = if (level == PRECISE) 6 else 2
        put("lat", round(fix.lat, decimals)); put("lon", round(fix.lon, decimals))
        put("accuracy_m", if (level == PRECISE) fix.accuracy.roundToInt() else maxOf(1000, fix.accuracy.roundToInt()))
        if (level == PRECISE) fix.speed?.takeIf { it >= 0 }?.let { put("speed_mps", (it * 10).roundToInt() / 10.0) }
        val visible = places.filterNot { it.private }
        if (visible.isNotEmpty()) put("distances_m", buildJsonObject {
            visible.forEach { p -> put(p.id, roundTo(metres(fix.lat, fix.lon, p.lat, p.lon), if (level == PRECISE) 10 else 100)) }
        })
        area?.let { a ->
            put("area", buildJsonObject {
                a.name?.let { put("name", it.take(80)) }; a.locality?.let { put("locality", it.take(80)) }
                a.region?.let { put("region", it.take(80)) }; a.country?.let { put("country", it.take(80)) }
                a.countryCode?.uppercase()?.takeIf { Regex("^[A-Z]{2}$").matches(it) }?.let { put("country_code", it) }
            })
        }
    }
}

/** `places` as the host takes them: never coordinates; a private place is just "Private place". */
fun placesWire(places: List<SavedPlace>) = kotlinx.serialization.json.buildJsonArray {
    places.take(20).forEach { p ->
        add(buildJsonObject {
            put("id", p.id); put("name", if (p.private) "Private place" else p.name.take(40)); put("kind", if (p.private) "other" else p.kind); put("private", p.private)
        })
    }
}
