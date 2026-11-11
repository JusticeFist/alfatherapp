package app.hermesandroid.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.hermesandroid.graph
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Saved-place arrivals/departures and big moves, delivered by Play services even when the app is closed. */
class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val location = graph.location
        goAsync().finishWithin(graph.scope, graph.scope.launch {
            when (intent.action) {
                GEOFENCE -> GeofencingEvent.fromIntent(intent)?.takeIf { !it.hasError() }?.let { e ->
                    val kind = if (e.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) "arrive" else "leave"
                    val f = e.triggeringLocation?.let { RawFix(it.latitude, it.longitude, it.accuracy, if (it.hasSpeed()) it.speed else null, it.time) }
                    val ids = e.triggeringGeofences.orEmpty().map { it.requestId }
                    graph.alerts.onPlace(kind, ids)
                    location.onPlaces(kind, ids, f)
                }
                MOVED -> LocationResult.extractResult(intent)?.lastLocation?.let {
                    location.onMoved(RawFix(it.latitude, it.longitude, it.accuracy, if (it.hasSpeed()) it.speed else null, it.time))
                }
            }
        })
    }

    companion object {
        const val GEOFENCE = "app.hermesandroid.location.GEOFENCE"
        const val MOVED = "app.hermesandroid.location.MOVED"
    }
}

/** Ends a goAsync() broadcast when [work] is done or after 8 s: Android calls the receiver hung (ANR) after 10 s for a
 *  foreground broadcast, 60 s for a background one, and an upload can take longer (60 s read timeout, the send lock).
 *  [work] goes on in [scope]; what it doesn't get through rides the next batch (arrive/leave events are queued). */
fun BroadcastReceiver.PendingResult.finishWithin(scope: CoroutineScope, work: Job) {
    scope.launch { try { withTimeoutOrNull(8_000) { work.join() } } finally { finish() } }
}
