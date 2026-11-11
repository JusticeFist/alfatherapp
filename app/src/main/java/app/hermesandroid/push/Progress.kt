package app.hermesandroid.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.trPlural
import app.hermesandroid.net.Store
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import java.security.SecureRandom
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Whether an app screen is showing: notifications for things the app shows itself are skipped then. */
object AppVisibility {
    @Volatile var foreground = false
}

// Ongoing progress while the agent works (iOS's Live Activity): the phone registers a
// random id for the chat's turn with the relay; the plugin reports the phase, the tool *category*, how many tools
// ran and how full the context is; the relay forwards each update as an FCM `live_activity` message.

/** One turn being followed: which chat it is and since when. */
@Serializable
data class Activity(val id: String, val title: String, val storedId: String?, val profile: String?, val dashboard: String?, val startedAt: Long, val updatedAt: Long = 0)

/** No update for this long and the turn is stale (the relay's STALE_AFTER_S): a turn cut off (dashboard restart) never sends its end. */
const val PROGRESS_STALE_MS = 15 * 60_000L
/** The relay forgets a registration after this (ACTIVITY_TTL_MS). */
private const val PROGRESS_TTL_MS = 8 * 3600_000L

fun progressStale(a: Activity, now: Long) = now - maxOf(a.startedAt, a.updatedAt) >= PROGRESS_STALE_MS

/** The relay's `content-state`: phase thinking | tool | approval | input, or done | failed | stopped at the end. */
data class ProgressState(val phase: String, val tools: Int, val tool: String?, val context: Int?, val ended: Boolean)

fun parseProgress(aps: JsonObject?): ProgressState? {
    val cs = aps?.get("content-state").obj() ?: return null
    val phase = cs.str("phase") ?: return null
    return ProgressState(phase, (cs["tools"] as? JsonPrimitive)?.intOrNull ?: 0, cs.str("tool"), (cs["context"] as? JsonPrimitive)?.intOrNull, aps?.str("event") == "end")
}

private val TOOL_WORDS = mapOf(
    "terminal" to R.string.progress_tool_terminal, "code" to R.string.progress_tool_code, "files" to R.string.progress_tool_files,
    "web" to R.string.progress_tool_web, "browser" to R.string.progress_tool_browser, "agents" to R.string.progress_tool_agents,
    "memory" to R.string.progress_tool_memory, "images" to R.string.progress_tool_images, "audio" to R.string.progress_tool_audio,
    "planning" to R.string.progress_tool_planning, "schedule" to R.string.progress_tool_schedule, "messages" to R.string.progress_tool_messages,
    "skills" to R.string.progress_tool_skills, "connector" to R.string.progress_tool_connector, "phone" to R.string.progress_tool_phone,
    "home" to R.string.progress_tool_home,
)

/** The notification's line for a state ("Using the terminal · 3 tools · context 41 %"). */
fun progressText(s: ProgressState): String {
    val what = when (s.phase) {
        "tool" -> tr(R.string.progress_using, tr(TOOL_WORDS[s.tool] ?: R.string.progress_tool_tools))
        "approval" -> tr(R.string.progress_approval)
        "input" -> tr(R.string.progress_input)
        "done" -> tr(R.string.progress_done)
        "failed" -> tr(R.string.progress_failed)
        "stopped" -> tr(R.string.progress_stopped)
        else -> tr(R.string.progress_thinking)
    }
    return listOfNotNull(what, s.tools.takeIf { it > 0 }?.let { trPlural(R.plurals.progress_tools, it, it) }, s.context?.let { tr(R.string.progress_context, it) }).joinToString(" · ")
}

object Progress {
    private const val CHANNEL = "progress"
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    private fun load(store: Store): List<Activity> = runCatching { json.decodeFromString<List<Activity>>(store.activities) }.getOrDefault(emptyList())
        .filter { System.currentTimeMillis() - it.startedAt < PROGRESS_TTL_MS }
    private fun save(store: Store, list: List<Activity>) { store.activities = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Activity.serializer()), list) }
    private fun notificationId(a: Activity) = 3000 + (a.id.hashCode() and 0xfff)
    /** The latest state of each followed turn, shown when the user leaves the app. */
    private val last = java.util.concurrent.ConcurrentHashMap<String, ProgressState>()

    /** The app went to the background: turns still running appear in the shade (stale ones don't come back). */
    fun appHidden(context: Context, store: Store) {
        val now = System.currentTimeMillis()
        load(store).forEach { a ->
            if (progressStale(a, now)) NotificationManagerCompat.from(context).cancel(notificationId(a))
            else show(context, a, last[a.id] ?: ProgressState("thinking", 0, null, null, false))
        }
    }

    /** The user is back in the app: it shows the progress itself. */
    fun appShown(context: Context, store: Store) {
        load(store).forEach { NotificationManagerCompat.from(context).cancel(notificationId(it)) }
    }

    /** Starts following a turn: registers it with the relay and shows "Thinking…". */
    suspend fun begin(context: Context, state: PushState, sessionIds: List<String>, title: String, storedId: String?, profile: String?, dashboard: String?) {
        if (!state.registered || sessionIds.isEmpty()) return
        val store = state.store
        // One activity per chat: a new turn replaces the last one's.
        load(store).filter { it.storedId == storedId }.forEach { end(context, state, it.id) }
        val id = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val ok = state.registerActivity(id, sessionIds.distinct().take(6))
        if (!ok) return
        val a = Activity(id, title, storedId, profile, dashboard, System.currentTimeMillis())
        save(store, (load(store) + a).takeLast(6))
        show(context, a, ProgressState("thinking", 0, null, null, false))
    }

    private fun show(context: Context, a: Activity, s: ProgressState) {
        last[a.id] = s
        if (!AppVisibility.foreground) post(context, a, s)
    }

    /** An FCM `live_activity` message. */
    fun update(context: Context, store: Store, activityId: String?, aps: JsonObject?) {
        val a = load(store).firstOrNull { it.id == activityId } ?: return
        val s = parseProgress(aps) ?: return
        if (s.ended) {
            NotificationManagerCompat.from(context).cancel(notificationId(a))
            last.remove(a.id)
            save(store, load(store).filterNot { it.id == a.id })
            return
        }
        save(store, load(store).map { if (it.id == a.id) it.copy(updatedAt = System.currentTimeMillis()) else it })
        show(context, a, s)
    }

    /** The turn finished (seen live in the app) or the chat moved on: drop its notification and registration. */
    suspend fun endFor(context: Context, state: PushState, storedId: String?) {
        load(state.store).filter { it.storedId == storedId }.forEach { end(context, state, it.id) }
    }

    private suspend fun end(context: Context, state: PushState, id: String) {
        val a = load(state.store).firstOrNull { it.id == id } ?: return
        NotificationManagerCompat.from(context).cancel(notificationId(a))
        save(state.store, load(state.store).filterNot { it.id == id })
        state.unregisterActivity(id)
    }

    private fun post(context: Context, a: Activity, s: ProgressState) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.progress_channel), NotificationManager.IMPORTANCE_LOW).apply { description = tr(R.string.progress_channel_desc) })
        val open = PendingIntent.getActivity(context, notificationId(a), Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SESSION, a.storedId).putExtra(EXTRA_PROFILE, a.profile).putExtra(EXTRA_DASHBOARD, a.dashboard).putExtra(EXTRA_TITLE, a.title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val waiting = s.phase == "approval" || s.phase == "input"
        val n = newNotification(context, CHANNEL)
            .setContentTitle(a.title).setContentText(progressText(s))
            .setOngoing(!waiting).setOnlyAlertOnce(true).setSilent(true)
            .setTimeoutAfter(PROGRESS_STALE_MS) // each update re-posts it; silence means the turn is gone
            .setWhen(a.startedAt).setUsesChronometer(true).setShowWhen(true)
            .setProgress(0, 0, !waiting)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(a), n) }
    }

    /** Relay body for a registration (`push_token` is the activity id). */
    fun body(id: String, sessionIds: List<String>?) = buildJsonObject {
        put("push_token", id)
        sessionIds?.let { put("session_ids", buildJsonArray { it.forEach { s -> add(JsonPrimitive(s)) } }) }
    }
}
