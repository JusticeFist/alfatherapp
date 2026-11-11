package app.hermesandroid.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {
    private fun ev(k: String, s: Int, e: Int) = Ev(k, k, s * 60_000L, e * 60_000L, null)

    @Test fun overlapsOnly() {
        val groups = conflicts(listOf(ev("c", 90, 120), ev("a", 0, 60), ev("b", 30, 45), ev("e", 40, 50), ev("d", 60, 90)))
        assertEquals(listOf("a" to listOf("b", "e")), groups.map { (a, hit) -> a.key to hit.map { it.key } })
    }

    @Test fun travel() {
        assertEquals(9, travelMinutes(500.0)) // 700 m on foot
        assertEquals(33, travelMinutes(10_000.0)) // 14 km by car + parking
        assertEquals(60 - 33 - LEAD_MIN, (noticeAt(60 * 60_000L, 33) / 60_000L).toInt())
    }

    @Test fun places() {
        assertTrue(placeLike("Music School, Springfield"))
        assertFalse(placeLike("https://meet.google.com/abc"))
        assertFalse(placeLike(null))
    }
}
