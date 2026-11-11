package app.hermesandroid.alerts

import kotlin.math.ceil

/** A timed calendar occurrence that counts (busy, not declined or canceled). */
data class Ev(val key: String, val title: String, val start: Long, val end: Long, val location: String?)

/** Minutes ahead of the leave time that the "sal en…" notice goes out. */
const val LEAD_MIN = 10

// ponytail: straight line ×1.4 at walking / city-driving speed, no traffic; a routing API if the guesses run short.
private fun road(metres: Double) = metres * 1.4

fun walking(metres: Double) = road(metres) < 1500

/** Minutes to get somewhere `metres` away in a straight line: walking ~80 m/min, else driving ~500 m/min plus 5 to park. */
fun travelMinutes(metres: Double): Int =
    if (walking(metres)) ceil(road(metres) / 80).toInt() else ceil(road(metres) / 500).toInt() + 5

/** When the "sal en $LEAD_MIN min" notice is due for an event `travel` minutes away. */
fun noticeAt(start: Long, travel: Int): Long = start - (travel + LEAD_MIN) * 60_000L

/** Each event with the later ones it overlaps (one notice per event, not per pair: a long trip can cover a whole morning). */
fun conflicts(events: List<Ev>): List<Pair<Ev, List<Ev>>> {
    val s = events.sortedBy { it.start }
    val seen = mutableSetOf<String>()
    return s.mapIndexedNotNull { i, a ->
        if (a.key in seen) return@mapIndexedNotNull null
        val hit = s.drop(i + 1).takeWhile { it.start < a.end }.filter { it.key !in seen }
        if (hit.isEmpty()) null else { seen += hit.map { it.key }; a to hit }
    }
}

/** A location that names a place (not a video call link). */
fun placeLike(location: String?): Boolean {
    val l = location?.trim()?.lowercase() ?: return false
    return l.length >= 3 && listOf("http", "www.", "zoom", "meet.", "teams", "webex").none { it in l }
}
