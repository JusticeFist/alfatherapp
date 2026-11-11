package app.hermesandroid.phone

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneWireTest {
    private val zone = ZoneId.of("-06:00")

    @Test
    fun timesCarryTheOffset() {
        // 2026-10-22 15:15:00 UTC, whole minute: the seconds must still be written.
        assertEquals("2026-10-22T09:15:00-06:00", iso(1792682100000L, zone))
        // An all-day event stored as UTC midnight stays on its own date.
        assertEquals("2026-10-23T00:00:00-06:00", isoAllDay(1792713600000L, zone))
    }

    @Test
    fun idsAndValues() {
        val a = wireId("e_", 42, 1792682100000L)
        assertEquals(24, a.length)
        assertEquals(a, wireId("e_", 42, 1792682100000L))
        assertNotEquals(a, wireId("e_", 42, 1792682100001L))
        assertEquals("#3F51B5", hexColor(0xFF3F51B5.toInt()))
        assertEquals("busy", availability(0))
        assertEquals("accepted", myStatus(1))
        assertEquals(0.85f, batteryStep(0.83f))
        assertEquals(0.13f, batteryStep(0.13f))
    }
}
