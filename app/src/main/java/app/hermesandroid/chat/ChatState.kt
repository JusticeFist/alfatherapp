package app.hermesandroid.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.annotation.StringRes
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.HermesEvent
import app.hermesandroid.net.RpcException
import app.hermesandroid.net.ServerRequest
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class Role { User, Assistant, Tool, System, Error }

/**
 * The server's copy of a chat keeps the keys its rows had in the cached copy (same row id, or same tool call), so the
 * list keeps those items instead of rebuilding every row (Markdown blank for a frame, heights jumping).
 */
internal fun keepKeys(old: List<ChatMsg>, new: List<ChatMsg>): List<ChatMsg> {
    fun idOf(m: ChatMsg) = m.rowId?.let { "${m.role}:$it" } ?: m.toolId.takeIf { m.role == Role.Tool && it.isNotEmpty() }?.let { "tool:$it" }
    val keys = old.mapNotNull { m -> idOf(m)?.let { it to m.key } }.toMap(HashMap())
    return new.map { m -> idOf(m)?.let { keys.remove(it) }?.let { m.copy(key = it) } ?: m }
}

@Serializable
data class ChatMsg(
    val key: Long,
    val role: Role,
    val text: String = "",
    val reasoning: String = "",
    val toolName: String = "",
    val toolId: String = "",
    val args: String = "",
    val result: String = "",
    val done: Boolean = true,
    val time: Long = 0,
    /** Durable DB row id (history); live rows are addressed by `newest_role` until reloaded. */
    val rowId: Long? = null,
    val reaction: String? = null,
)

data class Attachment(val uri: Uri, val name: String, val mime: String, val size: Long) {
    val isImage get() = mime in IMAGE_MIMES
}

private val IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

/** Send while a turn runs: the busy input modes. */
enum class BusyAction(@StringRes val label: Int) { Queue(R.string.chatstate_busy_queue), Steer(R.string.chatstate_busy_steer), Interrupt(R.string.chatstate_busy_interrupt) }

data class SlashCommand(val name: String, val description: String, val category: String?, val argsHint: String? = null)

data class Subagent(val id: String, val goal: String, val status: String, val text: String)

/**
 * The open conversation. Every chat has a stored id (REST, list) and a runtime id
 * (WS: prompt.submit, events) — see docs/protocol.md §4.
 */
class ChatState(val graph: AppGraph, private val context: Context) {
    private val client = graph.client
    private val json = Json { ignoreUnknownKeys = true }

    var isOpen by mutableStateOf(false)
        private set
    /** Bumped whenever a chat is left: the transcript starts each chat with a fresh scroll state. */
    var opened by mutableStateOf(0)
        private set
    /** The user asked something in this view: that question rises to the top and its answer is read from its start. */
    var asked by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var storedId: String? = null
        private set
    var runtimeId by mutableStateOf<String?>(null)
        private set
    val messages = mutableStateListOf<ChatMsg>()
    var running by mutableStateOf(false)
        private set
    var turnStartedAt by mutableStateOf(0L)
        private set
    var model by mutableStateOf<String?>(null)
        private set
    var provider by mutableStateOf<String?>(null)
        private set
    var reasoning by mutableStateOf<String?>(null)
        private set
    var fast by mutableStateOf(false)
        private set
    var yolo by mutableStateOf(false)
        private set
    var contextPercent by mutableStateOf<Int?>(null)
        private set
    var statusText by mutableStateOf<String?>(null)
        private set
    var offline by mutableStateOf(false)
        private set

    // History paging (tail-anchored: offset counts back from the newest row).
    var hasOlder by mutableStateOf(false)
        private set
    var loadingOlder by mutableStateOf(false)
        private set
    private var historyOffset = 0

    // Composer
    var draft by mutableStateOf("")
    /** The next message goes with [COUNCIL_RULES]: the agent asks its helper bots before answering (that one message). */
    var council by mutableStateOf(false)
    val attachments = mutableStateListOf<Attachment>()
    var pendingSend by mutableStateOf<String?>(null)
        private set
    var busyAction by mutableStateOf(BusyAction.Queue)
    val slashCommands = mutableStateListOf<SlashCommand>()
    var takeoverOffered by mutableStateOf(false)
        private set
    var showModelPicker by mutableStateOf(false)

    val subagents = mutableStateListOf<Subagent>()

    private var current = -1 // index of the assistant message receiving deltas
    private var turnHadText = false
    private var nextKey = 0L
    private val drafts = mutableMapOf<String, String>()

    /** Profile this chat lives in: a Bot Chat belongs to its bot's profile, everything else to the active one. */
    var chatProfile by mutableStateOf<String?>(null)
        private set
    /** The agent's display name in this chat (Allfather for the default profile). */
    val agentName get() = app.hermesandroid.ui.shell.profileTitle(chatProfile ?: graph.profile)

    private suspend fun rpc(method: String, params: JsonObject? = null, timeoutMs: Long = 30_000): JsonElement {
        val p = chatProfile
        val scopedParams = if (p != null && params?.containsKey("profile") != true) JsonObject((params ?: JsonObject(emptyMap())) + ("profile" to JsonPrimitive(p))) else params
        return client.call(method, scopedParams, timeoutMs)
    }

    private fun scoped(path: String) = client.withProfile(path, chatProfile ?: graph.profile)

    /** Approvals / questions that belong to this chat. */
    val requests get() = graph.requests.filter { it.params.str("session_id").let { s -> s == null || s == runtimeId } }

    init {
        graph.listen(::onEvent)
        graph.scope.launch {
            snapshotFlow { graph.connectedEpoch }.collect { epoch ->
                if (epoch == 0 || !isOpen) return@collect
                // A new chat whose session.create died with the old socket is created again; anything else resumes.
                storedId?.let { id -> resume(id) } ?: unsentCreate?.let { if (runtimeId == null) create(it) }
            }
        }
    }

    // ---- open / close ----

    /** [profile]: the chat's own profile when it isn't the active one (a helper bot's chat or cron run). */
    fun open(stored: String, title: String, profile: String? = null) {
        close()
        isOpen = true
        chatProfile = profile?.takeIf { it != graph.profile }
        graph.page = null // the chat is the home screen: whatever page was over it goes
        storedId = stored
        this.title = title
        draft = drafts[stored].orEmpty()
        graph.chats.markSeen(stored)
        graph.scope.launch {
            // Saved chats open at once (read-only until the server answers); the file is read off the main thread.
            withContext(Dispatchers.IO) { readCache(stored) }?.takeIf { storedId == stored && messages.isEmpty() }
                ?.let { cached -> messages.addAll(cached); nextKey = (cached.maxOfOrNull { it.key } ?: 0) + 1; offline = true }
            runCatching { loadHistory(stored, older = false) }.onFailure { if (messages.isEmpty()) addSystem(tr(R.string.chatstate_history_failed, it.message.toString()), error = true) }
            if (client.connected.value) resume(stored) // otherwise the connect hook resumes it
        }
    }

    /** The bot's one hidden chat titled exactly "Bot Chat" (BotMode): reuse it, or create it in the bot's profile. */
    fun openBotChat(profile: String, label: String) {
        close()
        isOpen = true
        graph.page = null
        chatProfile = profile.takeIf { it != graph.profile }
        title = label
        graph.scope.launch {
            val existing = runCatching {
                val r = rpc("session.list", buildJsonObject { put("profile", profile); put("title", BOT_CHAT_TITLE); put("limit", 200); put("include_hidden", true) })
                val rows = (r.obj()?.get("sessions") as? JsonArray ?: r as? JsonArray).orEmpty().mapNotNull { it.obj() }
                rows.firstOrNull { it.str("title") == BOT_CHAT_TITLE }?.let { it.str("id") ?: it.str("session_id") }
            }.getOrNull()
            if (existing != null) {
                storedId = existing
                runCatching { loadHistory(existing, older = false) }
                if (client.connected.value) resume(existing)
            } else {
                create(buildJsonObject {
                    put("cols", 96); put("source", "desktop"); put("profile", profile); put("title", BOT_CHAT_TITLE)
                    put("hidden", true); put("follow_profile_config", true)
                })
            }
        }
    }

    fun openNew(cwd: String? = null) {
        close()
        isOpen = true
        graph.page = null
        title = tr(R.string.chatstate_new_chat)
        graph.scope.launch { create(buildJsonObject { put("cols", 96); put("source", "desktop"); cwd?.let { put("cwd", it) } }) }
    }

    /** A new chat's session.create that hasn't succeeded yet, retried when the connection comes back. */
    private var unsentCreate: JsonObject? = null

    private suspend fun create(params: JsonObject) {
        unsentCreate = params
        // Not connected yet (the app just started): it waits, and the connect hook creates it.
        if (!client.connected.value) return
        try {
            val r = rpc("session.create", params).obj()
            unsentCreate = null
            runtimeId = r?.str("session_id")
            storedId = r?.str("stored_session_id")
            applyInfo(r?.get("info").obj())
            afterAttach()
        } catch (e: Exception) {
            addSystem(tr(R.string.chatstate_create_failed, e.message.toString()), error = true)
        }
    }

    fun close() {
        // Leaving a chat: what came in while it was on screen was read.
        storedId?.let { id -> if (draft.isNotBlank()) drafts[id] = draft else drafts.remove(id); writeCache(id); graph.chats.markSeen(id) }
        opened++
        asked = false
        chatProfile = null
        isOpen = false
        unsentCreate = null
        storedId = null
        runtimeId = null
        messages.clear()
        attachments.clear()
        council = false
        subagents.clear()
        slashCommands.clear()
        running = false
        current = -1
        model = null
        provider = null
        reasoning = null
        contextPercent = null
        statusText = null
        pendingSend = null
        takeoverOffered = false
        hasOlder = false
        historyOffset = 0
        offline = false
        workerChat = false
        draft = ""
    }

    private suspend fun resume(stored: String) {
        try {
            val r = rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", stored)
                    put("cols", 96)
                    put("source", "desktop")
                    put("omit_messages", true)
                },
                timeoutMs = 60_000,
            ).obj() ?: return
            if (storedId != stored) return // user left while we waited
            runtimeId = r.str("session_id")
            running = r.bool("running") ?: false
            applyInfo(r["info"].obj())
            graph.adoptOpenRequests(r["open_requests"] as? JsonArray)
            afterAttach()
        } catch (e: RpcException) {
            // 4132: a Kanban worker's transcript. Hermes never lets it continue outside the board, so it's read-only here.
            if (e.code == 4132) { workerChat = true; offline = false; statusText = tr(R.string.chatstate_worker_chat) }
            else addSystem(tr(R.string.chatstate_open_failed, e.message.toString()), error = true)
        } catch (e: Exception) {
            addSystem(tr(R.string.chatstate_open_failed, e.message.toString()), error = true)
        }
    }

    /** A board worker's chat: readable, not writable (Hermes refuses to resume it). */
    var workerChat by mutableStateOf(false)
        private set

    /** Runs once a runtime session exists: flush a pending send, load per-session catalogs. */
    private suspend fun afterAttach() {
        pendingSend?.let { text ->
            pendingSend = null
            submit(text)
        }
        loadSlashCatalog()
        loadBusyMode()
        // Seed the composer ring; session.usage events keep it current afterwards.
        contextBreakdown()?.let { d -> contextPercentOf(d)?.let { contextPercent = it } }
    }

    private fun applyInfo(info: JsonObject?) {
        info ?: return
        info.str("model")?.let { model = it }
        info.str("provider")?.let { provider = it }
        info.str("reasoning_effort")?.let { reasoning = it }
        info.bool("fast")?.let { fast = it }
        info.bool("yolo")?.let { yolo = it }
        // A Bot Chat keeps its bot's name; the stored title is always "Bot Chat".
        if (chatProfile == null) info.str("title")?.takeIf { it.isNotBlank() && it != BOT_CHAT_TITLE }?.let { title = it }
    }

    // ---- history ----

    fun loadOlder() {
        val stored = storedId ?: return
        if (loadingOlder || !hasOlder) return
        graph.scope.launch { runCatching { loadHistory(stored, older = true) } }
    }

    private suspend fun loadHistory(stored: String, older: Boolean) {
        loadingOlder = older
        try {
            val id = URLEncoder.encode(stored, "UTF-8")
            val offset = if (older) historyOffset else 0
            val r = client.rest("GET", scoped("/api/sessions/$id/messages?limit=$PAGE&offset=$offset&order=latest&include_compacted=true&inline_images=false")).obj()
            val rows = (r?.get("messages") as? JsonArray).orEmpty().mapNotNull { it.obj() }
            if (storedId != stored) return
            historyOffset = offset + rows.size
            hasOlder = rows.size >= PAGE
            val page = parseHistory(rows.sortedBy { it.num("id") ?: it.num("timestamp") ?: 0.0 })
            if (older) {
                messages.addAll(0, page)
            } else {
                // The first server answer replaces the cached copy.
                val live = messages.filter { it.rowId == null && !offline }
                val kept = keepKeys(messages, page)
                messages.clear()
                messages.addAll(kept)
                messages.addAll(live)
                offline = false
            }
            current = -1
        } finally {
            loadingOlder = false
        }
    }

    private fun parseHistory(rows: List<JsonObject>): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        for (m in rows) {
            if (m.str("display_kind") == "hidden") continue
            val text = textOf(m["content"] ?: m["text"])
            val time = ((m.num("timestamp") ?: m.num("created_at") ?: 0.0) * 1000).toLong()
            val rowId = m.num("id")?.toLong()
            val reaction = (m["reactions"] as? JsonArray)?.firstNotNullOfOrNull { it.obj()?.takeIf { r -> r.str("author") == USER_AUTHOR }?.str("emoji") }
            when (m.str("role")) {
                "user" -> if (!text.startsWith("[Result:")) out += msg(Role.User, userWords(text)).copy(time = time, rowId = rowId, reaction = reaction)
                "assistant" -> {
                    val reasoningText = m.str("reasoning").orEmpty()
                    if (text.isNotBlank() || reasoningText.isNotBlank()) out += msg(Role.Assistant, text).copy(reasoning = reasoningText, time = time, rowId = rowId, reaction = reaction)
                    (m["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { call ->
                        val fn = call["function"].obj()
                        out += msg(Role.Tool, "").copy(toolName = fn?.str("name").orEmpty(), toolId = call.str("id").orEmpty(), args = fn?.str("arguments").orEmpty())
                    }
                }
                "tool" -> {
                    val i = out.indexOfLast { it.role == Role.Tool && it.toolId == m.str("tool_call_id") }
                    if (i >= 0) out[i] = out[i].copy(result = text)
                }
                "system" -> if (text.isNotBlank()) out += msg(Role.System, text).copy(time = time)
            }
        }
        return out
    }

    // ---- sending ----

    /** The composer's send button: a turn, a slash command, or (while busy) the chosen busy action. */
    fun send(action: BusyAction? = null) {
        val text = draft.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (workerChat) { addSystem(tr(R.string.chatstate_worker_no_messages, agentName)); return }
        draft = ""
        asked = true
        // Fresh phone context for the turn about to start (spaced 30 s).
        graph.phone.sync("message")
        graph.location.onMessage()
        if (text.startsWith("/") && attachments.isEmpty()) {
            graph.scope.launch { runSlash(text) }
            return
        }
        if (runtimeId == null) {
            // Type while reconnecting: it goes out once the chat is attached.
            pendingSend = text
            return
        }
        graph.scope.launch {
            val withRefs = uploadAttachments(text) ?: return@launch
            if (running) busySend(withRefs, action ?: busyAction) else submit(withRefs)
        }
    }

    fun cancelPendingSend() {
        pendingSend?.let { draft = it }
        pendingSend = null
    }

    /** Steps of phone control taken since the user's own last message (see control/PhoneAgent). */
    private var controlSteps = 0

    /** The reply asked for steps on the phone: run them and show Allfather where they led (confirming risky ones by notification). */
    private fun controlStep() {
        if (!app.hermesandroid.control.PhoneAgent.available) return
        val reply = app.hermesandroid.control.parseReply(messages.lastOrNull { it.role == Role.Assistant }?.text ?: return)
        if (reply.actions.isEmpty()) { controlSteps = 0; app.hermesandroid.control.PhoneAgent.finished(reply.say); return }
        graph.scope.launch {
            val step = app.hermesandroid.control.PhoneAgent.run(reply.actions, controlSteps, progress = { statusText = it }) ?: return@launch
            controlSteps = step.steps
            statusText = null
            submit(step.text, control = true, image = step.shot)
        }
    }

    /** `control`: a follow-up of phone control (not the user's words): it keeps the step count; `image`, its capture. */
    private suspend fun submit(text: String, queued: Boolean = false, control: Boolean = false, image: ByteArray? = null) {
        val sid = runtimeId ?: return
        if (!control) controlSteps = 0
        // With phone control on, the user's words go with the rules and the screen; the bubble shows only the words.
        // Hermes' titler skips a leading <system-reminder> block, so a new chat is named after the words, not the rules.
        // The council's rules go first so the chat list's preview can tell the two apart (ChatsState.readablePreview).
        val rules = buildList {
            if (!control && council) { add(COUNCIL_RULES); council = false }
            if (!control && app.hermesandroid.control.PhoneAgent.available)
                add(app.hermesandroid.control.CONTROL_RULES + "\n" + (app.hermesandroid.control.PhoneAgent.screenBlock() ?: ""))
        }
        val wire = if (rules.isEmpty()) text else "<system-reminder>\n" + rules.joinToString("\n") + "\n</system-reminder>\n" + text
        // A control follow-up (result + new screen) isn't the user's: no bubble, and stored hidden so a reload skips it too.
        if (!control) messages += msg(Role.User, text).copy(time = System.currentTimeMillis())
        if (!queued) startTurn()
        if (!queued) graph.scope.launch {
            app.hermesandroid.push.Progress.begin(context, graph.push, listOfNotNull(sid, storedId), title, storedId, chatProfile ?: graph.profile, app.hermesandroid.push.dashboardUuid(graph.store.activeId))
        }
        try {
            image?.let { rpc("image.attach_bytes", app.hermesandroid.control.PhoneAgent.shotParams(sid, it), timeoutMs = 60_000) }
            rpc(
                "prompt.submit",
                buildJsonObject { put("session_id", sid); put("text", wire); if (queued) put("queued", true); if (control) put("display_kind", "hidden") },
                timeoutMs = 180_000,
            )
            reportSurface(text)
        } catch (e: RpcException) {
            running = false
            if (e.message?.contains("SESSION_NOT_OWNED") == true || e.message?.contains("not owned", true) == true) {
                takeoverOffered = true
                addSystem(tr(R.string.chatstate_open_elsewhere), error = true)
            } else {
                addSystem(tr(R.string.chatstate_not_sent, e.message.toString()), error = true)
            }
        } catch (e: java.io.IOException) {
            running = false
            // The socket died under the send: it goes out again once the chat is attached.
            messages.removeAt(messages.indexOfLast { it.role == Role.User && it.text == text }.takeIf { it >= 0 } ?: return)
            pendingSend = text
        } catch (e: Exception) {
            running = false
            addSystem(tr(R.string.chatstate_not_sent, e.message.toString()), error = true)
        }
    }

    private suspend fun busySend(text: String, action: BusyAction) {
        val sid = runtimeId ?: return
        try {
            when (action) {
                BusyAction.Queue -> submit(text, queued = true)
                BusyAction.Steer -> {
                    val r = rpc("session.steer", buildJsonObject { put("session_id", sid); put("text", text) }).obj()
                    if (r?.str("status") == "rejected") addSystem(tr(R.string.chatstate_steer_rejected, agentName), error = true)
                    else messages += msg(Role.User, text).copy(time = System.currentTimeMillis(), toolName = STEER_MARK)
                }
                BusyAction.Interrupt -> {
                    rpc("session.redirect", buildJsonObject { put("session_id", sid); put("text", text) })
                    messages += msg(Role.User, text).copy(time = System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            addSystem(tr(R.string.chatstate_not_sent, e.message.toString()), error = true)
        }
    }

    // The notifier plugin records where a turn came from; harmless 404 without the plugin.
    private suspend fun reportSurface(text: String) {
        val sid = storedId ?: runtimeId ?: return
        runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
            client.rest("POST", scoped("/api/plugins/conduit_push/turn-surface"), buildJsonObject {
                // ponytail: the plugin's surface enum has no Android names yet; "iphone" is the closest phone surface
                put("session_id", sid); put("text_sha256", digest); put("surface", "iphone"); put("input", "text")
            }, reauth = false)
        }
    }

    fun stop() {
        val sid = runtimeId ?: return
        graph.scope.launch { runCatching { rpc("session.interrupt", buildJsonObject { put("session_id", sid) }) } }
    }

    fun takeOver() {
        val stored = storedId ?: return
        takeoverOffered = false
        graph.scope.launch {
            try {
                val r = client.rest("POST", scoped("/api/plugins/conduit_push/sessions/takeover"), buildJsonObject { put("session_ids", buildJsonArray { add(JsonPrimitive(stored)) }) }).obj()
                addSystem(tr(when (r?.str("status")) {
                    "taken_over", "free", "same_host" -> R.string.chatstate_taken_over
                    "busy" -> R.string.chatstate_takeover_busy
                    else -> R.string.chatstate_takeover_failed
                }))
                resume(stored)
            } catch (e: Exception) {
                addSystem(tr(R.string.chatstate_takeover_needs_plugin, e.message.toString()), error = true)
            }
        }
    }

    // ---- attachments ----

    fun addAttachments(uris: List<Uri>) {
        val limit = MAX_ATTACHMENT_BYTES
        uris.forEach { uri ->
            val cr = context.contentResolver
            var name = uri.lastPathSegment ?: tr(R.string.chatstate_file)
            var size = 0L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = c.getLong(1)
                }
            }
            if (size > limit) {
                addSystem(tr(R.string.chatstate_too_big, name, limit / 1_048_576), error = true)
                return@forEach
            }
            attachments += Attachment(uri, name, cr.getType(uri) ?: "application/octet-stream", size)
        }
    }

    /** Stages each attachment: images via image.attach_bytes, other files via file.attach (prompt carries its @file: ref). */
    private suspend fun uploadAttachments(text: String): String? {
        if (attachments.isEmpty()) return text
        val sid = runtimeId ?: return null
        val refs = mutableListOf<String>()
        statusText = tr(R.string.chatstate_uploading)
        try {
            for (a in attachments.toList()) {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(a.uri)?.use { it.readBytes() } } ?: continue
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (a.isImage) {
                    rpc("image.attach_bytes", buildJsonObject { put("session_id", sid); put("content_base64", b64); put("filename", a.name) }, timeoutMs = 120_000)
                } else {
                    val r = rpc("file.attach", buildJsonObject {
                        put("session_id", sid); put("data_url", "data:${a.mime};base64,$b64"); put("name", a.name); put("path", "")
                    }, timeoutMs = 120_000).obj()
                    (r?.str("ref_text")?.takeIf { it.startsWith("@file:") } ?: r?.str("path")?.let { "@file:$it" })?.let(refs::add)
                }
            }
            attachments.clear()
        } catch (e: Exception) {
            addSystem(tr(R.string.chatstate_upload_failed, e.message.toString()), error = true)
            return null
        } finally {
            statusText = null
        }
        return (listOf(text) + refs).filter { it.isNotBlank() }.joinToString("\n")
    }

    // ---- slash commands ----

    private suspend fun loadSlashCatalog() {
        val sid = runtimeId
        runCatching {
            val r = rpc("commands.catalog", sid?.let { buildJsonObject { put("session_id", it) } }).obj() ?: return
            val out = linkedMapOf<String, SlashCommand>()
            (r["categories"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { cat ->
                (cat["pairs"] as? JsonArray).orEmpty().forEach { slashOf(it, cat.str("name"))?.let { c -> out.putIfAbsent(c.name, c) } }
            }
            (r["pairs"] as? JsonArray).orEmpty().forEach { slashOf(it, tr(R.string.chatstate_skills_category))?.let { c -> out.putIfAbsent(c.name, c) } }
            BUILTIN_SLASH.forEach { out.putIfAbsent(it.name, it) }
            slashCommands.clear()
            slashCommands.addAll(out.values.sortedBy { it.name })
        }.onFailure { if (slashCommands.isEmpty()) slashCommands.addAll(BUILTIN_SLASH) }
    }

    private fun slashOf(e: JsonElement, category: String?): SlashCommand? {
        (e as? JsonArray)?.let { pair ->
            val name = (pair.firstOrNull() as? JsonPrimitive)?.contentOrNull?.trimStart('/')?.lowercase() ?: return null
            return SlashCommand(name, (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: tr(R.string.chatstate_hermes_command), category)
        }
        val o = e.obj() ?: return null
        val name = (o.str("name") ?: o.str("command"))?.trimStart('/')?.lowercase() ?: return null
        return SlashCommand(name, o.str("description") ?: o.str("desc") ?: tr(R.string.chatstate_hermes_command), category, o.str("args_hint"))
    }

    private suspend fun runSlash(raw: String) {
        val cleaned = raw.trimStart('/').trim()
        val name = cleaned.substringBefore(' ').lowercase()
        val arg = cleaned.substringAfter(' ', "").trim()
        val sid = runtimeId
        when (name) {
            "new", "reset" -> { openNew(); return }
            "branch", "fork" -> { messages.lastOrNull { it.role == Role.Assistant }?.let { branchFrom(it) }; return }
            "compress", "compact" -> { compress(arg); return }
            "model" -> if (arg.isEmpty()) { showModelPicker = true; return }
            "yolo" -> if (arg.isEmpty()) { runCatching { setOption("yolo", if (yolo) "0" else "1") }; addSystem(tr(if (yolo) R.string.chatstate_yolo_on else R.string.chatstate_yolo_off), label = NOTE_COMMAND); return }
            "background", "bg", "btw" -> if (arg.isNotEmpty() && sid != null) {
                runCatching {
                    rpc(if (name == "btw") "prompt.btw" else "prompt.background", buildJsonObject { put("session_id", sid); put("text", arg) })
                    addSystem(tr(if (name == "btw") R.string.chatstate_btw_started else R.string.chatstate_background_started), label = NOTE_COMMAND)
                }.onFailure { addSystem(tr(R.string.chatstate_command_failed, it.message.toString()), error = true) }
                return
            }
        }
        if (sid == null) { addSystem(tr(R.string.chatstate_still_connecting), error = true); return }
        try {
            val result = try {
                rpc("slash.exec", buildJsonObject { put("session_id", sid); put("command", cleaned) })
            } catch (e: RpcException) {
                rpc("command.dispatch", buildJsonObject { put("session_id", sid); put("name", name); put("arg", arg) })
            }
            handleSlashResult(result.obj() ?: return, arg, depth = 0)
        } catch (e: Exception) {
            addSystem(tr(R.string.chatstate_command_failed, e.message.toString()), error = true)
        }
    }

    private suspend fun handleSlashResult(o: JsonObject, arg: String, depth: Int) {
        val output = o.str("output") ?: o.str("message") ?: o.str("notice") ?: ""
        when (o.str("type")) {
            "send", "skill" -> (o.str("message") ?: output).takeIf { it.isNotBlank() }?.let { submit(it) }
            "prefill" -> {
                o.str("notice")?.takeIf { it.isNotBlank() }?.let { addSystem(it, label = NOTE_COMMAND) }
                draft = o.str("message") ?: output
            }
            "alias" -> if (depth < 3) {
                val target = o.str("target") ?: o.str("command") ?: o.str("name") ?: return
                runSlash("/" + if (arg.isEmpty()) target else "$target $arg")
            }
            else -> if (output.isNotBlank()) addSystem(output, label = NOTE_COMMAND)
        }
    }

    // ---- busy mode, context, model ----

    private suspend fun loadBusyMode() {
        runCatching {
            val v = rpc("config.set", buildJsonObject { put("key", "busy"); put("value", "status") }).obj()?.str("value")?.lowercase()
            busyAction = when (v) { "steer" -> BusyAction.Steer; "interrupt" -> BusyAction.Interrupt; else -> BusyAction.Queue }
        }
    }

    suspend fun contextBreakdown(): JsonObject? {
        val sid = runtimeId ?: return null
        return runCatching { rpc("session.context_breakdown", buildJsonObject { put("session_id", sid) }).obj() }.getOrNull()
    }

    fun compress(focus: String = "") {
        val sid = runtimeId ?: return
        statusText = tr(R.string.chatstate_compressing)
        graph.scope.launch {
            try {
                rpc("session.compress", buildJsonObject { put("session_id", sid); if (focus.isNotBlank()) put("focus_topic", focus) }, timeoutMs = 600_000)
                addSystem(tr(R.string.chatstate_compressed), label = NOTE_COMMAND)
            } catch (e: Exception) {
                addSystem(tr(R.string.chatstate_compress_failed, e.message.toString()), error = true)
            } finally {
                statusText = null
            }
        }
    }

    suspend fun modelOptions(): JsonObject? =
        runCatching { rpc("model.options", buildJsonObject { put("explicit_only", true); runtimeId?.let { put("session_id", it) } }).obj() }.getOrNull()

    /** config.set model; returns a confirmation message when Hermes wants one before switching. */
    private fun liveId() = runtimeId ?: throw IllegalStateException(tr(R.string.chatstate_chat_connecting))

    suspend fun setModel(model: String, provider: String, confirmed: Boolean = false): String? {
        val sid = liveId()
        val r = rpc("config.set", buildJsonObject {
            put("key", "model"); put("session_id", sid); put("value", "$model --provider $provider --session")
            if (confirmed) put("confirm_expensive_model", true)
        }).obj()
        if (r?.bool("confirm_required") == true) return r.str("confirm_message") ?: r.str("warning") ?: tr(R.string.chatstate_confirm_model)
        this.model = r?.str("value")?.substringBefore(" --")?.ifBlank { null } ?: model
        this.provider = provider
        return null
    }

    suspend fun setOption(key: String, value: String) {
        val sid = liveId()
        rpc("config.set", buildJsonObject { put("key", key); put("session_id", sid); put("value", value) })
        when (key) {
            "reasoning" -> reasoning = value
            "fast" -> fast = value == "fast"
            "yolo" -> yolo = value == "1"
        }
    }

    // ---- message actions ----

    /** Quotes text into the composer (a message's "Citar", a Pulse card's "Conversar"). */
    fun quote(text: String) {
        val quoted = text.lines().joinToString("\n") { "> $it" }
        draft = if (draft.isBlank()) "$quoted\n\n" else "$draft\n$quoted\n\n"
    }

    /** message.react: same emoji (or null) retracts. */
    fun react(m: ChatMsg, emoji: String?) {
        val sid = runtimeId ?: return
        val next = if (m.reaction == emoji) null else emoji
        graph.scope.launch {
            runCatching {
                rpc("message.react", buildJsonObject {
                    put("session_id", sid); put("author", USER_AUTHOR)
                    if (next == null) put("emoji", JsonNull) else put("emoji", next)
                    if (m.rowId != null) put("row_id", m.rowId) else put("newest_role", if (m.role == Role.User) "user" else "assistant")
                })
                val i = messages.indexOfFirst { it.key == m.key }
                if (i >= 0) messages[i] = messages[i].copy(reaction = next)
            }.onFailure { addSystem(tr(R.string.chatstate_react_failed, it.message.toString()), error = true) }
        }
    }

    /** Branch: a new session seeded with the transcript up to (and including) this reply. */
    fun branchFrom(m: ChatMsg) {
        val parent = storedId ?: return
        val idx = messages.indexOfFirst { it.key == m.key }.takeIf { it >= 0 } ?: return
        val seed = messages.take(idx + 1).filter { (it.role == Role.User || it.role == Role.Assistant) && it.text.isNotBlank() }
        val branchTitle = tr(R.string.chatstate_branch_title, title.take(60))
        close()
        isOpen = true
        title = branchTitle
        messages.addAll(seed)
        graph.scope.launch {
            create(buildJsonObject {
                put("cols", 96); put("source", "desktop"); put("parent_session_id", parent); put("title", branchTitle)
                put("messages", buildJsonArray { seed.forEach { add(buildJsonObject { put("role", if (it.role == Role.User) "user" else "assistant"); put("content", it.text) }) } })
            })
        }
    }

    // ---- subagents ----

    fun refreshSubagents() {
        val sid = runtimeId ?: return
        graph.scope.launch {
            runCatching {
                val rows = (rpc("subagent.list", buildJsonObject { put("session_id", sid) }).obj()?.get("subagents") as? JsonArray).orEmpty().mapNotNull { it.obj() }
                val known = subagents.associateBy { it.id }
                subagents.clear()
                subagents.addAll(rows.mapNotNull { r ->
                    val id = r.str("subagent_id") ?: return@mapNotNull null
                    Subagent(id, r.str("goal") ?: known[id]?.goal.orEmpty(), r.str("status") ?: "running", known[id]?.text.orEmpty())
                })
            }
        }
    }

    suspend fun tailSubagent(id: String): String {
        val sid = runtimeId ?: return ""
        return runCatching { rpc("subagent.tail", buildJsonObject { put("session_id", sid); put("subagent_id", id) }).obj()?.str("text").orEmpty() }.getOrDefault("")
    }

    fun steerSubagent(id: String, text: String) {
        val sid = runtimeId ?: return
        graph.scope.launch { runCatching { rpc("subagent.steer", buildJsonObject { put("session_id", sid); put("subagent_id", id); put("text", text) }) } }
    }

    fun interruptSubagent(id: String) {
        val sid = runtimeId ?: return
        graph.scope.launch { runCatching { rpc("subagent.interrupt", buildJsonObject { put("session_id", sid); put("subagent_id", id) }) }; refreshSubagents() }
    }

    suspend fun delegationPaused(): Boolean? = runCatching { client.call("delegation.status", scoped = false).obj()?.bool("paused") }.getOrNull()
    suspend fun setDelegationPaused(p: Boolean): Boolean? =
        runCatching { client.call("delegation.pause", buildJsonObject { put("paused", p) }, scoped = false).obj()?.bool("paused") }.getOrNull()

    // ---- requests ----

    fun answerApproval(r: ServerRequest, choice: String) = graph.answer(r, buildJsonObject { put("choice", choice) })
    fun answerSecret(r: ServerRequest, value: String) = graph.answer(r, buildJsonObject { put("value", value) })

    /**
     * Clarify: a single question answers with the response frame; batch questions lock one by one
     * through clarify.lock (the last lock resolves the request).
     */
    fun answerClarify(r: ServerRequest, answers: Map<String, String>, batch: Boolean) {
        if (!batch) {
            graph.answer(r, buildJsonObject { put("answer", answers.values.firstOrNull().orEmpty()) })
            return
        }
        graph.scope.launch {
            for ((qid, answer) in answers) {
                runCatching { rpc("clarify.lock", buildJsonObject { put("request_id", r.id); put("question_id", qid); put("answer", answer) }) }
                    .onFailure { addSystem(tr(R.string.chatstate_answer_failed, it.message.toString()), error = true); return@launch }
            }
            graph.requests.remove(r)
        }
    }

    // ---- events ----

    private fun startTurn() {
        running = true
        turnHadText = false
        current = -1
        turnStartedAt = System.currentTimeMillis()
    }

    private fun onEvent(e: HermesEvent) {
        val p = e.payload
        if (e.type == "session.title" && p.str("session_id") == storedId) p.str("title")?.takeIf { it != BOT_CHAT_TITLE }?.let { title = it }
        if (!isOpen || e.sessionId.isEmpty() || e.sessionId != runtimeId) return
        when (e.type) {
            "message.start" -> if (!running) startTurn() else { turnHadText = false; current = -1 }
            "message.delta" -> p.str("text")?.takeIf { it.isNotEmpty() }?.let { appendAssistant(text = it) }
            "reasoning.delta", "message.reasoning", "message.reasoning.delta" ->
                (p.str("reasoning") ?: p.str("reasoning_content") ?: p.str("text") ?: p.str("content"))?.let { appendAssistant(reasoning = it) }
            "message.interim" -> if (p.bool("already_streamed") != true) {
                p.str("text")?.takeIf { it.isNotBlank() }?.let { appendAssistant(text = it) }
                current = -1
            }
            "tool.start", "tool_call" -> {
                val args = p.str("args_text") ?: p.str("context") ?: p["args"]?.toString().orEmpty()
                messages += msg(Role.Tool, "").copy(toolName = p.str("name").orEmpty(), toolId = p.str("tool_id").orEmpty(), args = args, done = false)
                current = -1
            }
            "tool.complete", "tool_result" -> {
                val i = messages.indexOfLast { it.role == Role.Tool && it.toolId == p.str("tool_id") }
                if (i >= 0) {
                    val raw = p["result"]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }
                    val out = p.str("output") ?: raw ?: p.str("result_text") ?: p.str("summary").orEmpty()
                    messages[i] = messages[i].copy(result = out, text = p.str("summary").orEmpty(), done = true)
                }
            }
            "message.complete" -> {
                running = false
                storedId?.let { sid -> graph.scope.launch { app.hermesandroid.push.Progress.endFor(context, graph.push, sid) } }
                when (p.str("status")) {
                    "error" -> addSystem(p.str("error") ?: tr(R.string.chatstate_hermes_error), error = true)
                    "interrupted" -> addSystem(tr(R.string.chatstate_stopped))
                    else -> {
                        val text = p.str("text") ?: p.str("content") ?: p.str("rendered")
                        val previewed = p.bool("response_previewed") == true || p.bool("response_reused") == true
                        if (!turnHadText && !previewed && !text.isNullOrBlank()) appendAssistant(text = text)
                        controlStep()
                    }
                }
                messages.replaceAll { if (it.role == Role.Tool && !it.done) it.copy(done = true) else it }
                current = -1
                storedId?.let(::writeCache)
            }
            "error" -> addSystem(p.str("message") ?: "Error", error = true)
            "status.update" -> statusText = p.str("text")?.takeIf { p.str("kind") != "compacted" }
            "session.info" -> {
                p.bool("running")?.let { running = it }
                applyInfo(p)
            }
            "model.update" -> { p.str("model")?.let { model = it }; p.str("provider")?.let { provider = it } }
            "session.usage" -> p["usage"].obj()?.let { u -> contextPercentOf(u)?.let { contextPercent = it } }
            "message.reaction" -> {
                val row = p.num("row_id")?.toLong()
                val emoji = (p["reactions"] as? JsonArray)?.firstNotNullOfOrNull { it.obj()?.takeIf { r -> r.str("author") == USER_AUTHOR }?.str("emoji") }
                val i = messages.indexOfFirst { it.rowId == row }
                if (i >= 0) messages[i] = messages[i].copy(reaction = emoji)
            }
            "background.complete", "btw.complete" -> (p.str("text") ?: p.str("result"))?.let { addSystem(it, label = if (e.type == "btw.complete") NOTE_BTW else NOTE_BACKGROUND) }
        }
        if (e.type.startsWith("subagent.")) onSubagentEvent(p)
    }

    private fun onSubagentEvent(p: JsonObject) {
        val id = p.str("subagent_id") ?: return
        val i = subagents.indexOfFirst { it.id == id }
        val old = subagents.getOrNull(i)
        val next = Subagent(id, p.str("goal") ?: old?.goal.orEmpty(), p.str("status") ?: old?.status ?: "running", ((old?.text.orEmpty()) + p.str("text").orEmpty()).takeLast(4000))
        if (i >= 0) subagents[i] = next else subagents += next
    }

    private fun appendAssistant(text: String = "", reasoning: String = "") {
        if (text.isNotEmpty()) turnHadText = true
        val m = messages.getOrNull(current)
        if (m == null || m.role != Role.Assistant) {
            messages += msg(Role.Assistant, text).copy(reasoning = reasoning, time = System.currentTimeMillis())
            current = messages.lastIndex
        } else {
            messages[current] = m.copy(text = m.text + text, reasoning = m.reasoning + reasoning)
        }
    }

    private fun msg(role: Role, text: String) = ChatMsg(key = nextKey++, role = role, text = text)

    /** System / command notes; `label` (a NOTE_* key, or the text itself) replaces "SYSTEM" (toolName carries it). */
    private fun addSystem(text: String, error: Boolean = false, label: String? = null) {
        messages += msg(if (error) Role.Error else Role.System, text).copy(toolName = label.orEmpty(), time = System.currentTimeMillis())
        current = -1
    }

    // ---- offline cache: last messages of recently opened chats ----

    private fun cacheFile(stored: String) = File(context.filesDir, "chat-cache/${graph.profile}/${stored.hashCode().toUInt()}.json")

    private fun writeCache(stored: String) {
        val snapshot = messages.filter { it.role != Role.Error }.takeLast(CACHE_MESSAGES)
        if (snapshot.isEmpty()) return
        graph.scope.launch(Dispatchers.IO) {
            runCatching { cacheFile(stored).apply { parentFile?.mkdirs() }.writeText(json.encodeToString(ListSerializer(ChatMsg.serializer()), snapshot)) }
        }
    }

    private fun readCache(stored: String): List<ChatMsg>? =
        runCatching { json.decodeFromString(ListSerializer(ChatMsg.serializer()), cacheFile(stored).readText()) }.getOrNull()

    companion object {
        const val PAGE = 120
        const val CACHE_MESSAGES = 200
        const val MAX_ATTACHMENT_BYTES = 16L * 1024 * 1024
        const val USER_AUTHOR = "user"
        const val STEER_MARK = "steer"
        const val BOT_CHAT_TITLE = "Bot Chat"
        /** System-note labels kept in toolName; ChatScreen shows each in the phone's language. */
        const val NOTE_COMMAND = "command"
        const val NOTE_BTW = "btw"
        const val NOTE_BACKGROUND = "background"

        /** "Ask your helpers" (composer's + menu): the agent's helper profiles weigh in before it answers (host skill helper-team). */
        const val COUNCIL_RULES = """[Helper council on. Before answering, ask the helper profiles that fit this question (skill helper-team), several in parallel: one-shot counsel with `hermes -p <id> chat -Q --oneshot -q "Counsel. …"`, or a Kanban task (`--assignee <id>`) when it needs research with sources. Name the profile in the command itself. Build the best answer from what they say; if one of them is a reviewer, have it check the answer before you deliver it. Reply with the synthesis and one line per helper on what it added or objected to, in the user's language.]"""

        /** The user's words from a stored turn: drops the rules and screen sent before them in a <system-reminder>. */
        fun userWords(t: String): String =
            if (t.startsWith("<system-reminder>")) t.substringAfter("</system-reminder>", t).trim() else t


        // A getter: built in the phone's current language, and never while the companion loads (plain JVM tests).
        val BUILTIN_SLASH get() = listOf(
            SlashCommand("new", tr(R.string.chatstate_slash_new), null),
            SlashCommand("branch", tr(R.string.chatstate_slash_branch), null),
            SlashCommand("compress", tr(R.string.chatstate_slash_compress), null, tr(R.string.chatstate_slash_compress_hint)),
            SlashCommand("background", tr(R.string.chatstate_slash_background), null, tr(R.string.chatstate_slash_text_hint)),
            SlashCommand("btw", tr(R.string.chatstate_slash_btw), null, tr(R.string.chatstate_slash_text_hint)),
            SlashCommand("model", tr(R.string.chatstate_slash_model), null),
            SlashCommand("yolo", tr(R.string.chatstate_slash_yolo), null),
            SlashCommand("help", tr(R.string.chatstate_slash_help), null),
            SlashCommand("status", tr(R.string.chatstate_slash_status), null),
            SlashCommand("usage", tr(R.string.chatstate_slash_usage), null),
            SlashCommand("retry", tr(R.string.chatstate_slash_retry), null),
            SlashCommand("undo", tr(R.string.chatstate_slash_undo), null),
            SlashCommand("stop", tr(R.string.chatstate_slash_stop), null),
        )
    }
}

/** Message content is a string or a list of parts with `text`. */
fun textOf(e: JsonElement?): String = when (e) {
    is JsonPrimitive -> e.contentOrNull.orEmpty()
    is JsonArray -> e.joinToString("") { part -> (part as? JsonPrimitive)?.contentOrNull ?: part.obj()?.str("text").orEmpty() }
    is JsonObject -> e.str("text") ?: e.toString()
    else -> ""
}

/** Context use, 0–100: Hermes' context_percent is already a percent (an int: 1 is 1 %), else used / max. */
internal fun contextPercentOf(o: JsonObject): Int? {
    o.num("context_percent")?.takeIf { it > 0 }?.let { return it.toInt().coerceIn(0, 100) }
    val max = o.num("context_max")?.takeIf { it > 0 } ?: return null
    return ((o.num("context_used") ?: 0.0) / max * 100).toInt().coerceIn(0, 100)
}
