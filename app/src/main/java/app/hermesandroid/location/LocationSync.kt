package app.hermesandroid.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.trPlural
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.phone.iso
import app.hermesandroid.share.KeyLost
import app.hermesandroid.share.ShareChannel
import app.hermesandroid.share.ShareLink
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.security.SecureRandom
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Location sharing: two layers the user turns on apart, each with its own
// precision. "Talking" sends a fix when the user writes or calls; "background" follows saved places (arrive /
// leave), big moves, and answers "where's my phone?" pushes. Saved places' coordinates never leave the phone.

@Serializable
data class LocationPrefs(
    val enabled: Boolean = false,
    val talking: Boolean = true,
    val talkingPrecision: String = PRECISE,
    val background: Boolean = false,
    val backgroundPrecision: String = AREA,
    val retentionDays: Int = 7,
    /** Paused until this time (ms), or null. */
    val pausedUntil: Long? = null,
    val places: List<SavedPlace> = emptyList(),
)

class LocationSync(private val graph: AppGraph, private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val link = ShareLink(graph, ShareChannel.Location)
    private val lock = Mutex()
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val random = SecureRandom()

    var prefs by mutableStateOf(runCatching { json.decodeFromString<LocationPrefs>(graph.store.share("location-prefs")) }.getOrDefault(LocationPrefs()))
        private set
    var lastSent by mutableStateOf<Long?>(null)
        private set
    var error by mutableStateOf<String?>(null)

    /** The last fix taken and what went out last (for "don't resend the same place"). */
    private var cached: RawFix? = null
    private var sentFix: Pair<RawFix, String?>? = null

    /** Arrive/leave events not on the host yet, oldest first. Stored so that neither a receiver cut short nor a killed
     *  process loses one: every batch carries them until one goes through (the host ignores repeats by id). */
    private var queued: List<JsonObject> = runCatching { (json.parseToJsonElement(graph.store.share("location-queue")) as JsonArray).mapNotNull { it.obj() } }.getOrDefault(emptyList())
        set(v) { field = v; graph.store.setShare("location-queue", JsonArray(v).toString()) }

    fun choose(next: LocationPrefs) {
        val wasOn = prefs.enabled
        prefs = next
        graph.store.setShare("location-prefs", json.encodeToString(LocationPrefs.serializer(), next))
        if (!next.enabled || !next.background) queued = emptyList()
        if (next.enabled) { watch(); graph.scope.launch { send(null, reason = "settings") } }
        else if (wasOn) { unwatch(); graph.scope.launch { runCatching { lock.withLock { link.wipe() } }.onFailure { error = tr(R.string.locationsync_wipe_failed, it.message.orEmpty()) } } }
    }

    fun hasFine() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasCoarse() = hasFine() || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground() = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    private val paused get() = prefs.pausedUntil?.let { it > System.currentTimeMillis() } == true
    private val backgroundOn get() = prefs.enabled && prefs.background && !paused && hasBackground()

    private fun sharingWire() = buildJsonObject {
        val p = prefs
        put("talking", p.talking && hasCoarse()); put("talking_precision", p.talkingPrecision)
        put("background", p.background && hasBackground()); put("background_precision", p.backgroundPrecision)
        put("retention_days", p.retentionDays)
        put("paused_until", p.pausedUntil?.let { kotlinx.serialization.json.JsonPrimitive(iso(it)) } ?: kotlinx.serialization.json.JsonNull)
        put("time_zone", ZoneId.systemDefault().id)
        // No "maps": Android has no Apple Maps engine, so conduit_maps stays on the host.
        put("maps_host", true)
    }

    /** One batch: settings always, places when they changed (or the key is new) or events ride along, then a fix
     *  and/or the queued events. */
    private suspend fun send(fix: JsonObject?, reason: String, locateId: String? = null) = lock.withLock {
        if (!prefs.enabled || !graph.signedIn) return@withLock
        // Events need the background layer on the host too, and a place the host knows (a removed one would fail the batch).
        val events = if (backgroundOn) queued.filter { e -> prefs.places.any { it.id == e.str("place_id") } } else emptyList()
        suspend fun once(withPlaces: Boolean): JsonObject? {
            if (link.state.key == null) link.rekey()
            val placesWire = placesWire(prefs.places)
            val placesHash = app.hermesandroid.phone.digest(placesWire.toString())
            val body = buildJsonObject {
                put("batch_id", ShareLink.batchId()); put("sent_at", iso(System.currentTimeMillis()))
                put("sharing", sharingWire())
                if (withPlaces || events.isNotEmpty() || link.state.sent["places"] != placesHash) put("places", placesWire)
                fix?.let { put("fix", it) }
                if (events.isNotEmpty()) put("events", JsonArray(events))
                // At the batch's top level: the host marks that locate answered (location_store `_apply`).
                locateId?.let { put("locate_id", it) }
            }
            val r = link.ingest(body)
            link.update { it.copy(sent = it.sent + ("places" to placesHash), lastSendAt = System.currentTimeMillis()) }
            return r
        }
        try {
            val r = try { once(withPlaces = false) } catch (e: KeyLost) { link.rekey(); once(withPlaces = true) }
            if (events.isNotEmpty()) queued = queued - events.toSet()
            lastSent = System.currentTimeMillis(); error = null
            android.util.Log.i("LocationSync", "$reason -> ${r?.toString()?.take(160)}")
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            android.util.Log.w("LocationSync", "$reason failed: $error")
        }
    }

    // ---- fixes ----

    @SuppressLint("MissingPermission")
    private suspend fun measure(priority: Int, timeoutMs: Long): RawFix? {
        if (!hasCoarse()) return null
        val token = CancellationTokenSource()
        val loc = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                fused.getCurrentLocation(if (hasFine()) priority else Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token)
                    .addOnSuccessListener { cont.resume(it) }.addOnFailureListener { cont.resume(null) }
                cont.invokeOnCancellation { token.cancel() }
            }
        } ?: return null
        return RawFix(loc.latitude, loc.longitude, loc.accuracy, if (loc.hasSpeed()) loc.speed else null, loc.time).also { cached = it }
    }

    private suspend fun area(f: RawFix): Area? = withTimeoutOrNull(1500) {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { Geocoder(context).getFromLocation(f.lat, f.lon, 1)?.firstOrNull() }.getOrNull()?.let {
                Area(it.subLocality ?: it.featureName?.takeIf { n -> n.any(Char::isLetter) }, it.locality, it.adminArea, it.countryName, it.countryCode)
            }
        }
    }

    private fun fixId() = "f_" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private suspend fun wire(f: RawFix, source: String, precision: String): JsonObject? =
        fixWire(fixId(), source, iso(f.at.takeIf { it > 0 } ?: System.currentTimeMillis()), f, precision, prefs.places,
            if (precision == PLACES) null else area(f), coarseOnly = !hasFine())

    /** Before a message goes out (talking layer). Doesn't hold the message: the fix follows it within seconds. */
    fun onMessage() = talking("message")

    /** A voice call started (talking layer). */
    fun onCall() = talking("call")

    private fun talking(source: String) {
        val p = prefs
        if (!p.enabled || !p.talking || paused || !hasCoarse()) return
        graph.scope.launch {
            val now = System.currentTimeMillis()
            val f = cached?.takeIf { now - it.at < 30_000 || (now - it.at < 300_000 && (it.speed ?: 0f) < 2f) }
                ?: measure(Priority.PRIORITY_HIGH_ACCURACY, 2500) ?: return@launch
            // The same place isn't resent within 10 min unless the phone moved > 100 m.
            val place = placeAt(f, p.places)?.id
            sentFix?.let { (last, lastPlace) ->
                if (now - last.at < 600_000 && lastPlace == place && metres(f.lat, f.lon, last.lat, last.lon) < 100) return@launch
            }
            val fix = wire(f, source, p.talkingPrecision) ?: return@launch
            sentFix = f to place
            send(fix, reason = source)
        }
    }

    /** The app came to the front (background layer): a fix when the last one is over 15 min old. */
    fun onAppOpen() {
        if (!backgroundOn || (lastSent ?: link.state.lastSendAt).let { System.currentTimeMillis() - it < 15 * 60_000L }) return
        graph.scope.launch { measure(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 6000)?.let { f -> wire(f, "app", prefs.backgroundPrecision)?.let { send(it, reason = "app") } } }
    }

    /** "Where's my phone?" from Hermes (`location.requested` push): one fix within the host's 20 s. */
    suspend fun onLocate(locateId: String) {
        if (!backgroundOn) return
        val f = measure(Priority.PRIORITY_HIGH_ACCURACY, 6000) ?: cached ?: return
        send(wire(f, "locate", prefs.backgroundPrecision), reason = "locate", locateId = locateId)
    }

    /** Geofence transitions and big moves, from [LocationReceiver]. */
    suspend fun onPlaces(kind: String, placeIds: List<String>, f: RawFix?) {
        if (!backgroundOn) return
        val at = iso(System.currentTimeMillis())
        val events = placeIds.filter { id -> prefs.places.any { it.id == id } }.map { id ->
            buildJsonObject { put("id", "v_" + ByteArray(10).also(random::nextBytes).joinToString("") { "%02x".format(it) }); put("kind", kind); put("place_id", id); put("at", at) }
        }
        // Stored before anything slow (geocoding, the upload); the host takes at most 100 per batch.
        queued = (queued + events).takeLast(100)
        val fix = f?.let { wire(it, "place", prefs.backgroundPrecision) }
        if (events.isNotEmpty() || fix != null) send(fix, reason = "place $kind")
    }

    private var lastMoved: RawFix? = null

    suspend fun onMoved(f: RawFix) {
        if (!backgroundOn || prefs.backgroundPrecision == PLACES) return
        cached = f
        // Re-registering delivers the current position at once: only a real move (> 500 m) counts.
        lastMoved?.let { if (metres(f.lat, f.lon, it.lat, it.lon) < 500) return }
        lastMoved = f
        wire(f, "significant", prefs.backgroundPrecision)?.let { send(it, reason = "significant") }
    }

    // ---- places and watching ----

    suspend fun addPlaceHere(name: String, kind: String, radius: Int, private: Boolean): String? {
        val f = measure(Priority.PRIORITY_HIGH_ACCURACY, 10_000) ?: return tr(R.string.locationsync_no_fix)
        if (f.accuracy > 200) return tr(R.string.locationsync_imprecise, f.accuracy.toInt())
        val id = "p_" + ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        choose(prefs.copy(places = (prefs.places + SavedPlace(id, name.trim().take(40), kind, f.lat, f.lon, radius, private)).take(20)))
        return null
    }

    fun removePlace(id: String) = choose(prefs.copy(places = prefs.places.filterNot { it.id == id }))

    private fun receiverIntent(action: String) = PendingIntent.getBroadcast(context, action.hashCode(),
        Intent(context, LocationReceiver::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Geofences for saved places, and big-move updates when the background precision isn't "places only". */
    @SuppressLint("MissingPermission")
    fun watch() {
        unwatch()
        if (!backgroundOn) return
        val places = prefs.places
        if (places.isNotEmpty()) runCatching {
            LocationServices.getGeofencingClient(context).addGeofences(
                GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(places.map { p ->
                    Geofence.Builder().setRequestId(p.id).setCircularRegion(p.lat, p.lon, p.radius.coerceIn(100, 500).toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE).setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build()
                }).build(), receiverIntent(LocationReceiver.GEOFENCE))
        }
        if (prefs.backgroundPrecision != PLACES) runCatching {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15 * 60_000L)
                .setMinUpdateDistanceMeters(500f).setMinUpdateIntervalMillis(5 * 60_000L).build(), receiverIntent(LocationReceiver.MOVED))
        }
    }

    private fun unwatch() {
        runCatching { LocationServices.getGeofencingClient(context).removeGeofences(receiverIntent(LocationReceiver.GEOFENCE)) }
        runCatching { fused.removeLocationUpdates(receiverIntent(LocationReceiver.MOVED)) }
    }

    // ---- what Allfather did with it ----

    suspend fun rules(): List<JsonObject> = runCatching {
        (graph.client.rest("GET", graph.client.withProfile("/api/plugins/conduit_push/location/rules", "default")).obj()?.get("rules") as? JsonArray).orEmpty().mapNotNull { it.obj() }
    }.getOrDefault(emptyList())

    suspend fun deleteRule(id: String) = graph.client.rest("DELETE", graph.client.withProfile("/api/plugins/conduit_push/location/rules/$id", "default"))

    suspend fun reads(): List<JsonObject> = runCatching {
        (graph.client.rest("GET", graph.client.withProfile("/api/plugins/conduit_push/location/reads?limit=20", "default")).obj()?.get("reads") as? JsonArray).orEmpty().mapNotNull { it.obj() }
    }.getOrDefault(emptyList())

    fun summary(): String = when {
        !prefs.enabled -> tr(R.string.locationsync_off)
        paused -> tr(R.string.locationsync_paused)
        error != null -> tr(R.string.locationsync_error, error.orEmpty())
        else -> {
            val bg = prefs.background && hasBackground()
            tr(when {
                prefs.talking && bg -> R.string.locationsync_talking_and_background
                prefs.talking -> R.string.locationsync_talking
                bg -> R.string.locationsync_background
                else -> R.string.locationsync_nothing
            }) + " · " + trPlural(R.plurals.locationsync_places, prefs.places.size, prefs.places.size)
        }
    }

    companion object {
        @StringRes fun precisionLabel(p: String): Int = when (p) {
            PLACES -> R.string.locationsync_precision_places; AREA -> R.string.locationsync_precision_area; else -> R.string.locationsync_precision_exact
        }
    }
}
