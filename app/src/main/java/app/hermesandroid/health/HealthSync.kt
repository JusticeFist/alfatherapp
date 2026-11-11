package app.hermesandroid.health

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.phone.digest
import app.hermesandroid.phone.iso
import app.hermesandroid.share.KeyLost
import app.hermesandroid.share.ShareChannel
import app.hermesandroid.share.ShareLink
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// Health sharing from Health Connect, where the watch, Fitbit, Samsung Health… write:
// day windows per metric (hour windows for the busy ones), one bucket per night of sleep, and workouts as events,
// sealed to `/health/ingest`. A pass recomputes the last 3 days; the first one goes back `historyDays`.

@Serializable
data class HealthPrefs(
    val enabled: Boolean = false,
    val categories: Set<String> = setOf("activity", "heart", "sleep", "workouts"),
    val historyDays: Int = 30,
    /** Nights estimated by the phone (Sleep API) where Health Connect has none: for a user without a watch at night. */
    val phoneSleep: Boolean = false,
)

/** What each category reads from Health Connect. */
val CATEGORY_RECORDS: Map<String, List<KClass<out Record>>> = mapOf(
    "activity" to listOf(StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, FloorsClimbedRecord::class, ExerciseSessionRecord::class),
    "heart" to listOf(HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class),
    "sleep" to listOf(SleepSessionRecord::class),
    "workouts" to listOf(ExerciseSessionRecord::class),
    "body" to listOf(WeightRecord::class, BodyFatRecord::class, HeightRecord::class),
    "vitals" to listOf(OxygenSaturationRecord::class, RespiratoryRateRecord::class, BloodPressureRecord::class),
    "fitness" to listOf(Vo2MaxRecord::class),
)

/** Reading with the app closed (PhoneSyncJob's hourly pass). */
const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

fun permissionsFor(categories: Collection<String>): Set<String> =
    categories.flatMap { CATEGORY_RECORDS[it].orEmpty() }.map { HealthPermission.getReadPermission(it) }.toSet()

class HealthSync(private val graph: AppGraph, private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val link = ShareLink(graph, ShareChannel.Health)
    private val lock = Mutex()

    var prefs by mutableStateOf(runCatching { json.decodeFromString<HealthPrefs>(graph.store.share("health-prefs")) }.getOrDefault(HealthPrefs()))
        private set
    var lastSync by mutableStateOf<Long?>(null)
        private set
    var rows by mutableStateOf<JsonObject?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    private var lastPassAt = 0L

    val available get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    /** Health Connect answers a closed app only with this permission (asked for with the categories). */
    suspend fun backgroundAllowed() = BACKGROUND in granted()

    suspend fun granted(): Set<String> = if (available) runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet()) else emptySet()

    fun choose(next: HealthPrefs) {
        val wasOn = prefs.enabled
        prefs = next
        graph.store.setShare("health-prefs", json.encodeToString(HealthPrefs.serializer(), next))
        if (next.enabled && next.phoneSleep && "sleep" in next.categories) PhoneSleep.start(context) else PhoneSleep.stop(context)
        if (next.enabled) sync("changed", force = true)
        else if (wasOn) graph.scope.launch { runCatching { lock.withLock { link.wipe() } }.onFailure { error = tr(R.string.healthsync_wipe_failed, it.message.orEmpty()) } }
        app.hermesandroid.phone.PhoneJobs.schedule(context)
    }

    /** A pass; the frequent triggers (foreground, connected) are spaced 3 min. */
    /** Returns the pass, or null when skipped. */
    fun sync(reason: String, force: Boolean = false): kotlinx.coroutines.Job? {
        if (!prefs.enabled || !graph.signedIn || !available) return null
        val now = System.currentTimeMillis()
        if (!force && now - lastPassAt < 180_000) return null
        lastPassAt = now
        return graph.scope.launch {
            lock.withLock {
                busy = true
                try {
                    try { pass(reason, all = force) } catch (e: KeyLost) { pass(reason, all = true) }
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    android.util.Log.w("HealthSync", "pass $reason failed: $error")
                } finally { busy = false }
            }
        }
    }

    private suspend fun pass(reason: String, all: Boolean) {
        val status = link.status()
        val resend = link.ready(status)
        val st = link.state
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val have = granted()
        val phoneNights = prefs.phoneSleep && "sleep" in prefs.categories && PhoneSleep.permitted(context)
        val cats = prefs.categories.filter { c -> CATEGORY_RECORDS[c].orEmpty().any { HealthPermission.getReadPermission(it) in have } || (c == "sleep" && phoneNights) }
        // History goes back `historyDays` on a new key/store and whenever the readable categories (or the depth)
        // change — a pass before the permissions were granted backfills nothing; later ones redo the last 3 days.
        val backfill = digest(cats.sorted().toString() + prefs.historyDays)
        val days = if (resend || st.sent["backfill"] != backfill) prefs.historyDays else 3
        val today = LocalDate.now(zone)
        val from = today.minusDays(days - 1L).atStartOfDay(zone).toInstant()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant()
        val hourFrom = today.minusDays(1).atStartOfDay(zone).toInstant()

        val windows = mutableListOf<JsonObject>()
        fun can(k: KClass<out Record>) = HealthPermission.getReadPermission(k) in have
        for (m in METRICS.filter { it.category in cats }) {
            runCatching { windows += window(m, "day", from.toEpochMilli(), to.toEpochMilli(), buckets(m, from, to, Duration.ofDays(1), ::can), zone) }
                .onFailure { android.util.Log.w("HealthSync", "${m.name}: ${it.message}") }
            if (m.name in HOURLY) runCatching { windows += window(m, "hour", hourFrom.toEpochMilli(), to.toEpochMilli(), buckets(m, hourFrom, to, Duration.ofHours(1), ::can), zone) }
        }
        if ("sleep" in cats) runCatching {
            val watched = if (can(SleepSessionRecord::class)) read(SleepSessionRecord::class, from.minus(Duration.ofHours(12)), to).map { s ->
                Night(s.startTime.toEpochMilli(), s.endTime.toEpochMilli(), s.stages.map { Stage(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), stageName(it.stage)) })
            } else emptyList()
            // A night the watch didn't record comes from the phone's estimate (by wake-up day).
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val estimated = if (phoneNights) PhoneSleep.nights(graph.store).filter { p -> watched.none { day(it.end) == day(p.end) } }
                .map { Night(it.start, it.end, emptyList(), PhoneSleep.SOURCE) } else emptyList()
            val nights = watched + estimated
            android.util.Log.i("HealthSync", "sleep: ${watched.size} from Health Connect, ${estimated.size} estimated by the phone")
            windows += nightWindow(nights, today.minusDays(days - 1L), today.plusDays(1), zone)
        }
        val events = if ("workouts" in cats && can(ExerciseSessionRecord::class)) runCatching { workouts(from, to) }.getOrDefault(emptyList()) else emptyList()

        // Only windows whose content changed, unless asked for everything (or every 6 h).
        val full = all || resend || now - st.lastFullAt > 6 * 3600_000L
        val sent = st.sent.toMutableMap()
        val changed = windows.filter { w ->
            val key = "w:${w["metric"]}:${w["grain"]}:${w["from"]}"
            val h = digest(w["buckets"].toString())
            (full || sent[key] != h).also { sent[key] = h }
        }
        val newEvents = events.filter { e -> val k = "e:${e["id"]}"; val h = digest(e.toString()); (full || sent[k] != h).also { sent[k] = h } }
        if (changed.isEmpty() && newEvents.isEmpty() && now - st.lastSendAt < 15 * 60_000L) return
        val body = buildJsonObject {
            put("batch_id", ShareLink.batchId()); put("computed_at", iso(now))
            putJsonObject("device") { put("name", ShareLink.deviceName()); put("time_zone", zone.id) }
            putJsonArray("categories") { cats.forEach { add(it) } }
            putJsonArray("detailed") { }
            put("windows", JsonArray(changed.take(200)))
            put("events", JsonArray(newEvents.take(1000)))
        }
        val r = link.ingest(body)
        // Remember only what this pass computed: window keys move with the days anyway.
        sent["backfill"] = backfill
        val keys = windows.map { w -> "w:${w["metric"]}:${w["grain"]}:${w["from"]}" }.toSet() + events.map { e -> "e:${e["id"]}" } + "backfill"
        link.update { it.copy(sent = sent.filterKeys { k -> k in keys }, lastSendAt = now, lastFullAt = if (full || it.lastFullAt == 0L) now else it.lastFullAt) }
        lastSync = now
        runCatching { link.status() }.getOrNull()?.get("device").obj()?.let { rows = it["rows"].obj() }
        android.util.Log.i("HealthSync", "pass $reason days=$days windows=${changed.size} events=${newEvents.size} -> ${r?.toString()?.take(200)}")
    }

    // ---- Health Connect ----

    private suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageSize = 1000, pageToken = token))
            out += r.records; token = r.pageToken
        } while (token != null && out.size < 20_000)
        return out
    }

    /** One metric bucketed by `slice` over [from, to): Health Connect aggregates where it has them, else the samples. */
    private suspend fun buckets(m: Metric, from: Instant, to: Instant, slice: Duration, can: (KClass<out Record>) -> Boolean): List<Bucket> {
        suspend fun agg(type: KClass<out Record>, vararg metrics: AggregateMetric<*>, f: (AggregationResult, Long, Long) -> Bucket?): List<Bucket> {
            if (!can(type)) return emptyList()
            return client.aggregateGroupByDuration(AggregateGroupByDurationRequest(metrics.toSet(), TimeRangeFilter.between(from, to), slice))
                .mapNotNull { g -> f(g.result, g.startTime.toEpochMilli(), g.endTime.toEpochMilli()) }
        }
        fun <T : Record> samples(list: List<T>, time: (T) -> Instant, value: (T) -> Double): List<Bucket> =
            list.groupBy { (time(it).toEpochMilli() - from.toEpochMilli()) / slice.toMillis() }.map { (i, rs) ->
                val v = rs.map(value)
                val s = from.toEpochMilli() + i * slice.toMillis()
                Bucket(s, s + slice.toMillis(), avg = v.average(), min = v.min(), max = v.max(), count = v.size)
            }
        return when (m.name) {
            "steps" -> agg(StepsRecord::class, StepsRecord.COUNT_TOTAL) { r, s, e -> r[StepsRecord.COUNT_TOTAL]?.let { Bucket(s, e, sum = it.toDouble()) } }
            "distance_walking_running" -> agg(DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL) { r, s, e -> r[DistanceRecord.DISTANCE_TOTAL]?.let { Bucket(s, e, sum = it.inMeters) } }
            "active_energy" -> agg(ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) { r, s, e -> r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { Bucket(s, e, sum = it.inKilocalories) } }
            "flights_climbed" -> agg(FloorsClimbedRecord::class, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) { r, s, e -> r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.let { Bucket(s, e, sum = it) } }
            "exercise_time" -> agg(ExerciseSessionRecord::class, ExerciseSessionRecord.EXERCISE_DURATION_TOTAL) { r, s, e -> r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.let { Bucket(s, e, sum = it.toMinutes().toDouble()) } }
            "heart_rate" -> agg(HeartRateRecord::class, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT) { r, s, e ->
                r[HeartRateRecord.BPM_AVG]?.let { Bucket(s, e, avg = it.toDouble(), min = r[HeartRateRecord.BPM_MIN]?.toDouble(), max = r[HeartRateRecord.BPM_MAX]?.toDouble(), count = r[HeartRateRecord.MEASUREMENTS_COUNT]?.toInt()) } }
            "resting_heart_rate" -> agg(RestingHeartRateRecord::class, RestingHeartRateRecord.BPM_AVG, RestingHeartRateRecord.BPM_MIN, RestingHeartRateRecord.BPM_MAX) { r, s, e ->
                r[RestingHeartRateRecord.BPM_AVG]?.let { Bucket(s, e, avg = it.toDouble(), min = r[RestingHeartRateRecord.BPM_MIN]?.toDouble(), max = r[RestingHeartRateRecord.BPM_MAX]?.toDouble()) } }
            "blood_pressure_systolic" -> agg(BloodPressureRecord::class, BloodPressureRecord.SYSTOLIC_AVG, BloodPressureRecord.SYSTOLIC_MIN, BloodPressureRecord.SYSTOLIC_MAX) { r, s, e ->
                r[BloodPressureRecord.SYSTOLIC_AVG]?.let { Bucket(s, e, avg = it.inMillimetersOfMercury, min = r[BloodPressureRecord.SYSTOLIC_MIN]?.inMillimetersOfMercury, max = r[BloodPressureRecord.SYSTOLIC_MAX]?.inMillimetersOfMercury) } }
            "blood_pressure_diastolic" -> agg(BloodPressureRecord::class, BloodPressureRecord.DIASTOLIC_AVG, BloodPressureRecord.DIASTOLIC_MIN, BloodPressureRecord.DIASTOLIC_MAX) { r, s, e ->
                r[BloodPressureRecord.DIASTOLIC_AVG]?.let { Bucket(s, e, avg = it.inMillimetersOfMercury, min = r[BloodPressureRecord.DIASTOLIC_MIN]?.inMillimetersOfMercury, max = r[BloodPressureRecord.DIASTOLIC_MAX]?.inMillimetersOfMercury) } }
            "body_mass" -> agg(WeightRecord::class, WeightRecord.WEIGHT_AVG, WeightRecord.WEIGHT_MIN, WeightRecord.WEIGHT_MAX) { r, s, e ->
                r[WeightRecord.WEIGHT_AVG]?.let { Bucket(s, e, avg = it.inKilograms, min = r[WeightRecord.WEIGHT_MIN]?.inKilograms, max = r[WeightRecord.WEIGHT_MAX]?.inKilograms) } }
            "height" -> agg(HeightRecord::class, HeightRecord.HEIGHT_AVG, HeightRecord.HEIGHT_MIN, HeightRecord.HEIGHT_MAX) { r, s, e ->
                r[HeightRecord.HEIGHT_AVG]?.let { Bucket(s, e, avg = it.inMeters, min = r[HeightRecord.HEIGHT_MIN]?.inMeters, max = r[HeightRecord.HEIGHT_MAX]?.inMeters) } }
            "heart_rate_variability" -> if (can(HeartRateVariabilityRmssdRecord::class)) samples(read(HeartRateVariabilityRmssdRecord::class, from, to), { it.time }, { it.heartRateVariabilityMillis }) else emptyList()
            "oxygen_saturation" -> if (can(OxygenSaturationRecord::class)) samples(read(OxygenSaturationRecord::class, from, to), { it.time }, { it.percentage.value }) else emptyList()
            "respiratory_rate" -> if (can(RespiratoryRateRecord::class)) samples(read(RespiratoryRateRecord::class, from, to), { it.time }, { it.rate }) else emptyList()
            "body_fat_percentage" -> if (can(BodyFatRecord::class)) samples(read(BodyFatRecord::class, from, to), { it.time }, { it.percentage.value }) else emptyList()
            "vo2_max" -> if (can(Vo2MaxRecord::class)) samples(read(Vo2MaxRecord::class, from, to), { it.time }, { it.vo2MillilitersPerMinuteKilogram }) else emptyList()
            else -> emptyList()
        }
    }

    /** Workouts as events: activity, minutes, and the heart rate during them when the watch recorded it. */
    private suspend fun workouts(from: Instant, to: Instant): List<JsonObject> = read(ExerciseSessionRecord::class, from, to).take(200).map { w ->
        val hr = runCatching {
            client.aggregate(androidx.health.connect.client.request.AggregateRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX), TimeRangeFilter.between(w.startTime, w.endTime)))
        }.getOrNull()
        buildJsonObject {
            put("id", ("hc:" + w.metadata.id).filter { it.isLetterOrDigit() || it in ":_-" }.take(80))
            put("kind", "workout"); put("metric", "workout")
            put("start", iso(w.startTime.toEpochMilli())); put("end", iso(maxOf(w.endTime, w.startTime).toEpochMilli()))
            w.metadata.dataOrigin.packageName.takeIf { it.isNotBlank() }?.let { put("source", it.take(120)) }
            putJsonObject("data") {
                put("activity", exerciseName(w.exerciseType))
                put("duration_min", Duration.between(w.startTime, w.endTime).toMinutes())
                hr?.get(HeartRateRecord.BPM_AVG)?.let { put("avg_heart_rate", it) }
                hr?.get(HeartRateRecord.BPM_MAX)?.let { put("max_heart_rate", it) }
                w.title?.takeIf { it.isNotBlank() }?.let { put("title", it.take(120)) }
            }
        }
    }

    fun summary(): String = when {
        !available -> tr(R.string.healthsync_unavailable)
        !prefs.enabled -> tr(R.string.healthsync_off)
        error != null -> tr(R.string.healthsync_error, error.orEmpty())
        else -> prefs.categories.joinToString(", ") { c -> CATEGORY_LABELS[c]?.let { tr(it) } ?: c }.replaceFirstChar { it.uppercase() }
    }

    companion object {
        /** Metrics that also get hour windows for the last two days. */
        private val HOURLY = setOf("steps", "distance_walking_running", "active_energy", "heart_rate")
        /** Each category's name, lowercase (the summary lists them in a sentence). */
        val CATEGORY_LABELS = mapOf("activity" to R.string.healthsync_cat_activity, "heart" to R.string.healthsync_cat_heart,
            "sleep" to R.string.healthsync_cat_sleep, "workouts" to R.string.healthsync_cat_workouts, "body" to R.string.healthsync_cat_body,
            "vitals" to R.string.healthsync_cat_vitals, "fitness" to R.string.healthsync_cat_fitness)

        fun exerciseName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycling"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dance"
            else -> "other"
        }
    }
}
