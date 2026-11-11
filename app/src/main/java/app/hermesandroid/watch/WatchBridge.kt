package app.hermesandroid.watch

import android.content.Context
import android.provider.CalendarContract
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.graph
import app.hermesandroid.net.HermesEvent
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.tr
import app.hermesandroid.ui.shell.defaultProfileTitle
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject

// The phone side of the watch app (wear/…/Bridge.kt has the same paths): a question from the watch goes to
// Allfather in one "From the watch" chat on the default profile, and the answer streams back as it's written.

const val ASK = "/allfather/ask"
const val REPLY = "/allfather/reply"
const val STATUS = "/allfather/status"
const val AUDIO = "/allfather/audio"

/**
 * Whole sentences in `text` from `from` on, for speaking while the answer is still being written: a clip ends at
 * . ! ? … (followed by a space) or a line break; clips under 40 characters wait for the next one (a lone "Sí, señor."
 * is a wasted round trip) — except the first, which only needs 15, so the voice starts sooner. `final` flushes the rest. Returns the clips and where the unspoken text now starts.
 */
fun speakableClips(text: String, from: Int, final: Boolean): Pair<List<String>, Int> {
    val clips = mutableListOf<String>()
    var start = from
    for (i in from until text.length) {
        val c = text[i]
        val end = c == '\n' || (c in ".!?…" && i + 1 < text.length && text[i + 1].isWhitespace())
        if (end && text.substring(start, i + 1).trim().length >= (if (from == 0 && clips.isEmpty()) 15 else 40)) { clips += text.substring(start, i + 1).trim(); start = i + 1 }
    }
    if (final && text.substring(start).isNotBlank()) { clips += text.substring(start).trim(); start = text.length }
    return clips to start
}

class WatchBridge(private val graph: AppGraph, private val context: Context) {
    // Sonnet 5.5 thinking high, like the assistant (the user's pick).
    private val chat = app.hermesandroid.chat.QuickChat(graph, tr(R.string.watchbridge_chat_title), reasoning = "high")
    /** The watch wants the answer read aloud: the phone sends it in Hermes' own voice. */
    @Volatile private var speak = false
    /** Whether a question from the watch is being answered (the tile/complication show "working"). */
    @Volatile private var answering = false
    private var text = ""
    private var lastSent = 0L
    /** This answer's clips waiting to be voiced, in order, and how much of `text` they cover. */
    private var clips: kotlinx.coroutines.channels.Channel<String>? = null
    private var spokenUpTo = 0

    /** The watch's payload: JSON {q, speak} (plain text from an older watch app). */
    fun ask(node: String, payload: String) {
        val o = runCatching { org.json.JSONObject(payload) }.getOrNull()
        speak = o?.optBoolean("speak") ?: false
        val question = o?.optString("q") ?: payload
        graph.scope.launch {
            text = ""; lastSent = 0
            clips?.close(); spokenUpTo = 0
            clips = if (speak) kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { voice(node, it) } else null
            answering = true
            // The watch reads the answer aloud on a small screen: ask for brevity, visibly.
            val control = app.hermesandroid.control.PhoneAgent.screenBlock()?.let { "\n" + app.hermesandroid.control.CONTROL_RULES + "\n" + it } ?: ""
            turn(node, "[From the watch: reply in 1 to 3 sentences, in the user\'s language, no tables or long lists]$control\n$question", 0)
        }
    }

    /** One turn of the watch's chat; with phone control on, steps in the reply run (confirmed by notification) and loop. */
    private suspend fun turn(node: String, message: String, steps: Int, image: ByteArray? = null) {
            chat.ask(message, image, onText = { soFar, done ->
                text = soFar
                if (!done) {
                    val now = System.currentTimeMillis()
                    if (now - lastSent > 500) { lastSent = now; send(node, soFar, done = false) }
                    speakFrom(final = false)
                } else {
                    val reply = app.hermesandroid.control.parseReply(soFar)
                    if (reply.actions.isNotEmpty() && app.hermesandroid.control.PhoneAgent.available) {
                        send(node, reply.say.ifBlank { tr(R.string.watchbridge_doing_on_phone) }, done = false)
                        graph.scope.launch {
                            val step = app.hermesandroid.control.PhoneAgent.run(reply.actions, steps, progress = { send(node, it, done = false) }) ?: return@launch
                            text = ""; spokenUpTo = 0
                            turn(node, step.text, step.steps, step.shot)
                        }
                        return@ask
                    }
                    answering = false
                    app.hermesandroid.control.PhoneAgent.finished(reply.say.ifBlank { soFar })
                    send(node, reply.say.ifBlank { soFar }, done = true, audio = clips != null && soFar.isNotEmpty())
                    speakFrom(final = true)
                    clips?.close(); clips = null
                }
            }, onError = { err ->
                answering = false; clips?.close(); clips = null
                send(node, text, done = true, error = err)
            })
    }

    private fun speakFrom(final: Boolean) {
        val ch = clips ?: return
        val (ready, upTo) = speakableClips(text, spokenUpTo, final)
        spokenUpTo = upTo
        ready.forEach { ch.trySend(it) }
    }

    /** Voices the clips in order as they come, in Hermes' voice (`/api/audio/speak`, Edge Jorge today), one message
     *  each (a sentence is a few KB): the watch starts talking after the first sentence, not the whole answer. */
    private fun voice(node: String, ch: kotlinx.coroutines.channels.ReceiveChannel<String>) = graph.scope.launch {
        val started = System.currentTimeMillis()
        for (clip in ch) runCatching {
            val r = graph.client.rest("POST", "/api/audio/speak", buildJsonObject { put("text", app.hermesandroid.voice.spokenText(clip)) }, timeoutSec = 30).obj()
            val bytes = (r?.str("data_url") ?: r?.str("dataUrl"))?.substringAfter("base64,")?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            if (bytes != null && bytes.size <= 90_000) {
                Wearable.getMessageClient(context).sendMessage(node, AUDIO, bytes).await()
                android.util.Log.i("WatchBridge", "clip sent: ${bytes.size} bytes at +${System.currentTimeMillis() - started} ms")
            }
        }.onFailure { android.util.Log.w("WatchBridge", "audio: ${it.message}") }
    }

    private fun send(node: String, t: String, done: Boolean, error: String? = null, audio: Boolean = false) {
        val body = JSONObject().put("text", t).put("done", done).put("audio", audio).apply { error?.let { put("error", it) } }.toString().toByteArray()
        Wearable.getMessageClient(context).sendMessage(node, REPLY, body)
    }

    /** The tile/complication status: the AI's name, the next calendar event (when shared), and whether it's working. */
    fun pushStatus(working: Boolean = answering) = graph.scope.launch {
        runCatching {
            val (title, at) = nextEvent() ?: (null to 0L)
            val req = PutDataMapRequest.create(STATUS).apply {
                dataMap.putString("name", defaultProfileTitle)
                dataMap.putString("next_title", title.orEmpty())
                dataMap.putLong("next_at", at)
                dataMap.putBoolean("working", working)
                dataMap.putLong("stamp", System.currentTimeMillis() / 60_000) // once a minute at most
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(req).await()
        }
    }

    private fun nextEvent(): Pair<String, Long>? {
        if (!graph.phone.prefs.enabled || !graph.phone.canReadCalendar()) return null
        val now = System.currentTimeMillis()
        val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
        return CalendarContract.Instances.query(context.contentResolver, cols, now, now + 2 * 86_400_000L)?.use { c ->
            var best: Pair<String, Long>? = null
            while (c.moveToNext()) {
                if (c.getInt(2) == 1) continue
                val begin = c.getLong(1)
                if (begin >= now && (best == null || begin < best.second)) best = (c.getString(0) ?: tr(R.string.watchbridge_event)) to begin
            }
            best
        }
    }
}

/** Questions from the watch, even while the phone app is closed. */
class WatchService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == ASK) applicationContext.graph.watch.ask(event.sourceNodeId, String(event.data))
    }

    /** A Gemini Live call from the watch. */
    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        if (channel.path == LIVE) applicationContext.graph.watchLive.open(channel)
    }
}
