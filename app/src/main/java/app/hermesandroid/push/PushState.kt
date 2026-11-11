package app.hermesandroid.push

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.BuildConfig
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.HttpError
import app.hermesandroid.net.bool
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Notifications through the user's push relay (`relay/` in this repo, with FCM): register this phone's FCM token, prove it holds the token, pair each dashboard
// profile with the relay through the host plugin, and hand the plugin an E2E key per gateway.

/** Notification preferences, as the relay stores them. */
@Serializable
data class PushPrefs(
    val enabled: Boolean = true,
    @SerialName("approval_needed") val approvalNeeded: Boolean = true,
    @SerialName("input_needed") val inputNeeded: Boolean = true,
    @SerialName("response_ready") val responseReady: Boolean = true,
    @SerialName("turn_failed") val turnFailed: Boolean = true,
    @SerialName("background_task_finished") val backgroundTaskFinished: Boolean = true,
    @SerialName("completion_sound") val completionSound: Boolean = true,
    @SerialName("attention_sound") val attentionSound: Boolean = true,
    /** Plaintext pushes (no E2E key yet) carry message text only with this on. */
    @SerialName("show_previews") val showPreviews: Boolean = false,
    @SerialName("decision_cards") val decisionCards: Boolean = true,
)

@Serializable
data class PushData(
    val relayUrl: String = "",
    val installationId: String? = null,
    val credential: String? = null,
    val deviceToken: String? = null,
    val prefs: PushPrefs = PushPrefs(),
    val records: List<E2ERecord> = emptyList(),
    /** Gateway id of each paired "dashboardId|profile". */
    val gateways: Map<String, String> = emptyMap(),
)

/** The relay wants a UUID per dashboard; ours are short ids, so derive a stable one. */
fun dashboardUuid(id: String): String = java.util.UUID.nameUUIDFromBytes("hermes-dashboard:$id".toByteArray()).toString()

/** Token challenges the relay pushes back, handed from PushService to the waiting registration. */
object PushBus {
    @Volatile var challenge: CompletableDeferred<String>? = null
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Reads the saved push state without the app graph (the messaging service may run alone). */
fun loadPush(store: app.hermesandroid.net.Store): PushData = runCatching { json.decodeFromString<PushData>(store.push) }.getOrDefault(PushData())

class PushState(private val graph: AppGraph, private val context: Context) {
    var data by mutableStateOf(loadPush(graph.store))
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    /** This profile's pairing on the active dashboard: "this", "other" (another phone) or null. */
    var pairing by mutableStateOf<String?>(null)
        private set
    var e2e by mutableStateOf(false)
        private set
    private val lock = Mutex()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    val configured get() = BuildConfig.FCM_APP_ID.isNotEmpty()
    val store get() = graph.store

    /** `PUT /v1/installations/{id}/live-activities`: the relay forwards this turn's progress to us. */
    suspend fun registerActivity(id: String, sessionIds: List<String>): Boolean {
        val inst = data.installationId ?: return false
        return runCatching { relay("PUT", "/v1/installations/$inst/live-activities", Progress.body(id, sessionIds), auth = true).first in 200..299 }.getOrDefault(false)
    }

    suspend fun unregisterActivity(id: String) {
        val inst = data.installationId ?: return
        runCatching { relay("DELETE", "/v1/installations/$inst/live-activities", Progress.body(id, null), auth = true) }
    }
    val registered get() = data.installationId != null

    private fun save(d: PushData) { data = d; graph.store.push = json.encodeToString(PushData.serializer(), d) }

    /** Default: a relay on the dashboard's host, port 8787 (the relay's LAN setup). */
    fun defaultRelay(): String = runCatching { java.net.URI(graph.store.baseUrl).let { "http://${it.host}:8787" } }.getOrDefault("")
    val relayUrl get() = data.relayUrl.ifBlank { defaultRelay() }

    /** The URL the plugin uses: loopback when the relay runs on the dashboard's own host (it sends credentials over http only to loopback). */
    private fun pluginRelayUrl(): String {
        val relay = java.net.URI(relayUrl)
        val dash = runCatching { java.net.URI(graph.store.baseUrl).host }.getOrNull()
        return if (relay.scheme == "http" && relay.host == dash) "http://127.0.0.1:${if (relay.port > 0) relay.port else 80}" else relayUrl.trimEnd('/')
    }

    fun setRelayUrl(url: String) = save(data.copy(relayUrl = url.trim().trimEnd('/')))

    private suspend fun relay(method: String, path: String, body: JsonObject? = null, auth: Boolean = false): Pair<Int, JsonObject?> = withContext(Dispatchers.IO) {
        val url = relayUrl.trimEnd('/') + path
        val u = java.net.URI(url)
        // Credentials only over https, or plain http to a LAN/loopback relay (RelayTransportPolicy).
        if (auth && u.scheme != "https" && !isLocal(u.host)) throw HttpError(400, tr(R.string.pushstate_relay_https))
        val req = Request.Builder().url(url).method(method, body?.toString()?.toRequestBody("application/json".toMediaType()) ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null)
        if (auth) data.credential?.let { req.header("Authorization", "Bearer $it") }
        http.newCall(req.build()).execute().use { r -> r.code to runCatching { Json.parseToJsonElement(r.body.string()).obj() }.getOrNull() }
    }

    private fun isLocal(host: String?) = host != null && (host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) || host.startsWith("100.") || host.endsWith(".ts.net") || host.endsWith(".local"))

    private fun relayError(code: Int, o: JsonObject?): HttpError = HttpError(code, when (o?.str("error")) {
        "unauthorized" -> tr(R.string.pushstate_unknown_phone)
        "fcm_unavailable" -> tr(R.string.pushstate_no_firebase)
        "invalid_device_token", "token_proof_required", "token_proof_invalid" -> tr(R.string.pushstate_token_check)
        null -> tr(R.string.pushstate_relay_code, code)
        else -> tr(R.string.pushstate_relay_error, o.str("error").orEmpty())
    })

    private suspend fun fcmToken(): String {
        ensureFirebase(context)
        return suspendCancellableCoroutine { c ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> if (t.isSuccessful) c.resume(t.result) else c.resumeWithException(t.exception ?: IllegalStateException(tr(R.string.pushstate_no_token))) }
        }
    }

    /** POST /v1/token-challenges, then wait for the nonce the relay pushes to this token. */
    private suspend fun proveToken(token: String): String {
        val waiting = CompletableDeferred<String>().also { PushBus.challenge = it }
        try {
            val (code, o) = relay("POST", "/v1/token-challenges", buildJsonObject { put("platform", "fcm"); put("device_token", token) })
            if (code !in 200..299) throw relayError(code, o)
            return withTimeout(20_000) { waiting.await() }
        } finally {
            PushBus.challenge = null
        }
    }

    private fun prefsJson() = json.encodeToJsonElement(PushPrefs.serializer(), data.prefs)

    suspend fun enable() = op {
        val token = fcmToken()
        if (registered) { update(token); return@op }
        fun body(proof: String?) = buildJsonObject {
            put("platform", "fcm"); put("device_token", token); put("preferences", prefsJson())
            proof?.let { put("token_proof", it) }
        }
        var (code, o) = relay("POST", "/v1/installations", body(null))
        if (code == 403 && o?.str("error") == "token_proof_required") { val r = relay("POST", "/v1/installations", body(proveToken(token))); code = r.first; o = r.second }
        if (code !in 200..299) throw relayError(code, o)
        val inst = o?.get("installation").obj()
        save(data.copy(installationId = inst?.str("id"), credential = o?.str("credential"), deviceToken = token))
        message = tr(R.string.pushstate_enabled)
        syncAll()
    }

    /** PUT the token (when it moved) and preferences. */
    private suspend fun update(token: String? = data.deviceToken) {
        val id = data.installationId ?: return
        val moving = token != null && token != data.deviceToken
        fun body(proof: String?) = buildJsonObject {
            if (moving) put("device_token", token)
            put("preferences", prefsJson())
            proof?.let { put("token_proof", it) }
        }
        var (code, o) = relay("PUT", "/v1/installations/$id", body(null), auth = true)
        if (code == 403 && o?.str("error") == "token_proof_required" && token != null) { val r = relay("PUT", "/v1/installations/$id", body(proveToken(token)), auth = true); code = r.first; o = r.second }
        if (code !in 200..299) throw relayError(code, o)
        if (moving) save(data.copy(deviceToken = token))
    }

    suspend fun setPrefs(p: PushPrefs) = op { save(data.copy(prefs = p)); update() }

    /** A new FCM token (PushService.onNewToken). */
    suspend fun tokenChanged(token: String) { if (registered) runCatching { lock.withLock { update(token) } } }

    suspend fun disable() = op {
        data.installationId?.let { id -> runCatching { relay("DELETE", "/v1/installations/$id", auth = true) } }
        save(PushData(relayUrl = data.relayUrl, prefs = data.prefs))
        pairing = null; e2e = false; paired = 0 to 0
        runCatching { ensureFirebase(context); FirebaseMessaging.getInstance().deleteToken() }
        message = tr(R.string.pushstate_disabled)
    }

    private suspend fun op(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        try { lock.withLock { block() } } catch (e: Exception) { message = e.message ?: tr(R.string.pushstate_error) } finally { busy = false }
    }

    /** After each connect: pair every profile nothing receives yet (the helper bots' own crons notify too), and make sure each gateway holds this phone's E2E key. */
    suspend fun sync() { if (registered && graph.hostPlugin.reports("pairing")) runCatching { lock.withLock { syncAll() } }.onFailure { message = it.message } }

    suspend fun pairNow() = op { syncLocked(graph.profile, force = true).let { (p, sealed) -> pairing = p; e2e = sealed } }

    /** Profiles paired with this phone, of all the dashboard's (Settings > Notifications). */
    var paired by mutableStateOf(0 to 0)
        private set

    /** One profile failing (a timeout, the relay's 5 new pairings a minute) doesn't stop the others, and the next connect
     *  pairs what's left; only the active one's error is shown. */
    private suspend fun syncAll() {
        val all = listOf(graph.profile) + graph.profiles.filter { it != graph.profile }
        var mine = 0
        var failure: Throwable? = null
        for (p in all) {
            val r = runCatching { syncLocked(p) }
            if (r.getOrNull()?.first == "this") mine++
            if (p == graph.profile) r.onSuccess { (state, sealed) -> pairing = state; e2e = sealed }.onFailure { failure = it }
        }
        paired = mine to all.size
        failure?.let { throw it }
    }

    /** [profile]'s pairing ("this", "other" or null) and whether its pushes come sealed. */
    private suspend fun syncLocked(profile: String, force: Boolean = false): Pair<String?, Boolean> {
        val id = data.installationId ?: return null to false
        val e2ePath = graph.client.withProfile("/api/plugins/conduit_push/e2e", profile)
        var status = graph.client.rest("GET", e2ePath).obj()
        val paired = status?.bool("paired") == true
        val mine = status?.str("installation_id") == id
        var pairing = if (!paired) null else if (mine) "this" else "other"
        // Another phone's pairing is replaced only when asked.
        if (!paired || (!mine && force)) {
            val (code, o) = relay("POST", "/v1/installations/$id/pairings", buildJsonObject { put("dashboard_id", dashboardUuid(graph.store.activeId)) }, auth = true)
            if (code !in 200..299) throw relayError(code, o)
            val r = graph.client.rest("POST", graph.client.withProfile("/api/plugins/conduit_push/pair", profile),
                buildJsonObject { put("pairing_code", o?.str("pairing_code").orEmpty()); put("relay_url", pluginRelayUrl()) }, timeoutSec = 25).obj()
            if (r?.bool("ok") != true || r.str("installation_id") != id) throw HttpError(400, tr(R.string.pushstate_pair_unconfirmed))
            pairing = "this"
            status = graph.client.rest("GET", e2ePath).obj()
        }
        if (pairing != "this") return pairing to false
        val gw = status?.str("gateway_id").orEmpty()
        if (gw.isNotEmpty()) save(data.copy(gateways = data.gateways + ("${graph.store.activeId}|$profile" to gw)))
        val heldKid = status?.get("e2e").obj()?.str("kid")
        val have = data.records.any { it.kid == heldKid && it.gatewayId == gw && it.installationId == id }
        if (status?.bool("crypto") == false || gw.isEmpty()) return pairing to false
        if (!have) {
            val rec = E2E.newRecord(id, gw, graph.store.activeId, profile)
            val r = graph.client.rest("POST", e2ePath, buildJsonObject {
                put("installation_id", id); put("gateway_id", gw); put("kid", rec.kid); put("secret", rec.secret)
            }).obj()
            if (r?.bool("ok") != true || r.str("kid") != rec.kid) return pairing to false
            save(data.copy(records = data.records.filterNot { it.installationId == id && it.gatewayId == gw } + rec))
        }
        return pairing to true
    }

    /** A clarify answer straight to the relay (sealed when this gateway has a key): true when accepted. */
    suspend fun respondDecision(requestId: String, answer: String, questionId: String?, gatewayId: String?): String? {
        val record = data.records.firstOrNull { it.gatewayId == gatewayId && it.installationId == data.installationId }
        val body = buildJsonObject {
            put("answer", record?.let { E2E.sealAnswer(answer, it, requestId, questionId) } ?: answer)
            questionId?.let { put("question_id", it) }
            gatewayId?.let { put("gateway_id", it) }
        }
        val (code, _) = relay("POST", "/v1/decisions/${java.net.URLEncoder.encode(requestId, "UTF-8")}/respond", body, auth = true)
        return when (code) { 200 -> null; 404, 410 -> tr(R.string.pushstate_question_gone); 409 -> tr(R.string.pushstate_answered_elsewhere); else -> tr(R.string.pushstate_relay_code, code) }
    }
}

/** Initializes the default Firebase app from BuildConfig (no google-services plugin). False without a config. */
fun ensureFirebase(context: Context): Boolean {
    if (BuildConfig.FCM_APP_ID.isEmpty()) return false
    if (FirebaseApp.getApps(context).isEmpty()) {
        FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FCM_APP_ID).setApiKey(BuildConfig.FCM_API_KEY)
            .setProjectId(BuildConfig.FCM_PROJECT_ID).setGcmSenderId(BuildConfig.FCM_SENDER_ID).build())
    }
    return true
}
