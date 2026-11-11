package app.hermesandroid.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.share.KeyLost
import app.hermesandroid.share.ShareChannel
import app.hermesandroid.share.ShareLink
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Phone sharing: the calendar, the phone's state and Do Not Disturb, sealed to
// `/phone/ingest` so Allfather knows the user's day. Reminders, Focus names and CarPlay have no Android
// counterpart and are never sent (the host simply doesn't mention them).

/** What the user chose to share. Calendar: "off" | "read" | "full" (read and let Allfather add or change). */
@Serializable
data class PhonePrefs(
    val enabled: Boolean = false,
    val calendar: String = "full",
    val details: Boolean = false,
    val futureDays: Int = 60,
    val status: Boolean = true,
    val focus: Boolean = true,
    val hiddenCalendars: Set<Long> = emptySet(),
    /** Allfather's additions (events, alarms, timers) go in without a card. */
    val autoAdd: Boolean = false,
    /** Alarms and timers in the Clock app, for Allfather to set. */
    val alarms: Boolean = true,
    /** Contacts the user picked (lookup key → name shown in settings); only these are ever sent. */
    val contacts: Map<String, String> = emptyMap(),
)

private val SNAPSHOT_REASONS = setOf("changed", "permission", "manual")

class PhoneSync(private val graph: AppGraph, private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val link = ShareLink(graph, ShareChannel.Phone)
    private val lock = Mutex()

    var prefs by mutableStateOf(runCatching { json.decodeFromString<PhonePrefs>(graph.store.share("phone-prefs")) }.getOrDefault(PhonePrefs()))
        private set
    var lastSync by mutableStateOf<Long?>(null)
        private set
    var counts by mutableStateOf<JsonObject?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    private var lastPassAt = 0L

    fun choose(next: PhonePrefs) {
        val wasOn = prefs.enabled
        prefs = next
        graph.store.setShare("phone-prefs", json.encodeToString(PhonePrefs.serializer(), next))
        if (next.enabled) { watchCalendar(); sync("changed", force = true) }
        else if (wasOn) graph.scope.launch { runCatching { lock.withLock { link.wipe() } }.onFailure { error = tr(R.string.phonesync_wipe_failed, it.message.orEmpty()) } }
        PhoneJobs.schedule(context)
    }

    fun canReadCalendar() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** A pass, spaced 30 s for the frequent triggers (foreground, message, calendar). Returns it, or null when skipped. */
    fun sync(reason: String, force: Boolean = false): kotlinx.coroutines.Job? {
        if (!prefs.enabled || !graph.signedIn) return null
        val now = System.currentTimeMillis()
        if (!force && now - lastPassAt < 30_000) return null
        lastPassAt = now
        return graph.scope.launch {
            lock.withLock {
                busy = true
                try {
                    // `force` only skips the spacing; a full snapshot is for a new choice or a lost key.
                    try { pass(reason, snapshot = reason in SNAPSHOT_REASONS) } catch (e: KeyLost) { pass(reason, snapshot = true) }
                    error = null
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    android.util.Log.w("PhoneSync", "pass $reason failed: $error")
                } finally { busy = false }
            }
        }
    }

    private suspend fun pass(reason: String, snapshot: Boolean) {
        val status = link.status()
        status?.get("device").obj()?.let { d -> counts = d["counts"].obj(); d.num("last_sync")?.let { lastSync = (it * 1000).toLong() } }
        val resend = link.ready(status)
        val st = link.state
        val now = System.currentTimeMillis()
        val p = prefs
        val prefsHash = digest(p.toString())
        val full = snapshot || resend || now - st.lastFullAt > 24 * 3600_000L || st.sent["prefs"] != prefsHash
        val zone = ZoneId.systemDefault()

        val sent = st.sent.toMutableMap()
        var changed = full
        var newStatus: String? = null
        val body = buildJsonObject {
            put("batch_id", ShareLink.batchId())
            put("sent_at", iso(now))
            put("time_zone", zone.id)
            put("sharing", buildJsonObject {
                val cal = p.calendar != "off" && canReadCalendar()
                put("calendar", if (cal) "full" else "off"); put("calendar_read_only", p.calendar == "read" || !canWriteCalendar())
                put("reminders", false); put("reminders_read_only", false)
                put("details", p.details); put("attendee_emails", false)
                put("status", p.status); put("focus", p.focus); put("auto_add", p.autoAdd); put("alarms", p.alarms); put("contacts", p.contacts.isNotEmpty())
                put("past_days", 7); put("future_days", p.futureDays)
            })
            if (p.calendar != "off" && canReadCalendar()) {
                val from = LocalDate.now(zone).minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
                val to = LocalDate.now(zone).plusDays(p.futureDays + 1L).atStartOfDay(zone).toInstant().toEpochMilli()
                val calendars = readCalendars(p)
                val calHash = digest(calendars.toString())
                if (full || sent["calendars"] != calHash) { put("calendars", calendars); sent["calendars"] = calHash; changed = true }
                val events = readEvents(p, calendars, from, to)
                val upsert = events.filter { (id, e) -> full || sent["e:$id"] != digest(e.toString()) }
                val gone = sent.keys.filter { it.startsWith("e:") }.map { it.removePrefix("e:") }.filter { it !in events }
                if (full || upsert.isNotEmpty() || gone.isNotEmpty()) {
                    changed = true
                    put("events", buildJsonObject {
                        put("full", full); put("from", iso(from)); put("to", iso(to))
                        put("upsert", JsonArray(upsert.values.toList()))
                        putJsonArray("delete") { if (!full) gone.forEach { add(it) } }
                    })
                    sent.keys.removeAll { it.startsWith("e:") }
                    events.forEach { (id, e) -> sent["e:$id"] = digest(e.toString()) }
                }
            }
            if (p.status) {
                val s = readStatus()
                val h = digest(s.filterKeys { it != "at" }.toString())
                if (full || h != st.statusHash || now - st.statusAt > 15 * 60_000L) { put("status", s); changed = true; newStatus = h }
            }
            if (p.focus) put("focus", readFocus())
            if (p.alarms) changes.alarmsWire().let { a -> val h = digest(a.toString()); if (full || sent["alarms"] != h) { put("alarms", a); sent["alarms"] = h; changed = true } }
            if (p.contacts.isNotEmpty() && canReadContacts()) {
                val list = readContacts(p.contacts.keys)
                val h = digest(list.toString())
                if (full || sent["contacts"] != h) { put("contacts", list); sent["contacts"] = h; changed = true }
            }
        }
        // Nothing new: a keepalive every 15 min so "last seen" stays fresh while the app is in use.
        if (!changed && now - st.lastSendAt < 15 * 60_000L) { if (changes.hasUnreported() || reason == "push") changes.exchange(); return }
        sent["prefs"] = prefsHash
        val r = link.ingest(body)
        link.update { it.copy(sent = sent, lastSendAt = now, lastFullAt = if (full) now else it.lastFullAt, statusHash = newStatus ?: it.statusHash, statusAt = if (newStatus != null) now else it.statusAt) }
        lastSync = now
        graph.watch.pushStatus()
        // What the host now holds, for the settings page.
        runCatching { link.status() }.getOrNull()?.get("device").obj()?.let { counts = it["counts"].obj() }
        android.util.Log.i("PhoneSync", "pass $reason full=$full -> ${r?.toString()?.take(200)}")
        if ((r?.num("pending") ?: 0.0) > 0 || changes.hasUnreported()) changes.exchange()
    }

    // ---- sources ----

    /** Wire id → this phone's calendar / occurrence, from the last pass: how a change names what it edits. */
    val calendarIndex = mutableMapOf<String, Long>()
    val eventIndex = mutableMapOf<String, EventRef>()
    val changes = PhoneChanges(graph, context, this)

    fun canWriteCalendar() = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun readCalendars(p: PhonePrefs): JsonArray = buildJsonArray {
        val cols = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE)
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id in p.hiddenCalendars || c.getInt(8) == 0) continue
                val type = c.getString(3).orEmpty()
                calendarIndex[wireId("c_", id)] = id
                add(buildJsonObject {
                    put("id", wireId("c_", id)); put("title", (c.getString(1) ?: "Calendar").take(200)); put("kind", "event")
                    put("color", hexColor(c.getInt(2)))
                    put("source", (if (type == "com.google") "Google" else if (type == CalendarContract.ACCOUNT_TYPE_LOCAL) "Phone" else type.substringAfterLast('.')).take(60))
                    put("writable", c.getInt(6) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                    put("shared", c.getString(5) != null && c.getString(5) != c.getString(4))
                    put("default", c.getInt(7) == 1)
                })
            }
        }
    }

    /** Occurrences in [from, to) of the shared calendars, by wire id (event id + occurrence start). Capped at 4000. */
    private fun readEvents(p: PhonePrefs, calendars: JsonArray, from: Long, to: Long): Map<String, JsonObject> {
        val shared = calendars.mapNotNull { (it as JsonObject)["id"]?.let { v -> (v as JsonPrimitive).content } }.toSet()
        val alarms = mutableMapOf<Long, MutableList<Int>>()
        context.contentResolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.EVENT_ID, CalendarContract.Reminders.MINUTES), null, null, null)?.use { c ->
            while (c.moveToNext()) alarms.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getInt(1))
        }
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.EVENT_TIMEZONE, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.AVAILABILITY, CalendarContract.Instances.STATUS, CalendarContract.Instances.RRULE, CalendarContract.Instances.IS_ORGANIZER,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS, CalendarContract.Instances.DESCRIPTION)
        val out = LinkedHashMap<String, JsonObject>()
        eventIndex.clear()
        CalendarContract.Instances.query(context.contentResolver, cols, from, to)?.use { c ->
            while (c.moveToNext() && out.size < 4000) {
                val calId = wireId("c_", c.getLong(5))
                if (calId !in shared) continue
                val allDay = c.getInt(4) == 1
                val begin = c.getLong(1)
                val id = wireId("e_", c.getLong(0), begin)
                eventIndex[id] = EventRef(c.getLong(0), begin, !c.getString(10).isNullOrBlank(), allDay)
                out[id] = buildJsonObject {
                    put("id", id); put("calendar_id", calId); put("title", (c.getString(3) ?: "(untitled)").take(200))
                    put("start", if (allDay) isoAllDay(begin) else iso(begin))
                    put("end", if (allDay) isoAllDay(maxOf(c.getLong(2), begin)) else iso(maxOf(c.getLong(2), begin)))
                    put("all_day", allDay)
                    c.getString(6)?.takeIf { it.isNotBlank() }?.let { put("time_zone", it) }
                    c.getString(7)?.takeIf { it.isNotBlank() }?.let { put("location", it.take(300)) }
                    put("availability", availability(c.getInt(8))); put("status", eventStatus(if (c.isNull(9)) null else c.getInt(9)))
                    put("recurring", !c.getString(10).isNullOrBlank())
                    put("organizer_is_me", c.getString(11) == "1")
                    put("my_status", myStatus(c.getInt(12)))
                    alarms[c.getLong(0)]?.take(10)?.let { m -> putJsonArray("alarms_min") { m.forEach { add(it.coerceIn(-40320, 40320)) } } }
                    if (p.details) c.getString(13)?.takeIf { it.isNotBlank() }?.let { put("notes", it.take(2000)) }
                }
            }
        }
        return out
    }

    private fun readStatus(): JsonObject = buildJsonObject {
        put("at", iso(System.currentTimeMillis()))
        val bm = context.getSystemService(BatteryManager::class.java)
        put("battery", batteryStep(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) / 100f))
        put("charging", bm.isCharging)
        put("low_power", context.getSystemService(PowerManager::class.java).isPowerSaveMode)
        val outputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        put("audio_route", when {
            outputs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLE_HEADSET } -> "bluetooth"
            outputs.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_USB_HEADSET } -> "headphones"
            else -> "speaker"
        })
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        put("network", when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "none"
        })
    }

    /** Android has no named Focus: Do Not Disturb (any filter but "all") stands in for it. */
    private fun readFocus(): JsonObject = buildJsonObject {
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        val on = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        put("at", iso(System.currentTimeMillis())); put("active", on)
        if (on) { put("label", "Do Not Disturb"); put("hold_calls", filter == NotificationManager.INTERRUPTION_FILTER_NONE) }
    }

    fun canReadContacts() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** A contact picked with the system picker: its lookup key and display name, or null. */
    fun picked(uri: android.net.Uri): Pair<String, String>? =
        context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: return@use null) to (c.getString(1) ?: tr(R.string.phonesync_contact)) else null
        }

    /** The picked contacts as the host takes them (≤200; numbers keep only dialable characters). */
    private fun readContacts(keys: Collection<String>): JsonArray = buildJsonArray {
        for (key in keys.take(200)) {
            val contactUri = ContactsContract.Contacts.lookupContact(context.contentResolver, ContactsContract.Contacts.CONTENT_LOOKUP_URI.buildUpon().appendPath(key).build()) ?: continue
            val id = contactUri.lastPathSegment ?: continue
            val name = context.contentResolver.query(contactUri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: continue
            var nickname: String? = null; var org: String? = null; var birthday: String? = null
            val phones = mutableListOf<JsonObject>(); val emails = mutableListOf<JsonObject>()
            context.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3),
                "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id), null)?.use { c ->
                while (c.moveToNext()) {
                    val v = c.getString(1)?.trim().orEmpty()
                    if (v.isEmpty()) continue
                    when (c.getString(0)) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> v.filter { it.isDigit() || it in "+()*#.,;/- " }.take(40).takeIf { it.any(Char::isDigit) && phones.size < 10 }?.let { n ->
                            phones += buildJsonObject { put("number", n); put("label", ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString().take(40)) }
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if ('@' in v && ' ' !in v && v.length <= 254 && emails.size < 10)
                            emails += buildJsonObject { put("address", v); put("label", ContactsContract.CommonDataKinds.Email.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString().take(40)) }
                        ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE -> nickname = v.take(80)
                        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> org = v.take(80)
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> if (c.getInt(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                            birthday = Regex("""^(\d{4}|-)-\d{2}-\d{2}$""").find(v)?.value
                    }
                }
            }
            add(buildJsonObject {
                put("id", wireId("p_", key)); put("name", name.take(120))
                nickname?.let { put("nickname", it) }; org?.let { put("organization", it) }; birthday?.let { put("birthday", it) }
                put("phones", JsonArray(phones)); put("emails", JsonArray(emails))
            })
        }
    }

    // ---- triggers ----

    private var observer: ContentObserver? = null

    /** Calendar edits while the app is alive start a pass (spaced like the others). */
    fun watchCalendar() {
        if (observer != null || !prefs.enabled || !canReadCalendar()) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { sync("calendar") }
        }.also { context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, it) }
    }

    fun summary(): String = when {
        !prefs.enabled -> tr(R.string.phonesync_off)
        error != null -> tr(R.string.phonesync_error, error.orEmpty())
        else -> listOfNotNull(if (prefs.calendar != "off" && canReadCalendar()) tr(R.string.phonesync_calendar) else null, if (prefs.status) tr(R.string.phonesync_status) else null, if (prefs.focus) tr(R.string.phonesync_dnd) else null)
            .joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { tr(R.string.phonesync_nothing) }
    }
}
