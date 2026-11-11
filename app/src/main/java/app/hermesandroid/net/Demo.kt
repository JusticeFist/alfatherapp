package app.hermesandroid.net

import android.net.Uri
import android.util.Base64
import app.hermesandroid.HermesApp
import app.hermesandroid.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Demo mode: a fake Hermes dashboard inside the app ([HermesClient] routes every REST/RPC call here when the
 * dashboard URL is [URL]). Sample data lives in assets/demo.json; chats created here live in memory until the
 * process dies. Replies are scripted: a streamed answer with one tool step, plus an approval card when the message
 * says "approve".
 */
object Demo {
    const val URL = "https://demo.allfather.invalid"

    private val data: JsonObject by lazy {
        val text = HermesApp.instance.assets.open("demo.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).obj()!!
    }
    private val routes by lazy { data["rest"].obj()!! }
    /** Chats created in this run (newest first) and every chat's history, sample ones included once opened. */
    private val created = mutableListOf<JsonObject>()
    private val history = ConcurrentHashMap<String, MutableList<JsonElement>>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val turns = ConcurrentHashMap<String, Job>()
    private var nextChat = 1
    private var nextRow = 1000

    // ---- REST ----

    suspend fun rest(method: String, path: String, body: JsonObject?): JsonElement {
        delay(120) // a network-ish pause, so spinners and transitions look like the real thing
        val u = Uri.parse(path)
        val p = u.path.orEmpty()
        when {
            method == "GET" && p == "/api/profiles/sessions" -> return sessions(u)
            method == "GET" && p.startsWith("/api/sessions/") && p.endsWith("/messages") -> {
                val id = p.removePrefix("/api/sessions/").removeSuffix("/messages")
                return buildJsonObject { put("messages", JsonArray(messagesOf(id).toList().asReversed())) }
            }
            method == "GET" && p == "/api/fs/read-text" -> return buildJsonObject {
                put("text", data["files"].obj()?.str(u.getQueryParameter("path").orEmpty()) ?: "Demo file.")
                put("binary", false); put("truncated", false)
            }
            method == "GET" && p == "/api/fs/read-data-url" -> return buildJsonObject { put("dataUrl", sampleImage) }
            method == "GET" && p == "$PULSE/feed" -> return pulseFeed()
            method == "POST" && p.startsWith("$PULSE/") && body != null -> body.num("card")?.toInt()?.let { id ->
                val now = JsonPrimitive(now())
                val change = when (p.removePrefix("$PULSE/")) {
                    "rate" -> mapOf("rating" to (body["rating"] ?: JsonPrimitive("up")))
                    "done" -> mapOf("done_at" to if (body.bool("done") == true) now else JsonNull)
                    "dismiss" -> mapOf("dismissed_at" to now)
                    else -> emptyMap()
                }
                pulse.merge(id, change) { a, b -> a + b }
            }
        }
        return route("$method $p") ?: EMPTY_OBJECT
    }

    /** Exact "METHOD /path" first, then patterns where `*` stands for one path segment. */
    private fun route(key: String): JsonElement? = (routes[key] ?: routes.entries.firstOrNull { (k, _) ->
        val a = k.split('/'); val b = key.split('/')
        a.size == b.size && a.indices.all { a[it] == "*" || a[it] == b[it] }
    }?.value)?.let(::live)

    private const val PULSE = "/api/plugins/conduit_push/pulse"
    /** Pulse taps (rate, done, dismiss) applied over the sample cards. */
    private val pulse = ConcurrentHashMap<Int, Map<String, JsonElement>>()

    private fun pulseFeed(): JsonElement {
        val feed = route("GET $PULSE/feed").obj()!!
        val cards = (feed["cards"] as? JsonArray).orEmpty().map { c -> c.obj()!!.let { o -> JsonObject(o + pulse[o.num("id")?.toInt()].orEmpty()) } }
        return JsonObject(feed + ("cards" to JsonArray(cards)))
    }

    /** The chat list: only the first page of the default scope has rows (archived and cron lists stay empty). */
    private fun sessions(u: Uri): JsonElement {
        if (u.getQueryParameter("archived") == "only" || u.getQueryParameter("source") == "cron" || u.getQueryParameter("offset") != "0") return buildJsonObject { put("sessions", JsonArray(emptyList())) }
        val sample = (route("GET /api/profiles/sessions").obj()?.get("sessions") as? JsonArray).orEmpty()
        return buildJsonObject { put("sessions", JsonArray(synchronized(created) { created.filter { (it.num("message_count") ?: 0.0) > 0 } } + sample)) }
    }

    private fun messagesOf(id: String): MutableList<JsonElement> = history.getOrPut(id) {
        (data["messages"].obj()?.get(id)?.let(::live) as? JsonArray).orEmpty().toMutableList()
    }

    private fun now() = System.currentTimeMillis() / 1000

    /** "@-N"/"@+N" → epoch seconds from now; "@iso-N"/"@iso+N" → the same as ISO-8601 (cron's next/last run). */
    private fun live(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { live(it.value) })
        is JsonArray -> JsonArray(e.map(::live))
        is JsonPrimitive -> {
            val s = e.content
            when {
                !e.isString || !s.startsWith("@") -> e
                s.startsWith("@iso") -> JsonPrimitive(OffsetDateTime.now().plusSeconds(s.removePrefix("@iso").toLong()).withNano(0).toString())
                else -> JsonPrimitive(now() + s.removePrefix("@").toLong())
            }
        }
    }

    private val sampleImage by lazy {
        val bytes = HermesApp.instance.resources.openRawResource(R.drawable.af_login).use { it.readBytes() }
        "data:image/webp;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ---- RPC ----

    private val info = buildJsonObject {
        put("model", "claude-opus-5-5"); put("provider", "anthropic"); put("reasoning_effort", "medium"); put("fast", false); put("yolo", false)
    }

    suspend fun call(method: String, params: JsonObject, scope: CoroutineScope, emit: (Any) -> Unit): JsonElement {
        delay(60)
        val sid = params.str("session_id").orEmpty()
        return when (method) {
            "session.create" -> {
                val id = "demo-new-${nextChat++}"
                val title = params.str("title")?.takeIf { it != "Bot Chat" }
                if (params.bool("hidden") != true) synchronized(created) {
                    created.add(0, buildJsonObject {
                        put("id", id); put("title", title ?: ""); put("source", params.str("source") ?: "chat")
                        put("last_active", now()); put("message_count", 0)
                        params.str("profile")?.let { put("profile", it) }
                    })
                }
                history[id] = mutableListOf()
                buildJsonObject { put("session_id", id); put("stored_session_id", id); put("info", info) }
            }
            "session.resume" -> buildJsonObject {
                put("session_id", sid); put("running", turns[sid]?.isActive == true); put("info", info); put("open_requests", JsonArray(emptyList()))
            }
            "prompt.submit" -> {
                turns[sid]?.cancel()
                turns[sid] = scope.launch { turn(sid, params.str("text").orEmpty(), emit) }
                buildJsonObject { put("status", "streaming") }
            }
            "session.interrupt" -> {
                turns.remove(sid)?.cancel()
                emit(HermesEvent("message.complete", sid, buildJsonObject { put("status", "interrupted") }))
                EMPTY_OBJECT
            }
            "model.options" -> route("GET /api/model/options")!!
            "config.set" -> when (params.str("key")) {
                "busy" -> buildJsonObject { put("value", "queue") }
                "model" -> buildJsonObject { put("value", params.str("value").orEmpty()) }
                else -> EMPTY_OBJECT
            }
            // No catalog: the composer falls back to its built-in commands.
            "commands.catalog" -> throw RpcException(-32601, "method not found")
            else -> data["rpc"].obj()?.get(method)?.let(::live) ?: EMPTY_OBJECT
        }
    }

    /** An answer to a server request (an approval card); resumes the turn waiting on it. */
    fun reply(id: String, result: JsonObject) { approvals.remove(id)?.complete(result) }

    private suspend fun turn(sid: String, text: String, emit: (Any) -> Unit) {
        val words = text.substringAfterLast("</system-reminder>").trim()
        val log = messagesOf(sid)
        log += row("user", words)
        fun ev(type: String, payload: JsonObject) = emit(HermesEvent(type, sid, payload))

        ev("message.start", EMPTY_OBJECT)
        delay(300)
        ev("reasoning.delta", buildJsonObject { put("text", "Demo mode: no model behind this. Replaying a scripted turn.") })
        delay(400)
        stream(sid, "Let me check that for you.", emit)

        // "approve" in the message: a shell step behind an approval card; otherwise a web search that just runs.
        val asksApproval = Regex("approv|aprob", RegexOption.IGNORE_CASE).containsMatchIn(words)
        val (tool, args) = if (asksApproval) "terminal" to buildJsonObject { put("command", CMD) }
            else "web_search" to buildJsonObject { put("query", words.take(60)) }
        ev("tool.start", buildJsonObject { put("tool_id", "call_1"); put("name", tool); put("args_text", args.toString()) })
        val choice = if (asksApproval) approval(sid, emit) else { delay(900); "once" }
        val denied = choice == "deny"
        val (result, summary) = when {
            denied -> "Denied by you." to "Denied"
            asksApproval -> FILES.joinToString("\n") to "Listed ${FILES.size} files"
            else -> "3 results" to "Found 3 results"
        }
        ev("tool.complete", buildJsonObject { put("tool_id", "call_1"); put("name", tool); put("result", result); put("summary", summary) })
        delay(300)

        val reply = when {
            denied -> "Okay, I didn't run it. In demo mode nothing runs anyway."
            asksApproval -> "You approved it (**$choice**). The demo folder has:\n\n" + FILES.joinToString("\n") { "- `$it`" }
            else -> "You said: *${words.take(200)}*\n\nThis is **demo mode**, so this reply is scripted. With your own Hermes you'd get a real answer here.\n\n" +
                "| Try | Where |\n|---|---|\n| Approval card | write *approve* |\n| Kanban, cron | Tasks |\n| Agent notices | Pulse |\n\n```kotlin\nval demo = true\n```"
        }
        stream(sid, reply, emit)
        ev("session.usage", buildJsonObject { put("usage", buildJsonObject { put("context_used", 5400 + log.size * 300); put("context_max", 200000) }) })
        ev("message.complete", buildJsonObject { put("status", "complete"); put("text", reply) })
        log += row("assistant", reply)

        // A new chat gets a title from its first words, like Hermes' titler, and the list refreshes.
        synchronized(created) {
            val i = created.indexOfFirst { it.str("id") == sid }
            if (i >= 0) {
                val title = created[i].str("title").orEmpty().ifEmpty { words.split(' ').take(4).joinToString(" ").take(40).ifEmpty { "Demo chat" } }
                created[i] = JsonObject(created[i] + mapOf("title" to JsonPrimitive(title), "preview" to JsonPrimitive(reply.take(80)),
                    "last_active" to JsonPrimitive(now()), "message_count" to JsonPrimitive(log.size)))
                ev("session.title", buildJsonObject { put("session_id", sid); put("title", title) })
            }
        }
        emit(HermesEvent("sessions.changed", "", EMPTY_OBJECT))
        turns.remove(sid)
    }

    private const val CMD = "ls ~/demo"
    private val FILES = listOf("notes.md", "trip.html", "allfather.webp")

    /** Shows the approval card and waits for the user's choice ("deny" after 2 min). */
    private suspend fun approval(sid: String, emit: (Any) -> Unit): String {
        val srq = "srq-demo-${System.nanoTime()}"
        val answer = CompletableDeferred<JsonObject>().also { approvals[srq] = it }
        emit(ServerRequest(srq, "approval", buildJsonObject {
            put("session_id", sid); put("command", CMD); put("description", "Run a shell command"); put("tool_name", "terminal")
            put("choices", buildJsonArray { listOf("once", "session", "always", "deny").forEach { add(JsonPrimitive(it)) } })
        }))
        return withTimeoutOrNull(120_000) { answer.await() }?.str("choice") ?: "deny"
    }

    private suspend fun stream(sid: String, text: String, emit: (Any) -> Unit) {
        Regex("\\S+\\s*").findAll(text).chunked(3).forEach { c ->
            emit(HermesEvent("message.delta", sid, buildJsonObject { put("text", c.joinToString("") { it.value }) }))
            delay(45)
        }
    }

    private fun row(role: String, content: String) = buildJsonObject {
        put("id", nextRow++); put("role", role); put("content", content); put("timestamp", now())
    }
}
