package app.hermesandroid.wear

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

// Watch ↔ phone over the Wearable Data Layer (the phone side is app/…/watch/WatchBridge.kt; keep the paths equal):
//   watch → phone  /allfather/ask     JSON {q, speak}
//   phone → watch  /allfather/reply   JSON {text, done, audio, error?}, sent as the answer streams
//   phone → watch  /allfather/audio   one sentence of the answer in Hermes' voice (MP3 bytes), in order, as it's written
//   phone → watch  data /allfather/status  {name, next_title, next_at, working}

const val ASK = "/allfather/ask"
const val REPLY = "/allfather/reply"
const val STATUS = "/allfather/status"
const val AUDIO = "/allfather/audio"

/** What the watch shows: the last exchange and the phone's status. */
object WatchState {
    var name by mutableStateOf("AllFather")
    var question by mutableStateOf<String?>(null)
    var reply by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var nextTitle by mutableStateOf<String?>(null)
    var nextAt by mutableStateOf(0L)
    var working by mutableStateOf(false)
    /** Bumped when a reply is complete (the screen reads it aloud when that's on). */
    var finished by mutableStateOf(0)
    /** The last `finished` already read aloud: waking the screen rebuilds the app, and it must not read it again. */
    var spoken = 0
}

object Phone {
    /** Sends the question to every connected phone node (normally one). False when none is reachable. */
    suspend fun ask(context: Context, text: String, speak: Boolean): Boolean {
        val payload = JSONObject().put("q", text).put("speak", speak).toString()
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrDefault(emptyList())
        if (nodes.isEmpty()) return false
        val client = Wearable.getMessageClient(context)
        return nodes.map { runCatching { client.sendMessage(it.id, ASK, payload.toByteArray()).await() }.isSuccess }.any { it }
    }

    /** The status data item, read once (the tile and complication call this; the app gets live updates). */
    suspend fun loadStatus(context: Context) {
        runCatching {
            Wearable.getDataClient(context).dataItems.await().use { items ->
                items.firstOrNull { it.uri.path == STATUS }?.let { apply(DataMapItem.fromDataItem(it).dataMap) }
            }
        }
    }

    fun apply(map: com.google.android.gms.wearable.DataMap) {
        map.getString("name")?.takeIf { it.isNotBlank() }?.let { WatchState.name = it }
        WatchState.nextTitle = map.getString("next_title")?.takeIf { it.isNotBlank() }
        WatchState.nextAt = map.getLong("next_at")
        WatchState.working = map.getBoolean("working")
    }
}

/** Replies and status from the phone, even while the app is closed. */
class PhoneListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == LIVE_STATE) { Live.onState(this, String(event.data)); return }
        if (event.path == LIVE_FLUSH) { Live.flush(); return }
        if (event.path == AUDIO) {
            android.util.Log.i("Speaker", "clip from phone: ${event.data.size} bytes")
            Speaker.enqueue(this, event.data)
            return
        }
        if (event.path != REPLY) return
        val o = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return
        WatchState.reply = o.optString("text")
        o.optString("error").takeIf { it.isNotBlank() }?.let { WatchState.error = it }
        if (o.optBoolean("done")) {
            WatchState.busy = false
            // Hermes' voice is on its way from the phone (or already playing); the watch reads it itself if none comes.
            if (o.optBoolean("audio") && !Speaker.heard) Speaker.expect(this, WatchState.reply)
            WatchState.finished++
            // Closed app: a finished answer still reaches the wrist.
            if (!MainActivity.visible) Notify.answer(this, WatchState.reply)
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.dataItem.uri.path == STATUS }.forEach { Phone.apply(DataMapItem.fromDataItem(it.dataItem).dataMap) }
        Complication.refresh(this)
        Tile.refresh(this)
    }
}

/** Reads answers aloud: Hermes' own voice from the phone, else the watch's TTS in the watch's language. */
object Speaker {
    private var tts: android.speech.tts.TextToSpeech? = null
    private var player: android.media.MediaPlayer? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var fallback: Runnable? = null
    /** An answer whose audio the phone is sending. */
    @Volatile var expecting = false
        private set

    private fun engine(context: Context): android.speech.tts.TextToSpeech =
        tts ?: android.speech.tts.TextToSpeech(context.applicationContext) { ok ->
            if (ok == android.speech.tts.TextToSpeech.SUCCESS) tts?.language = java.util.Locale.getDefault()
        }.also { tts = it }

    fun local(context: Context, text: String) {
        stop()
        val engine = engine(context)
        engine.language = java.util.Locale.getDefault()
        engine.speak(text.replace(Regex("[*_#`>]"), ""), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "answer")
    }

    /** Waits up to 8 s for the phone's audio, then reads it on the watch. */
    fun expect(context: Context, text: String) {
        expecting = true
        fallback?.let(main::removeCallbacks)
        fallback = Runnable { expecting = false; android.util.Log.i("Speaker", "no audio from the phone: reading on the watch"); local(context, text) }.also { main.postDelayed(it, 8_000) }
    }

    private val queue = ArrayDeque<java.io.File>()
    private var clip = 0
    /** Some audio of the current answer already came from the phone. */
    @Volatile var heard = false
        private set

    /** A new question: forget the last answer's audio. */
    fun reset() = main.post { cancel(); queue.clear(); heard = false }

    /** One sentence in Hermes' voice: plays now, or after the ones before it. */
    fun enqueue(context: Context, bytes: ByteArray) = main.post {
        fallback?.let(main::removeCallbacks); fallback = null; expecting = false; heard = true
        tts?.stop()
        queue.addLast(java.io.File(context.cacheDir, "clip${clip++ % 50}.mp3").apply { writeBytes(bytes) })
        if (player == null) next()
    }

    private fun next() {
        val file = queue.removeFirstOrNull() ?: return
        player = android.media.MediaPlayer().apply {
            setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.path)
            setOnCompletionListener { it.release(); player = null; next() }
            setOnErrorListener { mp, _, _ -> mp.release(); player = null; next(); true }
            // Async: prepare() would parse the clip on the main thread (this runs from main.post).
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    fun stop() {
        tts?.stop()
        queue.clear()
        player?.release(); player = null // release() is valid in any state, stop() not while preparing
    }

    /** The user turned reading off: nothing plays, now or when the phone's audio lands. */
    fun cancel() { fallback?.let(main::removeCallbacks); fallback = null; expecting = false; stop() }
}
