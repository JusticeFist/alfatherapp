package app.hermesandroid.health

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HealthWireTest {
    private val z = ZoneId.of("-06:00")
    private fun at(day: String, h: Int, m: Int = 0) = LocalDate.parse(day).atTime(h, m).atZone(z).toInstant().toEpochMilli()

    /** The host's own table (health_store.py METRICS): name → unit. A mismatch fails a whole batch. */
    private val hostUnits = mapOf(
        "steps" to "count", "distance_walking_running" to "m", "active_energy" to "kcal", "exercise_time" to "min", "flights_climbed" to "count",
        "heart_rate" to "count/min", "resting_heart_rate" to "count/min", "heart_rate_variability" to "ms", "oxygen_saturation" to "%",
        "respiratory_rate" to "count/min", "blood_pressure_systolic" to "mmHg", "blood_pressure_diastolic" to "mmHg",
        "body_mass" to "kg", "body_fat_percentage" to "%", "height" to "m", "vo2_max" to "mL/(kg*min)",
    )

    @Test
    fun unitsMatchTheHost() {
        METRICS.forEach { assertEquals(it.name, hostUnits[it.name], it.unit) }
    }

    @Test
    fun windowsKeepOnlyTheirBuckets() {
        val steps = METRICS.first { it.name == "steps" }
        val w = window(steps, "day", at("2026-10-21", 0), at("2026-10-23", 0), listOf(
            Bucket(at("2026-10-21", 0), at("2026-10-22", 0), sum = 8123.0),
            Bucket(at("2026-10-22", 0), at("2026-10-23", 0), sum = 4210.4),
            Bucket(at("2026-10-23", 0), at("2026-10-24", 0), sum = 10.0), // outside [from, to)
        ), z)
        assertEquals("2026-10-21T00:00:00-06:00", w["from"]!!.jsonPrimitive.content)
        val b = w["buckets"]!!.jsonArray
        assertEquals(2, b.size)
        assertEquals(4210.4, b[1].jsonObject["sum"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val hr = window(METRICS.first { it.name == "heart_rate" }, "hour", at("2026-10-22", 9), at("2026-10-22", 10),
            listOf(Bucket(at("2026-10-22", 9), at("2026-10-22", 10), avg = 72.456, min = 60.0, max = 101.0, count = 40)), z)
        val hb = hr["buckets"]!!.jsonArray[0].jsonObject
        assertEquals(72.46, hb["avg"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertFalse("sum" in hb)
    }

    @Test
    fun phoneNightsMerge() {
        val h = 3_600_000L
        // 23:00–03:00 and 03:40–07:00 (got up at 3) are one night; a nap at 15:00 stays apart; repeats collapse.
        val m = PhoneSleep.merge(listOf(PhoneNight(0, 4 * h), PhoneNight(4 * h + 40 * 60_000, 8 * h), PhoneNight(16 * h, 17 * h), PhoneNight(0, 4 * h)))
        assertEquals(listOf(PhoneNight(0, 8 * h), PhoneNight(16 * h, 17 * h)), m)
        val b = nightBucket(Night(0, 8 * h, emptyList(), PhoneSleep.SOURCE), z)
        assertEquals(PhoneSleep.SOURCE, b["source"]!!.jsonPrimitive.content)
        assertEquals(480.0, b["data"]!!.jsonObject["asleep"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun aNight() {
        val n = Night(at("2026-10-21", 23, 40), at("2026-10-22", 7, 5), listOf(
            Stage(at("2026-10-21", 23, 40), at("2026-10-22", 1, 0), "core"),
            Stage(at("2026-10-22", 1, 0), at("2026-10-22", 1, 15), "awake"),
            Stage(at("2026-10-22", 1, 15), at("2026-10-22", 3, 0), "deep"),
            Stage(at("2026-10-22", 3, 0), at("2026-10-22", 7, 5), "rem"),
        ))
        val w = nightWindow(listOf(n), LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-22"), z)
        assertEquals("2026-10-21T12:00:00-06:00", w["from"]!!.jsonPrimitive.content)
        val b = w["buckets"]!!.jsonArray[0].jsonObject
        assertEquals("2026-10-22", b["date"]!!.jsonPrimitive.content)
        val d = b["data"]!!.jsonObject
        assertEquals(445.0, d["in_bed"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(430.0, d["asleep"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(1, d["wake_count"]!!.jsonPrimitive.content.toInt())
        assertEquals("core", stageName(4))
    }
}
