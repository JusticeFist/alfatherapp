package app.hermesandroid.health

import app.hermesandroid.phone.iso
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The health channel's wire shapes (health_store.py METRICS / `_window`): per metric one exact unit (a wrong one
// fails the whole batch), "sum" buckets carry `sum`, "stats" buckets `avg` (+ min/max/count), sleep is one
// bucket per night named by its wake-up date. Pure, so the JVM tests pin it.

enum class Agg { SUM, STATS }

/** A metric this phone can fill from Health Connect: host name, category, unit, aggregation. */
data class Metric(val name: String, val category: String, val unit: String, val agg: Agg)

val METRICS = listOf(
    Metric("steps", "activity", "count", Agg.SUM),
    // Health Connect doesn't split walking from cycling: all distance counts as walking/running.
    Metric("distance_walking_running", "activity", "m", Agg.SUM),
    Metric("active_energy", "activity", "kcal", Agg.SUM),
    Metric("exercise_time", "activity", "min", Agg.SUM),
    Metric("flights_climbed", "activity", "count", Agg.SUM),
    Metric("heart_rate", "heart", "count/min", Agg.STATS),
    Metric("resting_heart_rate", "heart", "count/min", Agg.STATS),
    // Health Connect stores RMSSD where Apple has SDNN; both are milliseconds of variability.
    Metric("heart_rate_variability", "heart", "ms", Agg.STATS),
    Metric("oxygen_saturation", "vitals", "%", Agg.STATS),
    Metric("respiratory_rate", "vitals", "count/min", Agg.STATS),
    Metric("blood_pressure_systolic", "vitals", "mmHg", Agg.STATS),
    Metric("blood_pressure_diastolic", "vitals", "mmHg", Agg.STATS),
    Metric("body_mass", "body", "kg", Agg.STATS),
    Metric("body_fat_percentage", "body", "%", Agg.STATS),
    Metric("height", "body", "m", Agg.STATS),
    Metric("vo2_max", "fitness", "mL/(kg*min)", Agg.STATS),
)
val CATEGORIES = listOf("activity", "heart", "sleep", "workouts", "body", "vitals", "fitness")

/** One bucket's numbers: a sum, or avg (min/max/count when known). */
data class Bucket(val start: Long, val end: Long, val sum: Double? = null, val avg: Double? = null, val min: Double? = null, val max: Double? = null, val count: Int? = null)

private fun r(v: Double) = Math.round(v * 100) / 100.0

/** A window: every bucket in [from, to) replaces the host's buckets of that metric and grain there. */
fun window(m: Metric, grain: String, from: Long, to: Long, buckets: List<Bucket>, zone: ZoneId = ZoneId.systemDefault()): JsonObject = buildJsonObject {
    put("metric", m.name); put("grain", grain); put("unit", m.unit); put("from", iso(from, zone)); put("to", iso(to, zone))
    put("buckets", buildJsonArray {
        buckets.filter { it.start in from until to && it.end > it.start }.forEach { b ->
            if (m.agg == Agg.SUM && b.sum == null || m.agg == Agg.STATS && b.avg == null) return@forEach
            add(buildJsonObject {
                put("start", iso(b.start, zone)); put("end", iso(b.end, zone))
                if (m.agg == Agg.SUM) put("sum", r(b.sum!!)) else {
                    put("avg", r(b.avg!!)); b.min?.let { put("min", r(it)) }; b.max?.let { put("max", r(it)) }; b.count?.let { put("count", it) }
                }
            })
        }
    })
}

/** A sleep stage from Health Connect, already named the host's way. */
data class Stage(val start: Long, val end: Long, val stage: String)

/** One night (a sleep session): its span and stages, and where it came from when it isn't Health Connect. */
data class Night(val start: Long, val end: Long, val stages: List<Stage>, val source: String? = null)

/** Health Connect stage type → host stage (light sleep is Apple's "core"). */
fun stageName(type: Int): String = when (type) {
    1, 3, 7 -> "awake"      // awake, out of bed, awake in bed
    2 -> "asleep"            // sleeping (no detail)
    4 -> "core"              // light
    5 -> "deep"
    6 -> "rem"
    else -> "asleep"
}

/** The night bucket: minutes per stage, wake-ups between sleep, bedtime and wake time; dated by the wake-up day. */
fun nightBucket(n: Night, zone: ZoneId = ZoneId.systemDefault()): JsonObject = buildJsonObject {
    put("start", iso(n.start, zone)); put("end", iso(n.end, zone))
    put("date", Instant.ofEpochMilli(n.end).atZone(zone).toLocalDate().toString())
    n.source?.let { put("source", it.take(120)) }
    put("data", buildJsonObject {
        fun minutes(vararg names: String) = n.stages.filter { it.stage in names }.sumOf { it.end - it.start } / 60_000.0
        val asleep = if (n.stages.isEmpty()) (n.end - n.start) / 60_000.0 else minutes("asleep", "core", "deep", "rem")
        put("asleep", r(asleep)); put("in_bed", r((n.end - n.start) / 60_000.0))
        listOf("core", "deep", "rem", "awake").forEach { s -> minutes(s).takeIf { it > 0 }?.let { put(s, r(it)) } }
        // A wake-up is an awake stage with sleep on both sides.
        val sorted = n.stages.sortedBy { it.start }
        put("wake_count", sorted.indices.count { i -> sorted[i].stage == "awake" && i > 0 && i < sorted.lastIndex })
        put("bedtime", iso(n.start, zone)); put("wake_time", iso(n.end, zone))
    })
}

/** Nights run noon to noon: the window for the nights that woke up on these days. */
fun nightWindow(nights: List<Night>, firstDay: LocalDate, lastDay: LocalDate, zone: ZoneId = ZoneId.systemDefault()): JsonObject {
    val from = firstDay.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    val to = lastDay.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    return buildJsonObject {
        put("metric", "sleep"); put("grain", "night"); put("unit", "min"); put("from", iso(from, zone)); put("to", iso(to, zone))
        put("buckets", JsonArray(nights.filter { it.start in from until to }.map { nightBucket(it, zone) }))
    }
}
