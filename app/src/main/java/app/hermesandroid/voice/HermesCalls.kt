package app.hermesandroid.voice

import app.hermesandroid.graph
import kotlinx.coroutines.launch
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.hermesandroid.AppGraph
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// Calls from Hermes: the plugin pushes `call.requested` when a watched job ends, an
// approval or question waits, or Hermes decides to call. The phone rings; answering opens a Gemini Live call
// that starts by saying why; declining or missing it is reported back so the chat's next turn hears it.

@Serializable
data class IncomingCall(
    val id: String, val kind: String? = null, val title: String? = null, val reason: String? = null,
    val sessionIds: List<String> = emptyList(), val profile: String? = null, val dashboard: String? = null, val at: Long = 0,
)

private val json = Json { ignoreUnknownKeys = true }
private val CALL_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

/** HermesCallRequest.parse: the push's `call` object, one line of title and reason, at most four sessions. */
fun parseCall(call: JsonObject?, profile: String?, dashboard: String?, now: Long = System.currentTimeMillis()): IncomingCall? {
    call ?: return null
    val id = call.str("id")?.takeIf { CALL_ID.matches(it) } ?: return null
    fun oneLine(s: String?, max: Int) = s?.replace('"', '\'')?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ")?.takeIf { it.isNotEmpty() }?.take(max)
    return IncomingCall(
        id, call.str("kind")?.takeIf { it in setOf("done", "failed", "stopped", "approval", "question") }, oneLine(call.str("title"), 120), oneLine(call.str("reason"), 200),
        (call["session_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.take(4),
        profile, dashboard, now,
    )
}

fun callHeadline(c: IncomingCall): String = when {
    // Why Hermes calls beats how a job ended ("done" is also every call it asks for with the tool).
    c.reason != null && c.kind !in setOf("approval", "question") -> c.reason
    else -> callKindHeadline(c)
}

private fun callKindHeadline(c: IncomingCall): String = when (c.kind) {
    "done" -> tr(R.string.hermescalls_done, c.title ?: tr(R.string.hermescalls_a_job))
    "failed" -> tr(R.string.hermescalls_failed, c.title ?: tr(R.string.hermescalls_a_job))
    "stopped" -> tr(R.string.hermescalls_stopped, c.title ?: tr(R.string.hermescalls_a_job))
    "approval" -> tr(R.string.hermescalls_approval)
    "question" -> tr(R.string.hermescalls_question)
    else -> c.reason ?: c.title ?: tr(R.string.hermescalls_wants_to_talk)
}

object HermesCalls {
    private const val CHANNEL = "incoming_calls"
    const val EXTRA_CALL = "hermes_call"
    const val NOTIFICATION_ID = 2001
    /** Unanswered this long, a call counts as missed. */
    const val RING_MS = 45_000L

    fun ring(context: Context, call: IncomingCall, callerName: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.hermescalls_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
        })
        val payload = json.encodeToString(IncomingCall.serializer(), call)
        val answer = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_CALL, payload), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(context, 2, Intent(context, app.hermesandroid.push.ActionReceiver::class.java)
            .putExtra("push_kind", "call_decline").putExtra(EXTRA_CALL, payload), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val caller = Person.Builder().setName(callerName).setImportant(true).build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(app.hermesandroid.R.drawable.ic_notification).setColor(context.getColor(app.hermesandroid.R.color.af_ink))
            .setContentTitle(callerName).setContentText(callHeadline(call))
            .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            .setFullScreenIntent(answer, true).setContentIntent(answer)
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setTimeoutAfter(RING_MS)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
        // Android ignores setTimeoutAfter on an ongoing call notification: stop ringing ourselves.
        // ponytail: dies with the process; HermesApp.reportMissedCall still reports it on the next connect.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val graph = context.graph
            if (graph.store.pendingCall != encode(call)) return@postDelayed // answered or declined meanwhile
            dismiss(context)
            graph.store.pendingCall = ""
            graph.scope.launch { report(graph, call, "missed") }
        }, RING_MS)
    }

    fun dismiss(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

    fun decode(raw: String?): IncomingCall? = raw?.let { runCatching { json.decodeFromString(IncomingCall.serializer(), it) }.getOrNull() }
    fun encode(call: IncomingCall) = json.encodeToString(IncomingCall.serializer(), call)

    /** "declined" or "missed", told to the chat's next turn (`/calls/outcomes`). */
    suspend fun report(graph: AppGraph, call: IncomingCall, outcome: String) {
        runCatching {
            graph.client.rest("POST", graph.client.withProfile("/api/plugins/conduit_push/calls/outcomes", call.profile ?: graph.profile), buildJsonObject {
                put("call_id", call.id); put("outcome", outcome)
                put("session_ids", buildJsonArray { call.sessionIds.forEach { add(JsonPrimitive(it)) } })
                call.kind?.let { put("kind", it) }; put("title", call.title.orEmpty()); put("reason", call.reason.orEmpty())
                put("age_s", ((System.currentTimeMillis() - call.at) / 1000).coerceAtLeast(0))
            })
        }
    }

    /** The last thing Hermes said in a call's chat, for Gemini to tell. */
    suspend fun context(graph: AppGraph, call: IncomingCall): String {
        val sid = call.sessionIds.firstOrNull() ?: return ""
        val r = runCatching {
            graph.client.rest("GET", graph.client.withProfile("/api/sessions/${java.net.URLEncoder.encode(sid, "UTF-8")}/messages?limit=8&offset=0&order=latest&inline_images=false", call.profile ?: graph.profile)).obj()
        }.getOrNull()
        val rows = (r?.get("messages") as? JsonArray).orEmpty().mapNotNull { it.obj() }
        return rows.firstOrNull { it.str("role") == "assistant" && !(it.str("content") ?: it.str("text")).isNullOrBlank() }?.let { it.str("content") ?: it.str("text") }.orEmpty().take(3000)
    }
}
