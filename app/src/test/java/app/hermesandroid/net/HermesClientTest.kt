package app.hermesandroid.net

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** HermesClient's WebSocket against a fake dashboard: ticket, /api/ws upgrade, gateway.ready, JSON-RPC replies. */
@RunWith(RobolectricTestRunner::class)
// HermesApp: error paths build their messages with tr().
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
// Robolectric's Conscrypt provider breaks javax.crypto for the BouncyCastle tests that share this JVM.
@ConscryptMode(ConscryptMode.Mode.OFF)
class HermesClientTest {
    private val server = MockWebServer()
    private val tickets = AtomicInteger()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    /** How the fake Hermes answers every RPC except client.capabilities: (socket, id, method). */
    @Volatile private var answer: (WebSocket, Int, String) -> Unit = { ws, id, _ -> ws.send(result(id, "{}")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var client: HermesClient

    private val hermes = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val o = Json.parseToJsonElement(text).jsonObject
            val id = o["id"]?.jsonPrimitive?.intOrNull ?: return
            when (val method = o["method"]?.jsonPrimitive?.content ?: return) {
                "client.capabilities" -> webSocket.send(result(id, "{}"))
                else -> answer(webSocket, id, method)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun result(id: Int, json: String) = """{"jsonrpc":"2.0","id":$id,"result":$json}"""

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> MockResponse.Builder().body("""{"ticket":"t${tickets.incrementAndGet()}"}""").build()
                "/api/ws" -> MockResponse.Builder().webSocketUpgrade(hermes).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
        val store = Store(RuntimeEnvironment.getApplication(), encrypted = false)
        store.addDashboard()
        store.baseUrl = server.url("/").toString()
        client = HermesClient(store, scope)
    }

    @After
    fun tearDown() {
        client.stop()
        scope.cancel()
        server.close()
    }

    private fun connect() = runBlocking {
        client.start()
        withTimeout(10_000) { client.connected.first { it } }
    }

    @Test
    fun callGetsItsResult() {
        answer = { ws, id, method -> ws.send(result(id, """{"echo":"$method"}""")) }
        connect()
        val r = runBlocking { client.call("sessions.list") }
        assertEquals("sessions.list", r.jsonObject["echo"]?.jsonPrimitive?.content)
    }

    @Test
    fun outOfOrderRepliesInOneFrameReachTheirCallers() {
        val held = CopyOnWriteArrayList<Pair<Int, String>>()
        answer = { ws, id, method ->
            held += id to method
            // Both replies, newest first, newline-batched in a single frame.
            if (held.size == 2) ws.send(held.reversed().joinToString("\n") { (i, m) -> result(i, "\"$m\"") })
        }
        connect()
        runBlocking {
            val first = async { client.call("first") }
            val second = async { client.call("second") }
            assertEquals("first", first.await().jsonPrimitive.content)
            assertEquals("second", second.await().jsonPrimitive.content)
        }
    }

    @Test
    fun rpcErrorBecomesRpcException() {
        answer = { ws, id, _ -> ws.send("""{"jsonrpc":"2.0","id":$id,"error":{"code":4132,"message":"session not found"}}""") }
        connect()
        val e = runCatching { runBlocking { client.call("session.resume") } }.exceptionOrNull()
        assertTrue("got $e", e is RpcException && e.code == 4132)
    }

    // withTimeout would throw a CancellationException that callers rethrow and loops die on; it must be an IOException.
    @Test
    fun unansweredCallTimesOutAsIOException() {
        answer = { _, _, _ -> }
        connect()
        val e = runCatching { runBlocking { client.call("slow", timeoutMs = 300) } }.exceptionOrNull()
        assertTrue("got $e", e is IOException)
    }

    // A dashboard restart closes with 1012: the RPC in flight fails at once instead of waiting out its 30 s timeout.
    @Test
    fun serverCloseFailsTheCallInFlight() {
        answer = { ws, _, _ -> ws.close(1012, "restart") }
        connect()
        val started = System.currentTimeMillis()
        val e = runCatching { runBlocking { client.call("prompt.submit") } }.exceptionOrNull()
        assertTrue("got $e", e is IOException)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun serverCloseReconnectsWithAFreshTicket() {
        connect()
        sockets.last().close(1012, "restart")
        runBlocking {
            withTimeout(10_000) { while (!(tickets.get() == 2 && client.connected.value)) delay(20) }
            assertEquals(JsonObjectText, client.call("gateway.ping").toString())
        }
        assertEquals(2, sockets.size)
    }

    // A network switch drops the socket while onConnected is still syncing: the client must reconnect anyway,
    // not sit on "Reconectando…" until a force stop.
    @Test
    fun dropDuringOnConnectedStillReconnects() {
        val release = CompletableDeferred<Unit>()
        client.onConnected = { if (tickets.get() == 1) release.await() }
        connect()
        sockets.last().close(1012, "restart")
        runBlocking {
            withTimeout(10_000) { while (!(tickets.get() == 2 && client.connected.value)) delay(20) }
        }
        release.complete(Unit)
    }

    private companion object { const val JsonObjectText = "{}" }
}
