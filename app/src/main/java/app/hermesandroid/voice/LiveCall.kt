package app.hermesandroid.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.control.CONTROL_RULES
import app.hermesandroid.control.PhoneAgent
import app.hermesandroid.control.parseReply
import app.hermesandroid.net.HermesEvent
import app.hermesandroid.net.bool
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.tr
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// A Gemini Live call (GeminiLiveConversationController + GeminiLiveSession + GeminiLiveToolBridge): the plugin
// mints an ephemeral token, the phone streams 16 kHz PCM to Google's Live API and plays its 24 kHz replies.
// Gemini answers small things itself and hands real work to Hermes as background jobs (one chat each),
// which it hears back about when they finish or need the user's OK.

/** The Live API messages this client sends (GeminiLiveProtocol). */
object LiveProtocol {
    /** Google Search grounding needs a billed Gemini project (the free tier closes the socket with 1011), so it's opt-in. */
    fun setup(model: String, system: String, voice: String, tools: JsonArray, googleSearch: Boolean = false) = buildJsonObject {
        putJsonObject("setup") {
            put("model", if (model.startsWith("models/")) model else "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") { putJsonObject("voiceConfig") { putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) } } }
            }
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("tools") {
                add(buildJsonObject { put("functionDeclarations", tools) })
                if (googleSearch) add(buildJsonObject { putJsonObject("googleSearch") {} })
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") { put("disabled", false) }
                put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            }
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
        }
    }

    /** The setup with session resumption on: `handle` (Gemini's latest) carries the conversation into a new connection. */
    fun resuming(setup: JsonObject, handle: String?): JsonObject = JsonObject(mapOf("setup" to JsonObject(
        setup["setup"].obj().orEmpty() + ("sessionResumption" to buildJsonObject { handle?.let { put("handle", it) } }))))

    /** Google no longer has the session a handle named: start fresh instead. */
    fun resumptionLost(reason: String) = reason.lowercase().let { r -> listOf("session not found", "session expired", "session has expired", "expired session").any { it in r } }

    /** Closes worth no retry: a bad request, auth or policy refusal, or a spent quota. */
    fun permanent(code: Int, reason: String) = code in setOf(1003, 1007, 1008) || code in 4000..4999 ||
        reason.lowercase().replace('_', ' ').let { "exceeded your current quota" in it || "resource exhausted" in it || "resource has been exhausted" in it }

    fun audio(pcm: ByteArray) = buildJsonObject {
        putJsonObject("realtimeInput") { putJsonObject("audio") { put("mimeType", "audio/pcm;rate=16000"); put("data", Base64.encodeToString(pcm, Base64.NO_WRAP)) } }
    }

    /** A user-role text turn; `complete` makes Gemini answer it out loud. */
    fun text(text: String, complete: Boolean) = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", text) }) } }) }
            put("turnComplete", complete)
        }
    }

    fun toolResponse(id: String, name: String, result: JsonObject) = buildJsonObject {
        putJsonObject("toolResponse") { putJsonArray("functionResponses") { add(buildJsonObject { put("id", id); put("name", name); put("response", result) }) } }
    }

    fun function(name: String, description: String, params: Map<String, String>, required: List<String> = params.keys.toList(), blocking: Boolean = true) = buildJsonObject {
        put("name", name); put("description", description)
        putJsonObject("parameters") {
            put("type", "OBJECT")
            putJsonObject("properties") { params.forEach { (k, d) -> putJsonObject(k) { put("type", "STRING"); put("description", d) } } }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
        put("behavior", if (blocking) "BLOCKING" else "NON_BLOCKING")
    }

    data class Call(val id: String, val name: String, val args: Map<String, String>)

    /** What one server message carries that the call acts on. */
    data class Server(
        val setupComplete: Boolean = false, val audio: List<ByteArray> = emptyList(), val heard: String? = null, val said: String? = null,
        val interrupted: Boolean = false, val turnComplete: Boolean = false, val calls: List<Call> = emptyList(), val goAway: Boolean = false,
        /** A new resumable handle (sessionResumptionUpdate with resumable = true). */
        val handle: String? = null,
    )

    fun decode(text: String): Server {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).obj() }.getOrNull() ?: return Server()
        val content = o["serverContent"].obj()
        val audio = (content?.get("modelTurn").obj()?.get("parts") as? JsonArray).orEmpty().mapNotNull { p ->
            p.obj()?.get("inlineData").obj()?.takeIf { it.str("mimeType")?.startsWith("audio/pcm") == true }?.str("data")?.let { Base64.decode(it, Base64.DEFAULT) }
        }
        val calls = (o["toolCall"].obj()?.get("functionCalls") as? JsonArray).orEmpty().mapNotNull { c ->
            val co = c.obj() ?: return@mapNotNull null
            Call(co.str("id") ?: return@mapNotNull null, co.str("name") ?: return@mapNotNull null,
                co["args"].obj().orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() })
        }
        return Server(
            setupComplete = o.containsKey("setupComplete"), audio = audio,
            heard = content?.get("inputTranscription").obj()?.str("text"), said = content?.get("outputTranscription").obj()?.str("text"),
            interrupted = content?.bool("interrupted") == true, turnComplete = content?.bool("turnComplete") == true,
            calls = calls, goAway = o.containsKey("goAway"),
            handle = o["sessionResumptionUpdate"].obj()?.takeIf { it.bool("resumable") == true }?.str("newHandle")?.takeIf { it.isNotEmpty() },
        )
    }
}

/** A Hermes job started from the call: its background chat and how it's going (`status` is also what Gemini hears). */
data class VoiceJob(val n: Int, val request: String, val sessionId: String, val storedId: String?, val status: String = "working", val result: String = "")

/** A job's status as the call screens show it. */
@StringRes fun jobStatusLabel(status: String): Int = when (status) {
    "done" -> R.string.livecall_job_done
    "error" -> R.string.livecall_job_error
    "awaiting approval" -> R.string.livecall_job_approval
    "call reason" -> R.string.livecall_job_reason
    else -> R.string.livecall_job_working
}

class LiveCall(private val graph: AppGraph, private val context: Context) {
    enum class State { Idle, Connecting, Listening, Speaking, Reconnecting, Ended }

    var state by mutableStateOf(State.Idle)
        private set
    var muted by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** Running transcript: (true = the user) to text. */
    val lines = mutableStateListOf<Pair<Boolean, String>>()
    /** When the current call began (the call screen's clock). */
    var startedAt = 0L
        private set
    val jobs = mutableStateListOf<VoiceJob>()
    /** Where the last call's transcript was saved (a chat), once it is. */
    var savedSession by mutableStateOf<String?>(null)
        private set
    var savedTitle by mutableStateOf("")
        private set
    private var callId = ""
    val active get() = state != State.Idle && state != State.Ended

    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    /** The connection taking over after a GoAway; the old one keeps serving until this one is set up. */
    private var handoff: WebSocket? = null
    /** The setup without its resumption part (built once per call), and Gemini's latest resumable handle. */
    private var setupBase: JsonObject? = null
    private var handle: String? = null
    /** The current socket's setup is complete: audio before that is dropped, other messages wait in [queued]. */
    @Volatile private var ready = false
    private val queued = mutableListOf<String>()
    private var reconnects = 0
    private var freshAfterLost = false
    private var mic: Job? = null
    private var track: AudioTrack? = null
    /**
     * Gemini's speech waiting for the AudioTrack, written on its own thread: a blocking write on the socket's thread
     * held every later message behind it, so an `interrupted` (barge-in) waited for seconds of already-sent speech.
     */
    @Volatile private var speech: kotlinx.coroutines.channels.Channel<ByteArray>? = null
    private var speaker: Job? = null
    private val unplayed = java.util.concurrent.atomic.AtomicInteger()
    private var audioManager = context.getSystemService(AudioManager::class.java)
    private val jobText = mutableMapOf<String, StringBuilder>()
    /** Phone-control steps each job has taken (see control/PhoneAgent). */
    private val controlSteps = mutableMapOf<String, Int>()
    /** Gemini said goodbye (end_call): hang up once its last words have played. */
    private var endAfterTurn = false
    /** Last time anyone spoke; a silent call ends on its own. */
    private var lastActivity = 0L
    private var idleWatch: Job? = null

    init {
        graph.listen(::onHermesEvent)
    }

    /** The call from Hermes this conversation answers, if it is one. */
    private var incoming: IncomingCall? = null
    /** Microphone and speaker somewhere else (the watch); null = this phone's. */
    private var remote: RemoteAudio? = null

    fun start(call: IncomingCall? = null, remote: RemoteAudio? = null) {
        if (active) return
        incoming = call
        this.remote = remote
        lines.clear(); jobs.clear(); error = null; muted = false; endAfterTurn = false; startedAt = System.currentTimeMillis()
        handle = null; ready = false; queued.clear(); reconnects = 0; freshAfterLost = false; handoff = null
        savedSession = null; callId = "android-" + java.util.UUID.randomUUID()
        state = State.Connecting
        // From the watch the phone is usually in a pocket with the app closed: Android may refuse a background
        // foreground service, and the call then lives as long as the watch's channel keeps the process up.
        runCatching { LiveCallService.start(context, watch = remote != null) }.onFailure { android.util.Log.w("LiveCall", "foreground service: ${it.message}") }
        graph.location.onCall()
        graph.scope.launch {
            try {
                val c = graph.client
                val tok = mint()
                val persona = runCatching { c.rest("GET", c.withProfile("/api/plugins/conduit_push/personality")).obj() }.getOrNull()?.takeIf { it.bool("available") == true }?.str("text")
                val memory = runCatching { c.rest("GET", c.withProfile("/api/plugins/conduit_push/memory/context")).obj() }.getOrNull()?.takeIf { it.bool("available") == true }
                val search = runCatching { c.rest("GET", c.withProfile("/api/plugins/conduit_push/web-search/status")).obj() }.getOrNull()?.let { it.bool("ok") == true && it.bool("available") == true } == true
                setupBase = LiveProtocol.setup(tok.str("model") ?: graph.live.model ?: "gemini-3.8-live", systemPrompt(persona, memory?.str("context")), graph.store.liveVoice, tools(memory?.bool("recall") == true, search))
                connect(tok)
            } catch (e: Exception) {
                fail(tr(R.string.livecall_start_failed, e.message.orEmpty()))
            }
        }
    }

    /** A fresh single-use token from Hermes: every connection, first or resumed, needs its own. */
    private suspend fun mint(): JsonObject {
        val c = graph.client
        val tok = c.rest("POST", c.withProfile("/api/plugins/conduit_push/gemini-live/token"), JsonObject(emptyMap()), timeoutSec = 25).obj()
        if (tok?.bool("ok") != true) throw IllegalStateException(tok?.str("reason") ?: tok?.str("error") ?: tr(R.string.livecall_no_token))
        return tok
    }

    /** Opens a connection that resumes the conversation when there's a handle. `asHandoff`: the current one keeps serving. */
    private suspend fun connect(tok: JsonObject? = null, asHandoff: Boolean = false) {
        val t = tok ?: mint()
        val token = t.str("token")!!
        val wsUrl = t.str("websocket_url")!!.let { if ("access_token=" in it) it else it + (if ('?' in it) "&" else "?") + "access_token=" + java.net.URLEncoder.encode(token, "UTF-8") }
        val setup = LiveProtocol.resuming(setupBase ?: return, handle).toString()
        if (!active) return
        val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), Listener(setup))
        if (asHandoff) handoff = ws else { ready = false; socket = ws }
    }

    /** The connection dropped mid-call: resume up to 3 times (1, 2, 4 s apart); a session Google lost starts fresh once. */
    private fun dropped(why: String, code: Int = 0, reason: String = "") {
        if (!active) return
        ready = false
        val lost = LiveProtocol.resumptionLost(reason) && handle != null && !freshAfterLost
        if (lost) { handle = null; freshAfterLost = true }
        else if (LiveProtocol.permanent(code, reason) || reconnects >= 3) { fail(why); return }
        else reconnects++
        android.util.Log.i("LiveCall", "dropped ($why), reconnecting: attempt $reconnects, resume=${handle != null}")
        state = State.Reconnecting
        graph.scope.launch {
            if (!lost) kotlinx.coroutines.delay(1000L shl (reconnects - 1))
            runCatching { connect() }.onFailure { dropped(tr(R.string.livecall_reconnect_failed, it.message.orEmpty())) }
        }
    }

    fun hangUp() {
        if (state == State.Idle) return
        if (state != State.Ended) graph.scope.launch { presence(0) }
        idleWatch?.cancel(); idleWatch = null
        state = State.Ended
        socket?.close(1000, "bye"); socket = null
        handoff?.close(1000, "bye"); handoff = null
        ready = false; queued.clear()
        stopAudio()
        LiveCallService.stop(context)
        if (graph.store.saveCalls) save()
    }

    /** The transcript becomes a chat in Hermes (`/voice/sessions`). */
    private fun save() {
        val turns = lines.filter { it.second.isNotBlank() }
        if (turns.none { it.first }) return
        val title = tr(R.string.livecall_saved_title, turns.first { it.first }.second.trim().take(60))
        graph.scope.launch {
            val r = runCatching {
                graph.client.rest("POST", graph.client.withProfile("/api/plugins/conduit_push/voice/sessions"), buildJsonObject {
                    put("call_id", callId); put("engine", "gemini-live"); put("title", title)
                    put("turns", buildJsonArray { turns.take(500).forEachIndexed { i, (user, text) -> add(buildJsonObject { put("role", if (user) "user" else "assistant"); put("text", text.trim()); put("index", i) }) } })
                }, timeoutSec = 30).obj()
            }
            r.onSuccess { o -> savedSession = o?.str("session_id"); savedTitle = title; graph.chats.refresh() }
                .onFailure { error = (error?.let { "$it " } ?: "") + tr(R.string.livecall_save_failed, it.message.orEmpty()) }
        }
    }

    private fun fail(message: String) {
        android.util.Log.w("LiveCall", "fail: $message")
        error = message
        hangUp()
    }

    /** Tool answers and text turns wait for a reconnect's setup; audio meanwhile is dropped. */
    private fun send(o: JsonObject) { val s = o.toString(); if (ready) socket?.send(s) else if (active) queued += s }

    private fun sendAudio(pcm: ByteArray) { if (ready && !muted) socket?.send(LiveProtocol.audio(pcm).toString()) }

    private fun systemPrompt(persona: String?, memory: String?): String = buildString {
        // The language picked in Settings > Voice (or the phone's), by its English name: "Spanish", "English"…
        val language = java.util.Locale.forLanguageTag(graph.store.speechTag).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "the user's language" }
        append("You are the user's personal assistant, talking with them by voice from their phone. ")
        append("Your name and character are in your personality, below: always introduce yourself by that name, never as Hermes, Gemini or a Google model. ")
        append("Always speak $language, in short, natural sentences, as on a phone call. ")
        append("In this call you don't have their files, accounts or tools at hand: for anything that needs data or actions ")
        append("(their finances, calendar, email, files, tasks, purchases, scheduling something) use start_hermes_job, which does it in the background with all your tools, and tell them you're on it. ")
        if (PhoneAgent.available) append("Hermes can also operate their phone (open apps, read the screen, tap and type): ask it with start_hermes_job. ")
        append("When a job finishes you'll get its result: sum it up out loud in a few sentences. ")
        append("If a job asks for approval, explain what it wants to do and ask; only use answer_approval after a clear yes or no from the user. ")
        append("When the user says goodbye or says they need nothing else, say goodbye in one short sentence and call end_call. ")
        append("For news or anything from the internet use web_search if you have it; otherwise ask Hermes to look it up with start_hermes_job.")
        if (!persona.isNullOrBlank()) append("\n\nYour personality:\n").append(persona.take(6000))
        if (!memory.isNullOrBlank()) append("\n\nWhat you remember about the user:\n").append(memory.take(6000))
    }

    private fun tools(recall: Boolean, search: Boolean) = buildJsonArray {
        if (search) add(LiveProtocol.function("web_search", "Searches the internet with Hermes' search engine and returns the top results (title, link and snippet). For news, facts or anything current.", mapOf("query" to "What to search for.")))
        add(LiveProtocol.function("start_hermes_job",
            "Asks Hermes (the user's agent, with access to their tools, files, accounts and memory) to do a task in a background chat. Use it for anything that needs real data or actions. Pass the full, clear request, in the user's language.",
            mapOf("request" to "What Hermes has to do, with all the context it needs."), blocking = false))
        add(LiveProtocol.function("end_call", "Hangs up the call. Use it when the user says goodbye or says they need nothing else, right after saying goodbye.", emptyMap()))
        add(LiveProtocol.function("call_me_when_done", "Asks Hermes to phone the user when a job from this call finishes (if the user asks for it, or is hanging up before it's done).",
            mapOf("job" to "The job's number.")))
        add(LiveProtocol.function("list_hermes_jobs", "Lists this call's jobs and how they're going.", emptyMap()))
        add(LiveProtocol.function("answer_approval", "Answers the approval a job is waiting for, only after a clear yes or no from the user.",
            mapOf("job" to "The job's number.", "choice" to "once to approve once, deny to refuse.")))
        if (recall) add(LiveProtocol.function("recall_memory", "Searches Hermes' memory for something specific about the user.", mapOf("query" to "What to search for.")))
    }

    // ---- audio ----

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        remote?.let { r -> r.open { pcm -> sendAudio(pcm) }; return }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // On the speaker unless a headset or Bluetooth device is connected.
        val devices = audioManager.availableCommunicationDevices
        val headset = devices.firstOrNull { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET) }
        (headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })?.let { audioManager.setCommunicationDevice(it) }
        val outRate = 24_000
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(outRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
        val out = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { speech = it }
        val player = track!!
        speaker = graph.scope.launch(Dispatchers.IO) {
            for (pcm in out) { meter(pcm, pcm.size); runCatching { player.write(pcm, 0, pcm.size) }; unplayed.decrementAndGet() }
        }
        val inRate = 16_000
        val chunk = inRate / 25 // 40 ms
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 2) * 2)
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        mic = graph.scope.launch(Dispatchers.IO) {
            val buf = ByteArray(chunk * 2)
            rec.startRecording()
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) { if (!muted && state != State.Speaking) meter(buf, n); sendAudio(buf.copyOf(n)) }
                }
            } finally { rec.stop(); rec.release() }
        }
    }

    /** Loudness of what's said or heard right now (0..1), read every frame by the call screen's rings. */
    @Volatile var level = 0f
        private set

    /** RMS of a PCM16 little-endian chunk, scaled so normal speech fills most of the range. */
    private fun meter(pcm: ByteArray, n: Int) {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) { val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble(); sum += s * s; i += 2 }
        level = (kotlin.math.sqrt(sum / maxOf(1, n / 2)) / 32768.0 * 5).toFloat().coerceIn(0f, 1f)
    }

    private fun stopAudio() {
        level = 0f
        remote?.let { it.close(); remote = null; return }
        mic?.cancel(); mic = null
        speech?.close(); speech = null; speaker?.cancel(); speaker = null; unplayed.set(0)
        track?.runCatching { pause(); flush(); release() }; track = null
        audioManager.clearCommunicationDevice()
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private inner class Listener(private val setup: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setup) }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text, webSocket)
        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handle(bytes.utf8(), webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            graph.scope.launch {
                when {
                    handoff === webSocket -> handoff = null // the old connection carries on; its own end resumes later
                    socket === webSocket -> dropped(tr(R.string.livecall_cut_off, t.message ?: response?.message.orEmpty()), response?.code ?: 0, response?.message.orEmpty())
                }
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            graph.scope.launch {
                when {
                    handoff === webSocket -> handoff = null
                    // Any close while the call is on (a session's time limit, a dropout) resumes; hanging up closed ours first.
                    socket === webSocket -> dropped(tr(R.string.livecall_gemini_closed, if (reason.isNotBlank()) "$code: $reason" else "$code"), code, reason)
                }
            }
        }
    }

    private fun handle(text: String, from: WebSocket) {
        val m = LiveProtocol.decode(text)
        if (from === handoff) {
            // The GoAway handoff's connection is set up: it takes over, and the old one goes.
            graph.scope.launch {
                m.handle?.let { handle = it }
                if (m.setupComplete && from === handoff) {
                    val old = socket
                    socket = from; handoff = null; ready = true; reconnects = 0
                    old?.close(1000, "handoff")
                    flushQueued()
                    android.util.Log.i("LiveCall", "handoff done")
                }
            }
            return
        }
        if (from !== socket) return
        // Here on the socket's thread, in message order: from a coroutine it could also drop the next turn's speech.
        if (m.interrupted) {
            remote?.flush()
            while (speech?.tryReceive()?.isSuccess == true) unplayed.decrementAndGet()
            track?.runCatching { pause(); flush(); play() }
        }
        m.audio.forEach { pcm ->
            val r = remote
            if (r != null) r.play(pcm) else speech?.let { unplayed.incrementAndGet(); if (it.trySend(pcm).isFailure) unplayed.decrementAndGet() }
        }
        graph.scope.launch {
            m.handle?.let { handle = it }
            if (m.setupComplete) {
                ready = true; reconnects = 0
                if (state == State.Connecting) { state = State.Listening; startAudio(); watchIdle(); presence(150); incoming?.let { greetIncoming(it) } }
                else { state = State.Listening; android.util.Log.i("LiveCall", "resumed") }
                flushQueued()
            }
            if (m.heard != null || m.audio.isNotEmpty()) lastActivity = System.currentTimeMillis()
            if (m.audio.isNotEmpty() && state == State.Listening) state = State.Speaking
            m.heard?.let { append(true, it) }
            m.said?.let { append(false, it) }
            // Generation ends before playback now: "speaking" lasts until the queued speech has played.
            if (m.turnComplete && state == State.Speaking) graph.scope.launch { untilPlayed(); if (state == State.Speaking) state = State.Listening }
            if (m.turnComplete && endAfterTurn) hangUpWhenPlayed()
            m.calls.forEach { runTool(it) }
            // Google ends each connection after a while: a new one resumes the conversation before this one goes.
            if (m.goAway && handoff == null && active) {
                android.util.Log.i("LiveCall", "goAway: handing off, resume=${handle != null}")
                runCatching { connect(asHandoff = true) }.onFailure { android.util.Log.w("LiveCall", "handoff: ${it.message}") }
            }
        }
    }

    private fun flushQueued() {
        val pending = queued.toList(); queued.clear()
        pending.forEach { socket?.send(it) }
    }

    /** Transcription comes in fragments: extend the last line of the same speaker. */
    private fun append(user: Boolean, text: String) {
        val last = lines.lastOrNull()
        if (last != null && last.first == user) lines[lines.lastIndex] = user to (last.second + text) else lines += user to text.trimStart()
    }

    // ---- tools ----

    private fun respond(call: LiveProtocol.Call, vararg pairs: Pair<String, String>) =
        send(LiveProtocol.toolResponse(call.id, call.name, JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })))

    private suspend fun runTool(call: LiveProtocol.Call) {
        when (call.name) {
            "start_hermes_job" -> {
                val request = call.args["request"].orEmpty().trim()
                if (request.isEmpty()) { respond(call, "error" to "Missing the request."); return }
                runCatching {
                    val r = graph.client.call("session.create", buildJsonObject { put("cols", 96); put("source", "desktop") }).obj()
                    val sid = r?.str("session_id") ?: throw IllegalStateException("no session")
                    val job = VoiceJob(jobs.size + 1, request, sid, r.str("stored_session_id"))
                    jobs += job
                    jobText[sid] = StringBuilder()
                    // With phone control on, the job gets the rules and the screen, as in the app's chats.
                    val wire = PhoneAgent.screenBlock()?.let { "$CONTROL_RULES\n$it\n$request" } ?: request
                    graph.scope.launch {
                        runCatching { graph.client.call("prompt.submit", buildJsonObject { put("session_id", sid); put("text", wire) }, timeoutMs = 180_000) }
                            .onFailure { finishJob(sid, "error", "Couldn't send it: ${it.message}") }
                    }
                    job
                }.onSuccess { respond(call, "status" to "started", "job" to it.n.toString(), "note" to "Job ${it.n} started. You'll hear when it finishes.") }
                    .onFailure { respond(call, "error" to "Couldn't start it: ${it.message}") }
            }
            "end_call" -> { respond(call, "status" to "hanging up"); endAfterTurn = true; graph.scope.launch { kotlinx.coroutines.delay(8000); if (endAfterTurn) hangUpWhenPlayed() } }
            "call_me_when_done" -> {
                val job = jobs.firstOrNull { it.n.toString() == call.args["job"] }
                if (job == null) { respond(call, "error" to "No job has that number."); return }
                val r = runCatching {
                    graph.client.rest("POST", graph.client.withProfile("/api/plugins/conduit_push/calls/watches"), buildJsonObject {
                        put("session_ids", buildJsonArray { listOfNotNull(job.sessionId, job.storedId).distinct().forEach { add(JsonPrimitive(it)) } })
                        put("title", job.request.take(120)); put("hold_s", 0)
                    }).obj()
                }
                r.onSuccess { respond(call, "status" to (it?.str("status") ?: "watching")) }
                    .onFailure { e -> respond(call, "error" to if (e.message?.contains("off", true) == true) "Calls from Hermes are off: the user turns them on in Settings > Voice." else "Couldn't: ${e.message}") }
            }
            "list_hermes_jobs" -> respond(call, "jobs" to (jobs.joinToString("\n") { "${it.n}. ${it.request.take(80)} — ${it.status}" }.ifEmpty { "No jobs yet." }))
            "answer_approval" -> {
                val job = jobs.firstOrNull { it.n.toString() == call.args["job"] }
                // A call about an approval names the chat by another id than the request: one waiting approval is that one.
                val req = job?.let { j -> graph.requests.firstOrNull { it.method == "approval" && it.params.str("session_id") == j.sessionId } }
                    ?: graph.requests.filter { it.method == "approval" }.singleOrNull()
                if (req == null) { respond(call, "error" to "That job isn't waiting for any approval."); return }
                val choice = if (call.args["choice"] == "deny") "deny" else "once"
                graph.answer(req, buildJsonObject { put("choice", choice) })
                respond(call, "status" to if (choice == "deny") "denied" else "approved")
            }
            "web_search" -> {
                val r = runCatching { graph.client.rest("POST", graph.client.withProfile("/api/plugins/conduit_push/web-search"), buildJsonObject { put("query", call.args["query"].orEmpty()); put("limit", 3) }, timeoutSec = 25).obj() }.getOrNull()
                val results = (r?.get("results") as? JsonArray).orEmpty().mapNotNull { it.obj() }.joinToString("\n") { "${it.str("title").orEmpty()} — ${it.str("snippet").orEmpty()} (${it.str("url").orEmpty()})" }
                respond(call, "results" to results.ifBlank { r?.str("detail") ?: r?.str("error") ?: "No results." })
            }
            "recall_memory" -> {
                val r = runCatching { graph.client.rest("POST", graph.client.withProfile("/api/plugins/conduit_push/memory/recall"), buildJsonObject { put("query", call.args["query"].orEmpty()) }, timeoutSec = 15).obj() }.getOrNull()
                respond(call, "results" to (r?.str("results")?.take(4000) ?: "Nothing found."))
            }
            else -> respond(call, "error" to "Unknown tool.")
        }
    }

    /** "The user is in a call for hold_s more seconds" (0 = not), so Hermes doesn't ring meanwhile. */
    private suspend fun presence(holdS: Int) {
        runCatching { graph.client.rest("PUT", graph.client.withProfile("/api/plugins/conduit_push/calls/presence"), buildJsonObject { put("hold_s", holdS) }) }
    }

    /** An answered call from Hermes: Gemini opens by saying why it called, with what the chat last said. */
    private fun greetIncoming(c: IncomingCall) {
        graph.scope.launch {
            c.sessionIds.firstOrNull()?.let { sid -> jobs += VoiceJob(jobs.size + 1, c.title ?: tr(R.string.livecall_calling_about), sid, sid, status = "call reason") }
            val said = HermesCalls.context(graph, c)
            send(LiveProtocol.text(buildString {
                append("[Outgoing call] You are the one who called the user. Reason: ${callHeadline(c)}.")
                c.reason?.let { append(" Details: $it.") }
                if (said.isNotBlank()) append("\nThe last thing you said in that chat:\n$said")
                append("\nGreet them, tell them in a few sentences why you're calling and ask what they want to do.")
            }, complete = true))
        }
    }

    /** Waits for the goodbye still queued and in the AudioTrack buffer, then hangs up. */
    private fun hangUpWhenPlayed() {
        endAfterTurn = false
        graph.scope.launch { untilPlayed(); kotlinx.coroutines.delay(1500); if (active) hangUp() }
    }

    /** Until the queued speech has reached the AudioTrack (at most 30 s, in case playback is stuck). */
    private suspend fun untilPlayed() {
        kotlinx.coroutines.withTimeoutOrNull(30_000) { while (unplayed.get() > 0) kotlinx.coroutines.delay(100) }
    }

    /** Two minutes with nobody talking (and no job running) ends the call. */
    private fun watchIdle() {
        lastActivity = System.currentTimeMillis()
        idleWatch?.cancel()
        idleWatch = graph.scope.launch {
            var ticks = 0
            while (active) {
                kotlinx.coroutines.delay(10_000)
                if (++ticks % 6 == 0) presence(150)
                val busy = jobs.any { it.status == "working" || it.status == "awaiting approval" }
                if (!busy && System.currentTimeMillis() - lastActivity > 120_000) { error = tr(R.string.livecall_idle_hang_up); hangUp() }
            }
        }
    }

    private fun finishJob(sid: String, status: String, result: String) {
        val i = jobs.indexOfFirst { it.sessionId == sid }.takeIf { it >= 0 } ?: return
        if (jobs[i].status != "working" && jobs[i].status != "awaiting approval") return
        jobs[i] = jobs[i].copy(status = status, result = result)
        if (active) send(LiveProtocol.text("[Job ${jobs[i].n} ${if (status == "error") "failed" else "done"}] ${result.take(3000)}\nTell the user in a few sentences.", complete = true))
    }

    private fun onHermesEvent(e: HermesEvent) {
        val job = jobs.firstOrNull { it.sessionId == e.sessionId } ?: run {
            // An approval from one of this call's jobs comes as a server request, not an event.
            return
        }
        val p = e.payload
        when (e.type) {
            "message.delta" -> p.str("text")?.let { jobText[job.sessionId]?.append(it) }
            "message.complete" -> {
                val text = jobText[job.sessionId]?.toString()?.takeIf { it.isNotBlank() } ?: p.str("text") ?: p.str("content") ?: ""
                if (p.str("status") == "error") finishJob(job.sessionId, "error", p.str("error") ?: "Hermes returned an error")
                else if (!controlStep(job.sessionId, text)) finishJob(job.sessionId, "done", text.ifBlank { "Done, no text." })
            }
        }
    }

    /** The job's reply asked for steps on the phone: run them (risky ones confirmed on the bubble or by notification) and show Hermes where they led. */
    private fun controlStep(sid: String, text: String): Boolean {
        val reply = parseReply(text)
        val actions = reply.actions
        if (actions.isEmpty() || !PhoneAgent.available) { PhoneAgent.finished(reply.say); return false }
        jobText[sid] = StringBuilder()
        graph.scope.launch {
            val step = PhoneAgent.run(actions, controlSteps[sid] ?: 0)
                ?: return@launch finishJob(sid, "error", "Phone control was turned off.")
            controlSteps[sid] = step.steps
            runCatching {
                step.shot?.let { graph.client.call("image.attach_bytes", PhoneAgent.shotParams(sid, it), timeoutMs = 60_000) }
                graph.client.call("prompt.submit", buildJsonObject { put("session_id", sid); put("text", step.text) }, timeoutMs = 180_000)
            }
                .onFailure { finishJob(sid, "error", "Couldn't send it: ${it.message}") }
        }
        return true
    }

    /** Called when a server request (approval) arrives for one of this call's jobs. */
    fun approvalArrived(sessionId: String, description: String) {
        val i = jobs.indexOfFirst { it.sessionId == sessionId }.takeIf { it >= 0 } ?: return
        jobs[i] = jobs[i].copy(status = "awaiting approval")
        if (active) send(LiveProtocol.text("[Job ${jobs[i].n} needs approval] $description\nExplain it to the user and ask whether they approve.", complete = true))
    }
}

/** A call's microphone and speaker on another device: 16 kHz PCM in, 24 kHz PCM out (Gemini Live's formats). */
interface RemoteAudio {
    /** Starts delivering microphone chunks. */
    fun open(onMic: (ByteArray) -> Unit)
    fun play(pcm: ByteArray)
    /** Gemini was interrupted: drop what's queued to play. */
    fun flush()
    fun close()
}
