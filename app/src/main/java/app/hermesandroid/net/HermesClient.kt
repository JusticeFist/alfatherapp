package app.hermesandroid.net

import app.hermesandroid.R
import app.hermesandroid.tr
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** `word`: the plugin's machine-readable refusal (`{"detail", "code"}`), when it gave one. */
class HttpError(val code: Int, message: String, val word: String? = null) : IOException(message)
class RpcException(val code: Int, message: String) : Exception(message)

data class HermesEvent(val type: String, val sessionId: String, val payload: JsonObject)
data class ServerRequest(val id: String, val method: String, val params: JsonObject)

data class RpcReply(val id: Int, val result: JsonElement, val error: RpcException?)

val EMPTY_OBJECT = JsonObject(emptyMap())

/** Headers a user may never override, plus anything `sec-`. */
private val RESERVED_HEADERS = setOf(
    "host", "cookie", "content-length", "content-type", "transfer-encoding", "connection", "upgrade", "keep-alive",
    "proxy-connection", "te", "trailer", "expect", "origin", "authorization",
)

/** `Name: value` lines → valid, non-reserved header pairs (first spelling of a name wins). */
fun parseHeaders(text: String): List<Pair<String, String>> {
    val seen = mutableSetOf<String>()
    return text.lines().mapNotNull { line ->
        val name = line.substringBefore(':', "").trim()
        val value = line.substringAfter(':', "").trim()
        val lower = name.lowercase()
        if (name.isEmpty() || value.isEmpty() || lower in RESERVED_HEADERS || lower.startsWith("sec-") || !name.all { it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~" }) null
        else if (!seen.add(lower)) null else name to value
    }
}
private val frameJson = Json { ignoreUnknownKeys = true }

/**
 * Splits one WS text frame (possibly several newline-batched JSON objects) into
 * events, server-to-client requests (string `srq-` ids) and replies to our integer ids.
 */
fun parseFrames(text: String): List<Any> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
    val obj = runCatching { frameJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@mapNotNull null
    val method = obj.str("method")
    val id = obj["id"] as? JsonPrimitive
    when {
        method == "event" -> {
            val p = obj["params"].obj() ?: return@mapNotNull null
            val type = p.str("type") ?: return@mapNotNull null
            HermesEvent(type, p.str("session_id").orEmpty(), p["payload"].obj() ?: EMPTY_OBJECT)
        }
        method != null && id != null && id.isString -> ServerRequest(id.content, method, obj["params"].obj() ?: EMPTY_OBJECT)
        method == null && id?.intOrNull != null -> {
            val err = obj["error"].obj()
            RpcReply(id.intOrNull!!, obj["result"] ?: JsonNull, err?.let { RpcException(it.num("code")?.toInt() ?: 0, it.str("message") ?: tr(R.string.hermesclient_rpc_error)) })
        }
        else -> null
    }
}.toList()
fun JsonElement?.obj() = this as? JsonObject
fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

/**
 * Hermes dashboard client: cookie-authenticated REST plus the JSON-RPC 2.0 WebSocket at /api/ws.
 * Protocol notes live in docs/protocol.md.
 */
class HermesClient(private val store: Store, private val scope: CoroutineScope) {
    private val cookieJar = PersistentCookieJar(store)
    private val http = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(::authHeaders)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Headers for the active dashboard only: proxy headers, Cloudflare Access (HTTPS only)
     * and the OAuth bearer. Reserved names are never overridden.
     */
    private fun authHeaders(chain: okhttp3.Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.toString().startsWith(base)) return chain.proceed(req)
        val b = req.newBuilder()
        parseHeaders(store.customHeaders).forEach { (n, v) -> b.header(n, v) }
        if (req.url.isHttps) store.cfAccess.split('\n').takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { (id, secret) ->
            b.header("CF-Access-Client-Id", id.trim()).header("CF-Access-Client-Secret", secret.trim())
        }
        oauthAccess()?.let { b.header("Authorization", "Bearer $it") }
        return chain.proceed(b.build())
    }

    private fun oauthAccess(): String? = store.oauthTokens.takeIf { it.isNotEmpty() }?.let { runCatching { json.parseToJsonElement(it).obj()?.str("access_token") }.getOrNull() }
    // The WS upgrade must not follow redirects (an auth proxy would bounce it to a login page).
    private val wsHttp = http.newBuilder().followRedirects(false).readTimeout(0, TimeUnit.MILLISECONDS).build()
    // Token endpoints: no stale bearer, no cookies; shares the main client's connection pool.
    private val tokenHttp = http.newBuilder().apply { interceptors().clear() }.cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    /** Single consumer (AppGraph); unlimited so streamed deltas are never dropped. */
    val inbox = Channel<Any>(Channel.UNLIMITED)
    val connected = MutableStateFlow(false)
    val lastError = MutableStateFlow<String?>(null)
    var declinesNotShown = false
        private set
    /** Runs after every successful (re)connect, once the socket is ready for RPCs. */
    var onConnected: (suspend () -> Unit)? = null

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    @Volatile private var wantConnected = false
    private var connectJob: Job? = null
    private var pingJob: Job? = null

    private val base get() = store.baseUrl.trimEnd('/')

    /** Active Hermes profile. `default` sends no profile; others scope every RPC and REST call (scopeParams / DashboardPath). */
    var profile: String
        get() = store.profile
        set(v) { store.profile = v }

    fun withProfile(path: String, profile: String = this.profile): String =
        if (profile == "default" || profile.isEmpty()) path
        else path + (if ('?' in path) "&" else "?") + "profile=" + URLEncoder.encode(profile, "UTF-8")

    // ---- REST ----

    suspend fun login(url: String, username: String, password: String) {
        stop()
        cookieJar.clear()
        store.oauthTokens = ""
        store.baseUrl = url.trim().trimEnd('/')
        store.username = username.trim()
        store.password = password
        passwordLogin()
    }

    /** Signs out of the active dashboard (keeps it in the saved list). */
    fun logout() {
        stop()
        runCatching { scope.launch { runCatching { rest("POST", "/auth/logout", reauth = false) } } }
        cookieJar.clear()
        store.signOutActive()
    }

    /** Points the client at the store's (new) active dashboard. */
    fun switchDashboard() {
        stop()
        cookieJar.reload()
    }

    /** `GET /api/auth/providers` (public): which sign-in methods this dashboard offers. */
    suspend fun providers(url: String): List<JsonObject> {
        val r = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url.trimEnd('/') + "/api/auth/providers").build()).execute().use { it.code to it.body.string() }
        }
        if (r.first in 300..399) throw HttpError(r.first, tr(R.string.hermesclient_login_proxy))
        val o = runCatching { json.parseToJsonElement(r.second).obj() }.getOrNull() ?: throw HttpError(r.first, tr(R.string.hermesclient_unexpected))
        if (r.first !in 200..299) throw HttpError(r.first, o.str("detail") ?: tr(R.string.hermesclient_error_code, r.first))
        return (o["providers"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it.obj() }
    }

    // ---- native OAuth: PKCE + loopback callback, bearer REST, refresh ----

    /** Exchanges an authorization code for tokens and makes them the active dashboard's credential. */
    suspend fun completeOAuth(url: String, code: String, verifier: String) {
        stop()
        cookieJar.clear()
        store.baseUrl = url.trim().trimEnd('/')
        store.password = ""
        val r = tokenRequest("/auth/native/token", buildJsonObject { put("code", code); put("code_verifier", verifier) })
        store.oauthTokens = r.toString()
        store.username = r.str("user_id") ?: r.str("provider") ?: "OAuth"
    }

    private suspend fun refreshOAuth() {
        val t = runCatching { json.parseToJsonElement(store.oauthTokens).obj() }.getOrNull() ?: throw HttpError(401, tr(R.string.hermesclient_sign_in_again))
        val r = tokenRequest("/auth/native/refresh", buildJsonObject { put("refresh_token", t.str("refresh_token").orEmpty()); put("provider", t.str("provider").orEmpty()) })
        store.oauthTokens = r.toString()
    }

    private suspend fun tokenRequest(path: String, body: JsonObject): JsonObject {
        val (code, text) = withContext(Dispatchers.IO) {
            tokenHttp.newCall(Request.Builder().url(base + path).post(body.toString().toRequestBody(jsonType)).build()).execute().use { it.code to it.body.string() }
        }
        val o = runCatching { json.parseToJsonElement(text).obj() }.getOrNull()
        if (code !in 200..299 || o?.str("access_token") == null) throw HttpError(code, o?.str("detail") ?: tr(R.string.hermesclient_sign_in_incomplete, code))
        return o
    }

    private suspend fun passwordLogin() {
        val body = buildJsonObject {
            put("provider", "basic")
            put("username", store.username)
            put("password", store.password)
        }
        try {
            rest("POST", "/auth/password-login", body, reauth = false)
        } catch (e: HttpError) {
            throw when (e.code) {
                401 -> HttpError(401, tr(R.string.hermesclient_wrong_credentials))
                429 -> HttpError(429, tr(R.string.hermesclient_too_many))
                else -> e
            }
        }
    }

    /** One REST call. A 401 means the session died: sign in again with the saved password and retry once. */
    suspend fun rest(method: String, path: String, body: JsonObject? = null, reauth: Boolean = true, timeoutSec: Long = 30): JsonElement {
        // Parsed here too: callers run on the main thread, and a history or a data-URL reply is megabytes of JSON.
        val (code, parsed) = withContext(Dispatchers.IO) {
            val reqBody = body?.toString()?.toRequestBody(jsonType) ?: if (method == "POST") ByteArray(0).toRequestBody(null) else null
            val client = if (timeoutSec == 30L) http else http.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(base + path).method(method, reqBody).build()).execute().use { it.code to it.body.string() }
                .let { (code, text) -> code to runCatching { json.parseToJsonElement(text) }.getOrNull() }
        }
        if (code == 401 && reauth) {
            if (store.oauthTokens.isNotEmpty()) refreshOAuth() else passwordLogin()
            return rest(method, path, body, reauth = false, timeoutSec = timeoutSec)
        }
        if (code !in 200..299) {
            throw HttpError(code, parsed.obj()?.str("detail") ?: tr(R.string.hermesclient_server_error, code), parsed.obj()?.str("code"))
        }
        return parsed ?: throw HttpError(code, tr(R.string.hermesclient_unexpected))
    }

    // ---- WebSocket ----

    fun start() {
        wantConnected = true
        if (ws == null && connectJob?.isActive != true) connectJob = scope.launch { connectLoop() }
    }

    fun stop() {
        wantConnected = false
        connectJob?.cancel()
        ws?.close(1000, null)
        dropSocket(ws)
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (wantConnected && ws == null && scope.isActive) {
            try {
                openSocket()
                lastError.value = null
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError.value = e.message ?: e.toString()
                android.util.Log.w("HermesClient", "connect attempt $attempt failed: ${lastError.value}")
                val half = ws
                half?.cancel()
                dropSocket(half)
                // ponytail: always uses the foreground cap (8 s); add the 60 s background cap with a background service
                val delayMs = max(500.0, min(8_000.0, 1_000.0 * 2.0.pow(attempt)) * Random.nextDouble(0.5, 1.0))
                attempt++
                delay(delayMs.toLong())
            }
        }
    }

    private suspend fun openSocket() {
        val ticket = rest("POST", "/api/auth/ws-ticket").obj()?.str("ticket") ?: throw IOException(tr(R.string.hermesclient_no_ticket))
        val gate = CompletableDeferred<Unit>()
        ready = gate
        val url = "$base/api/ws?ticket=" + URLEncoder.encode(ticket, "UTF-8")
        // The host plugin tells its clients apart by this header (reply-source filtering); the name is its protocol.
        val socket = wsHttp.newWebSocket(Request.Builder().url(url).header("X-Conduit-Client", "conduit").build(), Listener())
        ws = socket
        // A timeout must be an IOException: withTimeout's TimeoutCancellationException is a CancellationException, and
        // connectLoop rethrows those, so the loop died silently and the app sat on "Reconnecting…" for good.
        withTimeoutOrNull(15_000) { gate.await() } ?: throw IOException(tr(R.string.hermesclient_no_answer_connecting))
        val caps = call("client.capabilities", buildJsonObject { put("server_requests", true) }, scoped = false).obj()
        declinesNotShown = caps?.bool("declines_not_shown") ?: false
        connected.value = true
        startPing(socket)
        // Not inside the connect job: its dozen REST/RPC calls take seconds, and a drop meanwhile (a network switch)
        // found the job still active, so lost() started no new loop; the job then ended and nothing reconnected.
        scope.launch { runCatching { onConnected?.invoke() }.onFailure { android.util.Log.w("HermesClient", "onConnected: $it") } }
    }

    /** A plugin's own WebSocket (Kanban `/events`), let in by a fresh single-use ticket like `/api/ws`. */
    suspend fun pluginSocket(path: String, listener: WebSocketListener): WebSocket {
        val ticket = rest("POST", "/api/auth/ws-ticket").obj()?.str("ticket") ?: throw IOException(tr(R.string.hermesclient_no_ticket))
        val url = base + path + (if ('?' in path) "&" else "?") + "ticket=" + URLEncoder.encode(ticket, "UTF-8")
        return wsHttp.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun startPing(socket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            var misses = 0
            while (isActive && ws === socket) {
                delay(20_000)
                misses = try {
                    call("gateway.ping", timeoutMs = 10_000, scoped = false); 0
                } catch (e: RpcException) {
                    0 // an RPC error still proves the socket is alive
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    misses + 1
                }
                if (misses >= 2) socket.cancel()
            }
        }
    }

    private fun dropSocket(socket: WebSocket?) {
        if (socket == null || ws !== socket) return
        ws = null
        connected.value = false
        pingJob?.cancel()
        ready?.completeExceptionally(IOException(tr(R.string.hermesclient_connection_closed)))
        pending.values.forEach { it.completeExceptionally(IOException(tr(R.string.hermesclient_connection_lost))) }
        pending.clear()
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lastError.value = response?.let { tr(R.string.hermesclient_ws_refused, it.code) } ?: t.message
            lost(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

        // The server is closing (a restart sends 1012): answer and reconnect now. Without this OkHttp only calls
        // onClosed once we close too, and every RPC meanwhile waits on a dead socket until its timeout.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            lost(webSocket)
        }

        // On the client's scope (main): a drop on OkHttp's thread could land between openSocket's last lines and the
        // connect job's end, see the job active and start nothing.
        private fun lost(webSocket: WebSocket) {
            scope.launch {
                val wasCurrent = ws === webSocket
                dropSocket(webSocket)
                if (wasCurrent && wantConnected) start()
            }
        }
    }

    private fun handle(text: String) = parseFrames(text).forEach { f ->
        when (f) {
            is HermesEvent -> {
                if (f.type == "gateway.ready") ready?.complete(Unit)
                inbox.trySend(f)
            }
            is ServerRequest -> inbox.trySend(f)
            is RpcReply -> {
                val waiter = pending.remove(f.id) ?: return@forEach
                if (f.error != null) waiter.completeExceptionally(f.error) else waiter.complete(f.result)
            }
        }
    }

    /** JSON-RPC call. `scoped` adds the active profile unless the caller already set one. */
    suspend fun call(method: String, params: JsonObject? = null, timeoutMs: Long = 30_000, scoped: Boolean = true): JsonElement {
        val socket = ws ?: throw IOException(tr(R.string.hermesclient_no_connection))
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JsonElement>()
        pending[id] = waiter
        val p = profile
        val scopedParams = if (scoped && p != "default" && params?.containsKey("profile") != true) {
            JsonObject((params ?: EMPTY_OBJECT) + ("profile" to JsonPrimitive(p)))
        } else {
            params
        }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (scopedParams != null) put("params", scopedParams)
        }
        try {
            if (!socket.send(frame.toString())) throw IOException(tr(R.string.hermesclient_no_connection))
            return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: throw IOException(tr(R.string.hermesclient_timeout, method))
        } finally {
            pending.remove(id)
        }
    }

    /** Answer a server-to-client request (`srq-…` id). */
    fun reply(id: String, result: JsonObject) {
        ws?.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }.toString())
    }

    fun replyError(id: String, code: Int, message: String) {
        ws?.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject { put("code", code); put("message", message) })
            }.toString()
        )
    }
}
