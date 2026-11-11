package app.hermesandroid.phone

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.compose.runtime.mutableStateListOf
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.share.KeyLost
import app.hermesandroid.share.ShareCrypto
import app.hermesandroid.share.ShareLink
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Changes Allfather asks for on the phone: `/phone/exchange` reports what
// happened to earlier changes and returns the open ones. An addition the user lets through ("agregar sin
// preguntar") is applied at once; everything else waits on a card in the chat.

/** A shared occurrence on this phone: its event row, start, and whether it repeats. */
data class EventRef(val eventId: Long, val begin: Long, val recurring: Boolean, val allDay: Boolean)

/** One change from the host (`changes[]`). */
data class PhoneChange(val id: String, val kind: String, val reason: String?, val auto: Boolean, val target: JsonObject?, val fields: JsonObject)

/** An alarm or timer this app set in the Clock app (the only ones Android lets it name and cancel). */
@Serializable
data class SetAlarm(val id: String, val kind: String, val label: String, val firesAt: Long, val repeats: List<String> = emptyList())

@Serializable
private data class ChangesState(
    /** Outcomes not yet told to the host: change id → result JSON. */
    val unreported: Map<String, String> = emptyMap(),
    /** Changes already answered, so a lost report never applies one twice. */
    val answered: List<String> = emptyList(),
    val alarms: List<SetAlarm> = emptyList(),
)

class PhoneChanges(private val graph: AppGraph, private val context: Context, private val sync: PhoneSync) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state = runCatching { json.decodeFromString<ChangesState>(graph.store.share("phone-changes")) }.getOrDefault(ChangesState())
    private fun save(f: (ChangesState) -> ChangesState) { state = f(state); graph.store.setShare("phone-changes", json.encodeToString(ChangesState.serializer(), state)) }

    /** Changes waiting on the user, shown as cards in the chat. */
    val open = mutableStateListOf<PhoneChange>()

    fun hasUnreported() = state.unreported.isNotEmpty()

    /** Reports outcomes and takes the open changes (called from inside a sync pass). */
    suspend fun exchange() {
        val link = sync.link
        val key = link.state.key ?: throw KeyLost()
        val callId = ShareLink.batchId()
        val req = ShareCrypto.seal(key.key("exchange device-to-host")!!,
            ShareCrypto.aad(link.ch, "exchange-request", "op" to "changes", "kid" to key.kid, "dev" to link.deviceId, "call" to callId),
            buildJsonObject {
                put("call_id", callId)
                put("results", JsonArray(state.unreported.values.take(50).map { Json.parseToJsonElement(it) }))
            }.toString())
        val reported = state.unreported.keys.take(50)
        val r = graph.client.rest("POST", graph.client.withProfile("/api/plugins/conduit_push/phone/exchange", "default"), buildJsonObject {
            put("v", 1); put("device_id", link.deviceId); put("kid", key.kid); put("call_id", callId); put("op", "changes"); put("nonce", req.nonce); put("ct", req.ct)
        }).obj() ?: return
        save { s -> s.copy(unreported = s.unreported - reported.toSet()) }
        val answer = ShareCrypto.open(key.key("exchange host-to-device")!!,
            ShareCrypto.aad(link.ch, "exchange-answer", "op" to "changes", "kid" to key.kid, "dev" to link.deviceId, "call" to callId),
            r.str("nonce") ?: return, r.str("ct") ?: return)?.let { Json.parseToJsonElement(it).obj() } ?: return
        val changes = (answer["changes"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { c ->
            val id = c.str("id") ?: return@mapNotNull null
            PhoneChange(id, c.str("kind").orEmpty(), c.str("reason"), c.bool("auto") == true, c["target"].obj(), c["fields"].obj() ?: JsonObject(emptyMap()))
        }.filter { it.id !in state.answered }
        open.clear()
        for (c in changes) {
            refusal(c)?.let { note -> finish(c, "failed", note = note); continue }
            if (c.auto && sync.prefs.autoAdd && c.kind in setOf("add_event", "set_alarm", "set_timer")) apply(c) else open += c
        }
        if (hasUnreported()) exchangeLater()
    }

    /** Outcomes go back on the next pass. */
    private fun exchangeLater() = sync.sync("results", force = true)

    /** Why this phone can't take the change at all, or null. */
    private fun refusal(c: PhoneChange): String? = when (c.kind) {
        "add_event", "update_event", "delete_event" ->
            if (sync.prefs.calendar != "full") "The user shares the calendar read-only." else if (!sync.canWriteCalendar()) "Missing the permission to write to the calendar." else null
        "set_alarm", "set_timer", "cancel_alarm" -> if (!sync.prefs.alarms) "The user doesn't allow setting alarms." else null
        else -> "Android has no Reminders; use an event or an alarm."
    }

    /** The user approved (or auto-add): do it on the phone and report. */
    fun approve(c: PhoneChange) = graph.scope.launch { apply(c); exchangeLater() }

    fun dismiss(c: PhoneChange) { finish(c, "dismissed"); exchangeLater() }

    private fun apply(c: PhoneChange) {
        val result = runCatching {
            when (c.kind) {
                "add_event" -> addEvent(c.fields)
                "update_event" -> updateEvent(c.target, c.fields)
                "delete_event" -> deleteEvent(c.target)
                "set_alarm" -> setAlarm(c.fields)
                "set_timer" -> setTimer(c.fields)
                "cancel_alarm" -> cancelAlarm(c.target)
                else -> throw IllegalStateException("Not possible on Android.")
            }
        }
        // The next pass (exchangeLater) carries the calendar change and this outcome to the host.
        result.onSuccess { finish(c, "applied", itemId = it) }.onFailure { finish(c, "failed", note = it.message) }
    }

    private fun finish(c: PhoneChange, outcome: String, itemId: String? = null, note: String? = null) {
        open.removeAll { it.id == c.id }
        val res = buildJsonObject {
            put("id", c.id); put("outcome", outcome)
            itemId?.let { put("item_id", it) }; note?.let { put("note", it.take(200)) }
        }
        save { s -> s.copy(unreported = s.unreported + (c.id to res.toString()), answered = (s.answered + c.id).takeLast(200)) }
    }

    // ---- calendar ----

    private fun time(s: String?): Long? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** An all-day day as Android stores it: UTC midnight. */
    private fun utcDay(s: String): Long = LocalDate.parse(s.take(10)).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun calendarFor(wire: String?): Long {
        wire?.let { w -> sync.calendarIndex[w]?.let { return it } }
        // The default: the primary calendar this phone can write to.
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        throw IllegalStateException("There is no calendar to write to.")
    }

    private fun eventValues(f: JsonObject, values: ContentValues, partial: Boolean) {
        fun has(k: String) = f.containsKey(k)
        if (has("title")) values.put(CalendarContract.Events.TITLE, f.str("title").orEmpty().take(200))
        val allDay = f.bool("all_day") == true
        if (has("start") || !partial) {
            val start = f.str("start") ?: throw IllegalStateException("Missing the start time.")
            if (allDay) {
                val s = utcDay(start)
                values.put(CalendarContract.Events.ALL_DAY, 1); values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                values.put(CalendarContract.Events.DTSTART, s)
                values.put(CalendarContract.Events.DTEND, f.str("end")?.let { e -> maxOf(utcDay(e), s + 86_400_000L) } ?: (s + 86_400_000L))
            } else {
                val s = time(start) ?: throw IllegalStateException("Invalid start time.")
                values.put(CalendarContract.Events.ALL_DAY, 0); values.put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                values.put(CalendarContract.Events.DTSTART, s)
                values.put(CalendarContract.Events.DTEND, time(f.str("end")) ?: (s + 3_600_000L))
            }
        }
        if (has("location")) values.put(CalendarContract.Events.EVENT_LOCATION, f.str("location"))
        if (has("notes") || has("url")) values.put(CalendarContract.Events.DESCRIPTION, listOfNotNull(f.str("notes"), f.str("url")).joinToString("\n").ifEmpty { null })
    }

    private fun addEvent(f: JsonObject): String {
        val values = ContentValues().apply { put(CalendarContract.Events.CALENDAR_ID, calendarFor(f.str("calendar_id"))) }
        eventValues(f, values, partial = false)
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: throw IllegalStateException("The calendar didn't accept the event.")
        val eventId = ContentUris.parseId(uri)
        setAlarms(eventId, f["alarms_min"] as? JsonArray)
        return wireId("e_", eventId, values.getAsLong(CalendarContract.Events.DTSTART))
    }

    private fun setAlarms(eventId: Long, minutes: JsonArray?) {
        minutes ?: return
        context.contentResolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        minutes.take(3).mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.forEach { m ->
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId); put(CalendarContract.Reminders.MINUTES, m); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
    }

    private fun ref(target: JsonObject?): EventRef =
        target?.str("event_id")?.let { sync.eventIndex[it] } ?: throw IllegalStateException("That event is no longer on the phone.")

    private fun updateEvent(target: JsonObject?, f: JsonObject): String {
        val e = ref(target)
        // ponytail: a single occurrence of a repeating event would need an exception row; edit only one-off events.
        if (e.recurring) throw IllegalStateException("It's a repeating event; edit it in the calendar.")
        val values = ContentValues()
        eventValues(f, values, partial = true)
        if (values.size() > 0) context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId), values, null, null)
        if (f.containsKey("alarms_min")) setAlarms(e.eventId, f["alarms_min"] as? JsonArray)
        return wireId("e_", e.eventId, values.getAsLong(CalendarContract.Events.DTSTART) ?: e.begin)
    }

    private fun deleteEvent(target: JsonObject?): String? {
        val e = ref(target)
        if (!e.recurring) {
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId), null, null)
        } else {
            if (target?.str("span") == "future") throw IllegalStateException("Deleting all future repeats is done in the calendar.")
            // One occurrence of a repeating event: a canceled exception for its start.
            context.contentResolver.insert(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_EXCEPTION_URI, e.eventId), ContentValues().apply {
                put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, e.begin); put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            })
        }
        return null
    }

    // ---- alarms and timers (Clock app) ----

    private fun clock(intent: Intent) {
        context.startActivity(intent.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    private fun setAlarm(f: JsonObject): String {
        val label = f.str("label")?.take(80) ?: app.hermesandroid.ui.shell.defaultProfileTitle
        val repeats = (f["repeats"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it in days }
        val zone = ZoneId.systemDefault()
        val at: ZonedDateTime = time(f.str("at"))?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
            ?: f.str("time")?.let { t -> LocalTime.parse(t).let { lt -> ZonedDateTime.now(zone).with(lt).let { if (it.isBefore(ZonedDateTime.now(zone))) it.plusDays(1) else it } } }
            ?: throw IllegalStateException("Missing the alarm time.")
        clock(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, at.hour).putExtra(AlarmClock.EXTRA_MINUTES, at.minute).putExtra(AlarmClock.EXTRA_MESSAGE, label).apply {
            // Calendar.SUNDAY = 1 … SATURDAY = 7
            if (repeats.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(repeats.map { (days.indexOf(it) + 1) % 7 + 1 }))
        })
        val id = wireId("a_", label, at.toInstant().toEpochMilli())
        save { s -> s.copy(alarms = (s.alarms + SetAlarm(id, "alarm", label, at.toInstant().toEpochMilli(), repeats)).takeLast(50)) }
        return id
    }

    private fun setTimer(f: JsonObject): String {
        val seconds = f.num("seconds")?.toInt()?.coerceIn(5, 86_400) ?: throw IllegalStateException("Missing the duration.")
        val label = f.str("label")?.take(80) ?: app.hermesandroid.ui.shell.defaultProfileTitle
        clock(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label))
        val firesAt = System.currentTimeMillis() + seconds * 1000L
        val id = wireId("t_", label, firesAt)
        save { s -> s.copy(alarms = (s.alarms + SetAlarm(id, "timer", label, firesAt)).takeLast(50)) }
        return id
    }

    private fun cancelAlarm(target: JsonObject?): String? {
        val a = state.alarms.firstOrNull { it.id == target?.str("alarm_id") } ?: throw IllegalStateException("That alarm is gone.")
        if (a.kind == "alarm") clock(Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL).putExtra(AlarmClock.EXTRA_MESSAGE, a.label))
        save { s -> s.copy(alarms = s.alarms.filterNot { it.id == a.id }) }
        return null
    }

    /** `alarms[]`: the ones this app set that haven't passed (a repeating alarm stays). */
    fun alarmsWire(): JsonArray {
        val now = System.currentTimeMillis()
        val live = state.alarms.filter { it.repeats.isNotEmpty() || it.firesAt > now }
        if (live.size != state.alarms.size) save { s -> s.copy(alarms = live) }
        return buildJsonArray {
            live.forEach { a ->
                add(buildJsonObject {
                    put("id", a.id); put("kind", a.kind); put("label", a.label); put("fires_at", iso(a.firesAt))
                    if (a.repeats.isNotEmpty()) putJsonArray("repeats") { a.repeats.forEach { add(it) } }
                    put("state", if (a.kind == "timer") "countdown" else "scheduled")
                    if (a.kind == "timer") put("remaining_s", ((a.firesAt - now) / 1000).coerceAtLeast(0))
                })
            }
        }
    }

    /** One line for a card: what Allfather wants to do. */
    fun describe(c: PhoneChange): String {
        val f = c.fields
        val title = f.str("title") ?: f.str("label")
        return when (c.kind) {
            "add_event" -> tr(R.string.phonechanges_add, title ?: tr(R.string.phonechanges_event), f.str("start")?.let(::humanTime) ?: "")
            "update_event" -> tr(R.string.phonechanges_update, title ?: tr(R.string.phonechanges_an_event)) + (f.str("start")?.let { " · ${humanTime(it)}" } ?: "")
            "delete_event" -> tr(R.string.phonechanges_delete, title ?: tr(R.string.phonechanges_an_event)) + (f.str("start")?.let { " · ${humanTime(it)}" } ?: "")
            "set_alarm" -> tr(R.string.phonechanges_alarm, title ?: app.hermesandroid.ui.shell.defaultProfileTitle, f.str("time") ?: f.str("at")?.let(::humanTime) ?: "")
            "set_timer" -> tr(R.string.phonechanges_timer, (f.num("seconds") ?: 0.0).toInt().let { s -> if (s >= 60) "${s / 60} min" else "$s s" }) + (title?.let { " · $it" } ?: "")
            "cancel_alarm" -> tr(R.string.phonechanges_cancel_alarm, title ?: "")
            else -> c.kind
        }
    }

    private fun humanTime(s: String): String = runCatching {
        if (s.length == 10) java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(utcDay(s) + 12 * 3_600_000L))
        else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(time(s)!!))
    }.getOrDefault(s)
}
