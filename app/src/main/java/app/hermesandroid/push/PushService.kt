package app.hermesandroid.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.graph
import app.hermesandroid.net.Store
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// FCM delivery : the relay sends data-only messages,
// so this service always runs, opens E2E envelopes on the phone and decides what to show.

/** What a push says, after decryption: banner text, where a tap goes, and an answerable decision. */
data class PushContent(
    val type: String, val title: String, val body: String,
    val sessionId: String?, val storedSessionId: String?, val profile: String?, val dashboardId: String?, val gatewayId: String?,
    val decision: JsonObject?, val thread: String?, val call: JsonObject? = null,
    /** A Pulse report, brief or follow-up (`title_label` pulse.*): a tap opens the Pulse page, not its hidden chat. */
    val pulse: Boolean = false,
)

private fun JsonObject?.s(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun parse(s: String?) = s?.let { runCatching { Json.parseToJsonElement(it).obj() }.getOrNull() }

/** FCM data → content: an opened envelope's fields, else the plaintext alert, else the generic copy. */
fun pushContent(data: Map<String, String>, records: List<E2ERecord>, nowS: Long = System.currentTimeMillis() / 1000): PushContent? {
    // "voip" is how the FCM relay sends a call (PushKit on an iPhone): it must ring, not be dropped.
    if (data["push_type"].let { it != null && it != "alert" && it != "voip" }) return null
    val routing = parse(data["conduit"]) ?: parse(data["body"])?.get("conduit").obj()
    val type = routing.s("type") ?: data["type"]?.takeIf { it != "unknown" } ?: return null
    val (gTitle, gBody) = E2E.genericCopy(type)
    val envelope = parse(data["conduit_e2e"])
    if (envelope != null) {
        val opened = E2E.open(envelope, type, records, nowS)
        val c = opened?.content
        return PushContent(
            type, c.s("title") ?: gTitle, c.s("body") ?: gBody, c.s("session_id"), c.s("stored_session_id"), c.s("profile"),
            opened?.record?.dashboardId ?: routing.s("dashboard_id"), opened?.record?.gatewayId ?: routing.s("gateway_id"),
            c?.get("decision").obj()?.takeIf { opened != null }, data["thread_id"], c?.get("call").obj(),
            c.s("title_label")?.startsWith("pulse.") == true,
        )
    }
    val alert = parse(data["aps"])?.get("alert")
    val ao = alert.obj()
    // The top-level `conduit` is routing only; the decision and the call ride in `body.conduit`.
    val rich = parse(data["body"])?.get("conduit").obj() ?: routing
    return PushContent(
        type, ao.s("title") ?: gTitle, ao.s("body") ?: (alert as? JsonPrimitive)?.contentOrNull ?: gBody,
        routing.s("session_id"), routing.s("stored_session_id") ?: routing.s("session_key"), routing.s("profile"), routing.s("dashboard_id"), routing.s("gateway_id"),
        rich?.get("decision").obj(), data["thread_id"], rich?.get("call").obj(),
        routing.s("title_label")?.startsWith("pulse.") == true,
    )
}

private const val CH_ATTENTION = "attention"
internal const val CH_REPLIES = "replies"
private const val CH_OTHER = "other"

fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    val agent = app.hermesandroid.ui.shell.defaultProfileTitle
    nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, tr(R.string.pushservice_ch_attention), NotificationManager.IMPORTANCE_HIGH).apply { description = tr(R.string.pushservice_ch_attention_desc, agent) })
    nm.createNotificationChannel(NotificationChannel(CH_REPLIES, tr(R.string.pushservice_ch_replies), NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr(R.string.pushservice_ch_replies_desc) })
    nm.createNotificationChannel(NotificationChannel(CH_OTHER, tr(R.string.pushservice_ch_other), NotificationManager.IMPORTANCE_DEFAULT).apply { description = tr(R.string.pushservice_ch_other_desc, agent) })
}

/** Every notification starts here: the AllFather glyph, accented (icon circle, app name, actions) in its off-white. */
fun newNotification(context: Context, channel: String): NotificationCompat.Builder =
    NotificationCompat.Builder(context, channel).setSmallIcon(app.hermesandroid.R.drawable.ic_notification)
        .setColor(context.getColor(app.hermesandroid.R.color.af_ink))

private val APPROVAL_LABELS = mapOf("once" to R.string.pushservice_allow, "session" to R.string.pushservice_this_session, "always" to R.string.pushservice_always, "deny" to R.string.pushservice_deny)

fun showPush(context: Context, p: PushContent) {
    ensureChannels(context)
    val id = (p.thread ?: p.sessionId ?: p.type).hashCode()
    val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_SESSION, p.storedSessionId ?: p.sessionId).putExtra(EXTRA_PROFILE, p.profile).putExtra(EXTRA_DASHBOARD, p.dashboardId).putExtra(EXTRA_TITLE, p.title)
        .putExtra(EXTRA_PULSE, p.pulse)
    val channel = when (p.type) { "approval.needed", "input.needed", "call.requested" -> CH_ATTENTION; "response.ready", "background_task.finished" -> CH_REPLIES; else -> CH_OTHER }
    val b = newNotification(context, channel)
        .setContentTitle(p.title).setContentText(p.body).setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
        .setAutoCancel(true).setGroup(p.thread)
        .setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setCategory(if (channel == CH_ATTENTION) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
    fun action(code: Int, label: String, extras: Intent.() -> Unit, input: RemoteInput? = null) {
        val i = Intent(context, ActionReceiver::class.java).putExtra(EXTRA_NOTIFICATION, id).putExtra(EXTRA_PROFILE, p.profile)
            .putExtra(EXTRA_DASHBOARD, p.dashboardId).putExtra(EXTRA_GATEWAY, p.gatewayId).apply(extras)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (input != null) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, id * 8 + code, i, flags)
        b.addAction(NotificationCompat.Action.Builder(0, label, pi).apply { input?.let { addRemoteInput(it) } }.build())
    }
    val d = p.decision
    when (d.s("kind")) {
        "approval" -> {
            val key = d.s("session_key")
            val choices = (d?.get("choices") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (key != null) {
                d.s("description")?.let { b.setStyle(NotificationCompat.BigTextStyle().bigText("${p.body}\n\n$it")) }
                listOf("once", "session", "deny").filter { it in choices }.forEachIndexed { i, ch ->
                    action(i, tr(APPROVAL_LABELS.getValue(ch)), { putExtra(EXTRA_KIND, "approval").putExtra(EXTRA_SESSION_KEY, key).putExtra(EXTRA_ANSWER, ch) })
                }
            }
        }
        "clarify" -> {
            val req = d.s("request_id")
            // A batch of questions needs the app; one question answers from here.
            if (req != null && (d?.get("questions") as? JsonArray).isNullOrEmpty()) {
                d.s("question")?.let { b.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
                val choices = (d?.get("choices") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(2)
                choices.forEachIndexed { i, ch -> action(i, ch, { putExtra(EXTRA_KIND, "clarify").putExtra(EXTRA_REQUEST, req).putExtra(EXTRA_ANSWER, ch) }) }
                action(5, tr(R.string.pushservice_reply), { putExtra(EXTRA_KIND, "clarify").putExtra(EXTRA_REQUEST, req) }, RemoteInput.Builder(KEY_REPLY).setLabel(tr(R.string.pushservice_your_reply)).build())
            }
        }
    }
    runCatching { NotificationManagerCompat.from(context).notify(id, b.build()) }
}

const val EXTRA_SESSION = "push_session"
const val EXTRA_PROFILE = "push_profile"
const val EXTRA_DASHBOARD = "push_dashboard"
const val EXTRA_TITLE = "push_title"
const val EXTRA_PULSE = "push_pulse"
private const val EXTRA_NOTIFICATION = "push_notification"
private const val EXTRA_GATEWAY = "push_gateway"
private const val EXTRA_KIND = "push_kind"
private const val EXTRA_SESSION_KEY = "push_session_key"
private const val EXTRA_REQUEST = "push_request"
private const val EXTRA_ANSWER = "push_answer"
private const val KEY_REPLY = "reply"

class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { applicationContext.graph.push.tokenChanged(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] == "live_activity") {
            Progress.update(applicationContext, Store(applicationContext), data["activity"], data["aps"]?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).obj() }.getOrNull() })
            return
        }
        // "Where's my phone?": answered within the host's 20 s, only for this dashboard's default profile.
        // runBlocking on purpose: the process is only kept alive while onMessageReceived runs (FCM's ~20 s budget);
        // returning early to a coroutine lets Android kill it before the fix is sent. Other pushes wait meanwhile.
        if (data["type"] == "location.requested") {
            val opened = parse(data["conduit_e2e"])?.let { E2E.open(it, "location.requested", loadPush(Store(applicationContext)).records) }
            val locate = opened?.content?.get("locate").obj()?.s("id")?.takeIf { it.startsWith("l_") }
            if (locate != null && (opened?.content.s("profile") ?: "default") == "default")
                kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(18_000) { applicationContext.graph.location.onLocate(locate) } }
            return
        }
        // Allfather queued a change for this phone: a pass picks it up (cards, or auto-add).
        if (data["type"] == "phone.changes") applicationContext.graph.phone.sync("push", force = true)
        data["conduit_token_challenge"]?.takeIf { it.length == 43 && Regex("^[A-Za-z0-9_-]+$").matches(it) }?.let { PushBus.challenge?.complete(it); return }
        val records = loadPush(Store(applicationContext)).records
        val content = pushContent(data, records)
        android.util.Log.i("PushService", "push type=${data["type"]} e2e=${data.containsKey("conduit_e2e")} parsed=${content?.type} call=${content?.call != null}")
        content ?: return
        val graph = applicationContext.graph
        // A scheduled run's result: counted until its job is opened, so it isn't missed when the app was open (no banner).
        app.hermesandroid.tasks.cronJobOf(content.storedSessionId ?: content.sessionId)?.let { job -> graph.scope.launch { graph.tasks.markUnread(job) } }
        // Hermes is calling: ring, unless the user is already in a call (then it's a plain notification).
        val call = if (content.type == "call.requested") app.hermesandroid.voice.parseCall(content.call, content.profile, content.dashboardId) else null
        if (call != null && !graph.liveCall.active) {
            graph.store.pendingCall = app.hermesandroid.voice.HermesCalls.encode(call)
            app.hermesandroid.voice.HermesCalls.ring(applicationContext, call, app.hermesandroid.ui.shell.defaultProfileTitle)
        } else if (!AppVisibility.foreground || content.type == "call.requested") {
            // A call that can't ring still gets its notification, even with the app open.
            if (content.type == "call.requested") android.util.Log.w("PushService", "call didn't ring: parsed=${call != null} inCall=${graph.liveCall.active}")
            showPush(applicationContext, content)
        }
    }
}

/** Notification buttons: approve over the dashboard RPC, answer a clarify through the relay. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val graph = context.graph
        val id = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
        val answer = reply ?: intent.getStringExtra(EXTRA_ANSWER).orEmpty()
        CoroutineScope(Dispatchers.Main).launch {
            val outcome = runCatching {
                when (intent.getStringExtra(EXTRA_KIND)) {
                    "approval" -> {
                        val dash = intent.getStringExtra(EXTRA_DASHBOARD)
                        val here = dash == null || dash == graph.store.activeId || dash == dashboardUuid(graph.store.activeId)
                        if (!graph.signedIn || !here) return@runCatching tr(R.string.pushservice_open_to_reply)
                        withTimeout(20_000) { graph.client.connected.first { it } }
                        val profile = intent.getStringExtra(EXTRA_PROFILE)
                        graph.client.call("approval.respond", buildJsonObject {
                            put("choice", answer); put("session_id", intent.getStringExtra(EXTRA_SESSION_KEY).orEmpty())
                            if (!profile.isNullOrEmpty() && profile != "default") put("profile", profile)
                        }, scoped = false)
                        tr(if (answer == "deny") R.string.pushservice_denied else R.string.pushservice_approved)
                    }
                    "call_decline" -> {
                        app.hermesandroid.voice.HermesCalls.dismiss(context)
                        graph.store.pendingCall = ""
                        app.hermesandroid.voice.HermesCalls.decode(intent.getStringExtra(app.hermesandroid.voice.HermesCalls.EXTRA_CALL))?.let { app.hermesandroid.voice.HermesCalls.report(graph, it, "declined") }
                        null
                    }
                    "clarify" -> if (answer.isBlank()) tr(R.string.pushservice_empty_reply) else
                        graph.push.respondDecision(intent.getStringExtra(EXTRA_REQUEST).orEmpty(), answer, null, intent.getStringExtra(EXTRA_GATEWAY)) ?: tr(R.string.pushservice_replied, answer)
                    else -> null
                }
            }.getOrElse { tr(R.string.pushservice_send_failed, it.message.orEmpty()) }
            if (outcome != null) {
                ensureChannels(context)
                runCatching {
                    NotificationManagerCompat.from(context).notify(id, newNotification(context, CH_OTHER).setContentTitle("AllFather").setContentText(outcome).setTimeoutAfter(8000).setAutoCancel(true).build())
                }
            }
            pending.finish()
        }
    }
}
