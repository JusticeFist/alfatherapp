package app.hermesandroid.chat

import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.HermesEvent
import app.hermesandroid.net.RpcException
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Quick side chats (watch, assistant) trade depth for speed. */
const val FAST_MODEL = "claude-sonnet-5-5"

/**
 * A side chat of its own on the default profile — the watch's, the assistant's — that streams each answer to the caller.
 * It's reused for 30 min. A runtime id only lives on the connection that opened or resumed it, so after a reconnect
 * the chat is resumed by its stored id; one that died anyway ("not found") is replaced and the question re-sent.
 */
class QuickChat(private val graph: AppGraph, private val title: String, private val model: String = FAST_MODEL, private val reasoning: String = "low") {
    private val lock = Mutex()
    private var session: String? = null
    private var stored: String? = null
    private var epoch = -1
    private var lastAsk = 0L

    /** The answer in flight: its runtime id, what's arrived, and who's waiting. */
    private class Turn(val sid: String, val onText: (String, Boolean) -> Unit, val onError: (String) -> Unit) { val text = StringBuilder() }
    @Volatile private var turn: Turn? = null

    /** The chat's stored id, to open it in the app. */
    val storedId get() = stored

    init { graph.listen(::onEvent) }

    /** Connects and opens (or resumes) the chat ahead of the question, while the user is still talking. */
    suspend fun prepare() = lock.withLock {
        runCatching {
            if (!graph.signedIn) return@runCatching
            graph.client.start()
            withTimeoutOrNull(20_000) { graph.client.connected.first { it } } ?: return@runCatching
            val now = System.currentTimeMillis()
            chat(now - lastAsk < 30 * 60_000L)
            lastAsk = now
        }
    }

    /** Sends `text` (with `image` attached first, a phone-control capture); `onText(soFar, done)` follows the answer as it's
     *  written. Errors (connection, turn) go to `onError`. */
    suspend fun ask(text: String, image: ByteArray? = null, onText: (String, Boolean) -> Unit, onError: (String) -> Unit) = lock.withLock {
        try {
            if (!graph.signedIn) throw IllegalStateException(tr(R.string.quickchat_not_signed_in))
            graph.client.start()
            withTimeoutOrNull(20_000) { graph.client.connected.first { it } } ?: throw IllegalStateException(tr(R.string.quickchat_unreachable))
            // Context like any message: location and phone state go first.
            graph.location.onMessage(); graph.phone.sync("message")
            val now = System.currentTimeMillis()
            val fresh = now - lastAsk < 30 * 60_000L
            lastAsk = now
            suspend fun submit(sid: String) {
                turn = Turn(sid, onText, onError)
                image?.let { graph.client.call("image.attach_bytes", app.hermesandroid.control.PhoneAgent.shotParams(sid, it), timeoutMs = 60_000, scoped = false) }
                graph.client.call("prompt.submit", buildJsonObject { put("session_id", sid); put("text", text) }, timeoutMs = 60_000, scoped = false)
            }
            try { submit(chat(fresh)) }
            catch (e: RpcException) {
                if (e.message?.contains("not found", true) != true) throw e
                session = null; stored = null
                submit(chat(fresh = false))
            }
        } catch (e: Exception) {
            turn = null
            onError(e.message ?: tr(R.string.quickchat_ask_failed))
        }
    }

    /** The runtime id to send to: the current one, the same chat resumed on this connection, or a new chat. */
    private suspend fun chat(fresh: Boolean): String {
        val st = stored
        if (fresh && session != null && epoch == graph.connectedEpoch) return session!!
        if (fresh && st != null) runCatching {
            graph.client.call("session.resume", buildJsonObject {
                put("session_id", st); put("cols", 60); put("source", "desktop"); put("omit_messages", true)
            }, timeoutMs = 60_000, scoped = false).obj()?.str("session_id")
        }.getOrNull()?.let { session = it; epoch = graph.connectedEpoch; return it }
        val r = graph.client.call("session.create", buildJsonObject {
            put("cols", 60); put("source", "desktop"); put("title", title); put("profile", "default")
            // session.create's per-session overrides: a config.set model right after create answers ok but the fresh
            // agent still builds with the profile default.
            put("model", model); put("provider", "anthropic"); put("reasoning_effort", reasoning)
        }, scoped = false).obj()
        stored = r?.str("stored_session_id")
        epoch = graph.connectedEpoch
        return r?.str("session_id")?.also { session = it } ?: throw IllegalStateException(tr(R.string.quickchat_no_chat))
    }

    private fun onEvent(e: HermesEvent) {
        val t = turn ?: return
        if (e.sessionId != t.sid) return
        when (e.type) {
            "message.delta" -> e.payload.str("text")?.let { t.text.append(it); t.onText(t.text.toString(), false) }
            "message.complete" -> {
                if (t.text.isEmpty()) e.payload.str("text")?.let { t.text.append(it) }
                turn = null
                t.onText(t.text.toString().trim(), true)
            }
            "error" -> { turn = null; t.onError(e.payload.str("error") ?: tr(R.string.quickchat_turn_failed)) }
        }
    }
}
