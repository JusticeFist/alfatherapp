package app.hermesandroid.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.hermesandroid.graph
import app.hermesandroid.net.Store
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepSegmentEvent
import com.google.android.gms.location.SleepSegmentRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Sleep estimated by the phone (Google Play services' Sleep API: stillness, light and when the phone stops being
// used) for a user who doesn't sleep with the watch. Play services delivers one segment a day after waking; the
// nights stay on the phone and go out as sleep buckets marked as an estimate, only where Health Connect has none.

/** One estimated sleep: start and end (ms). */
@Serializable
data class PhoneNight(val start: Long, val end: Long)

object PhoneSleep {
    private val json = Json { ignoreUnknownKeys = true }
    const val SOURCE = "Phone estimate"

    fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    fun nights(store: Store): List<PhoneNight> =
        runCatching { json.decodeFromString(ListSerializer(PhoneNight.serializer()), store.share("phone-sleep")) }.getOrDefault(emptyList())

    /** Adds segments; ones less than an hour apart are one night (getting up at 3 a.m. doesn't split it). Keeps 60. */
    fun add(store: Store, segments: List<PhoneNight>) =
        store.setShare("phone-sleep", json.encodeToString(ListSerializer(PhoneNight.serializer()), merge(nights(store) + segments).takeLast(60)))

    fun merge(all: List<PhoneNight>): List<PhoneNight> {
        val merged = mutableListOf<PhoneNight>()
        all.sortedBy { it.start }.forEach { n ->
            val last = merged.lastOrNull()
            if (last != null && n.start - last.end < 3_600_000L) merged[merged.lastIndex] = PhoneNight(last.start, maxOf(last.end, n.end))
            else merged += n
        }
        return merged
    }

    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 7101, Intent(context, PhoneSleepReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Registers (again) for daily sleep segments; harmless to repeat, needed after a reboot or an update. */
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (!permitted(context)) return
        runCatching { ActivityRecognition.getClient(context).requestSleepSegmentUpdates(intent(context), SleepSegmentRequest(SleepSegmentRequest.SEGMENT_EVENTS_ONLY)) }
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        runCatching { ActivityRecognition.getClient(context).removeSleepSegmentUpdates(intent(context)) }
    }
}

class PhoneSleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SleepSegmentEvent.hasEvents(intent)) return
        val segments = SleepSegmentEvent.extractEvents(intent)
            .filter { it.status == SleepSegmentEvent.STATUS_SUCCESSFUL && it.endTimeMillis > it.startTimeMillis }
            .map { PhoneNight(it.startTimeMillis, it.endTimeMillis) }
        if (segments.isEmpty()) return
        val graph = context.graph
        PhoneSleep.add(graph.store, segments)
        android.util.Log.i("PhoneSleep", "segments: ${segments.size}")
        graph.health.sync("sleep", force = true)
    }
}
