package app.hermesandroid.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.BatteryManager
import android.provider.CalendarContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import app.hermesandroid.AppGraph
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.graph
import app.hermesandroid.location.finishWithin
import app.hermesandroid.location.metres
import app.hermesandroid.push.newNotification
import app.hermesandroid.ui.shell.defaultProfileTitle
import com.google.android.gms.location.LocationServices
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Proactive notices, worked out on the phone from its calendar, location and battery (nothing goes to the host):
// time to leave for an event, leaving / arriving home, low battery before an event, events that clash.

@Serializable
data class AlertPrefs(
    val enabled: Boolean = false,
    val leave: Boolean = true,
    val home: Boolean = true,
    val battery: Boolean = true,
    val conflicts: Boolean = true,
)

private const val CHANNEL = "alerts"
private const val EVERY_MS = 30 * 60_000L
private const val HOUR = 3600_000L

class Alerts(private val graph: AppGraph, private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    /** Notices already given (key → when) and geocoded event places ("geo:<text>" → "lat,lon" or ""). */
    private val sp = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    var prefs by mutableStateOf(runCatching { json.decodeFromString<AlertPrefs>(graph.store.share("alerts-prefs")) }.getOrDefault(AlertPrefs()))
        private set

    fun choose(next: AlertPrefs) {
        prefs = next
        graph.store.setShare("alerts-prefs", json.encodeToString(AlertPrefs.serializer(), next))
        check("changed")
    }

    fun canReadCalendar() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    private fun canLocate() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun summary() = if (!prefs.enabled) tr(R.string.alerts_off) else listOfNotNull(
        R.string.alerts_kind_leave.takeIf { prefs.leave }, R.string.alerts_kind_home.takeIf { prefs.home },
        R.string.alerts_kind_battery.takeIf { prefs.battery }, R.string.alerts_kind_conflicts.takeIf { prefs.conflicts },
    ).joinToString(", ") { tr(it) }.replaceFirstChar { it.uppercase() }.ifEmpty { tr(R.string.alerts_none) }

    /** A pass now; it schedules the next one (every 30 min, or when a leave notice is due). */
    fun check(reason: String): Job = graph.scope.launch(Dispatchers.IO) {
        lock.withLock { runCatching { pass() }.onFailure { android.util.Log.w("Alerts", "pass $reason: $it") } }
    }

    private suspend fun pass() {
        val p = prefs
        if (!p.enabled || !canReadCalendar()) { alarm(null); return }
        val now = System.currentTimeMillis()
        prune(now)
        val events = events(now - 12 * HOUR, now + 36 * HOUR)
        var next = now + EVERY_MS

        if (p.conflicts) conflicts(events.filter { it.end > now && it.start < now + 24 * HOUR }).forEach { (a, hit) ->
            val listed = hit.take(3).joinToString(", ") { tr(R.string.alerts_event_at, it.title, time(it.start)) }
            val others = if (hit.size > 3) tr(R.string.alerts_others_more, listed, hit.size - 3) else listed
            notify("conflicts:${a.key}", if (hit.size == 1) tr(R.string.alerts_clash_two) else tr(R.string.alerts_clash_many, hit.size + 1),
                tr(R.string.alerts_clash_body, a.title, time(a.start), time(a.end), others))
        }

        val soon = events.filter { it.start > now && it.start < now + 2 * HOUR }
        if (p.battery) battery()?.let { level ->
            soon.firstOrNull()?.let { e ->
                notify("battery:${e.key}", tr(R.string.alerts_battery_title, level), tr(R.string.alerts_battery_body, e.title, time(e.start)))
            }
        }

        if (p.leave) here()?.let { (lat, lon) ->
            for (e in events.filter { it.start > now && it.start < now + 4 * HOUR && placeLike(it.location) }) {
                val (dlat, dlon) = geocode(e.location!!, lat, lon) ?: continue
                val m = metres(lat, lon, dlat, dlon)
                if (m < 300 || m > 200_000) continue // already there, or the geocoder guessed another city
                val travel = travelMinutes(m)
                val at = noticeAt(e.start, travel)
                val mode = tr(if (walking(m)) R.string.alerts_on_foot else R.string.alerts_by_car)
                when {
                    now < at -> next = minOf(next, at)
                    now < e.start - travel * 60_000L -> notify("leave:${e.key}",
                        if (now < at + 2 * 60_000L) tr(R.string.alerts_leave_in, LEAD_MIN) else tr(R.string.alerts_leave_now),
                        tr(R.string.alerts_leave_body, e.title, time(e.start), travel, mode), e.location)
                }
            }
        }
        alarm(next)
    }

    /** Saved-place arrivals and departures (geofences, see LocationReceiver): only "home" places speak up. */
    fun onPlace(kind: String, placeIds: List<String>) {
        if (!prefs.enabled || !prefs.home || !canReadCalendar()) return
        if (graph.location.prefs.places.none { it.id in placeIds && it.kind == "home" }) return
        graph.scope.launch(Dispatchers.IO) {
            lock.withLock {
                val now = System.currentTimeMillis()
                val events = events(now, now + 36 * HOUR)
                val low = battery()?.let { " " + tr(R.string.alerts_battery_note, it) } ?: ""
                if (kind == "leave") events.firstOrNull { it.start < now + 6 * HOUR }?.let { e ->
                    notify("home-leave:${e.key}", tr(R.string.alerts_left_home), (e.location?.let { tr(R.string.alerts_next_at, e.title, time(e.start), it) } ?: tr(R.string.alerts_next, e.title, time(e.start))) + low)
                } else {
                    val tomorrow = java.time.LocalDate.now().plusDays(1)
                    events.firstOrNull { app.hermesandroid.phone.day(it.start) == tomorrow }?.let { e ->
                        notify("home-arrive:${e.key}", tr(R.string.alerts_home_now), tr(R.string.alerts_tomorrow, e.title, time(e.start)) + low)
                    }
                }
            }
        }
    }

    // ---- reading the phone ----

    private fun events(from: Long, to: Long): List<Ev> {
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.STATUS, CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.VISIBLE)
        val out = mutableListOf<Ev>()
        CalendarContract.Instances.query(context.contentResolver, cols, from, to)?.use { c ->
            while (c.moveToNext()) {
                val skip = c.getInt(5) == 1 || c.getInt(6) == CalendarContract.Events.AVAILABILITY_FREE ||
                    (!c.isNull(7) && c.getInt(7) == CalendarContract.Events.STATUS_CANCELED) ||
                    c.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED || c.getInt(9) == 0
                if (skip) continue
                out += Ev("${c.getLong(0)}@${c.getLong(1)}", c.getString(3)?.takeIf { it.isNotBlank() } ?: tr(R.string.alerts_untitled),
                    c.getLong(1), maxOf(c.getLong(2), c.getLong(1)), c.getString(4)?.takeIf { it.isNotBlank() })
            }
        }
        return out.sortedBy { it.start }
    }

    /** The battery level when it's 25 % or lower and not charging. */
    private fun battery(): Int? {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level.takeIf { it in 1..25 && !bm.isCharging }
    }

    @SuppressLint("MissingPermission")
    private suspend fun here(): Pair<Double, Double>? {
        if (!canLocate()) return null
        val loc = withTimeoutOrNull(5000) { runCatching { LocationServices.getFusedLocationProviderClient(context).lastLocation.await() }.getOrNull() } ?: return null
        return loc.latitude to loc.longitude
    }

    /** The event's place, looked up near where you are and kept (a place doesn't move). */
    @Suppress("DEPRECATION")
    private fun geocode(text: String, lat: Double, lon: Double): Pair<Double, Double>? {
        val key = "geo:" + text.trim().lowercase()
        sp.getString(key, null)?.let { v -> return v.split(",").takeIf { it.size == 2 }?.let { it[0].toDouble() to it[1].toDouble() } }
        val g = Geocoder(context)
        val hit = runCatching { g.getFromLocationName(text, 1, lat - 1, lon - 1, lat + 1, lon + 1)?.firstOrNull() ?: g.getFromLocationName(text, 1)?.firstOrNull() }
        if (hit.isFailure) return null // offline: try again next pass
        val a = hit.getOrNull()
        sp.edit().putString(key, a?.let { "${it.latitude},${it.longitude}" } ?: "").apply()
        return a?.let { it.latitude to it.longitude }
    }

    // ---- telling ----

    private fun notify(key: String, title: String, text: String, place: String? = null) {
        if (sp.contains("f:$key")) return
        sp.edit().putLong("f:$key", System.currentTimeMillis()).apply()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.alerts_channel, defaultProfileTitle), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = tr(R.string.alerts_channel_desc) })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val b = newNotification(context, CHANNEL)
            .setContentTitle("$title · $defaultProfileTitle").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(open).setAutoCancel(true)
        place?.let {
            val maps = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(it))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            b.addAction(0, tr(R.string.alerts_directions), PendingIntent.getActivity(context, key.hashCode(), maps, PendingIntent.FLAG_IMMUTABLE))
        }
        runCatching { nm.notify(key.hashCode(), b.build()) }
        android.util.Log.i("Alerts", "notice $key")
    }

    private fun prune(now: Long) {
        val old = sp.all.filter { (k, v) -> k.startsWith("f:") && v is Long && now - v > 3 * 24 * HOUR }.keys
        if (old.isNotEmpty()) sp.edit().apply { old.forEach(::remove) }.apply()
    }

    private fun time(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    /** The next pass. Inexact under Doze (Android spaces these ~9 min), close enough for a 10-minute lead. */
    private fun alarm(at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, AlertsReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (at == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

/** The scheduled pass, and after a reboot or an update (alarms don't survive either). */
class AlertsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        goAsync().finishWithin(context.graph.scope, context.graph.alerts.check(intent.action ?: "alarm"))
    }
}
