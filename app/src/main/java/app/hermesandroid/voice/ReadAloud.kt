package app.hermesandroid.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Read aloud (MessageReadAloudController): Hermes' configured TTS through `/api/audio/speak`, or the phone's
// own TextToSpeech (also the fallback when Hermes can't speak). Settings > Voice picks the voice and speed.

/** Text as it should sound: no markdown, code, links or *stage directions*. */
fun spokenText(markdown: String): String {
    var t = markdown
    t = t.replace(Regex("```[\\s\\S]*?```"), " ")
    t = t.replace(Regex("(?m)^\\s*MEDIA:\\S+\\s*$"), " ")
    t = t.replace(Regex("::preview\\{[^}]*\\}"), " ")
    t = t.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")
    t = t.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    t = t.replace(Regex("https?://\\S+"), " ")
    t = t.replace(Regex("`([^`]*)`"), "$1")
    // An action of three or more words between single asterisks or underscores is a stage direction.
    t = t.replace(Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?!\\*)")) { m -> if (m.groupValues[1].trim().split(Regex("\\s+")).size >= 3) " " else m.groupValues[1] }
    t = t.replace(Regex("(?<![_\\w])_([^_\\n]+)_(?![_\\w])")) { m -> if (m.groupValues[1].trim().split(Regex("\\s+")).size >= 3) " " else m.groupValues[1] }
    t = t.replace(Regex("(\\*\\*|__|~~)"), "")
    t = t.replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "")
    t = t.replace(Regex("(?m)^\\s*\\|?[-:| ]{3,}\\|?\\s*$"), " ")
    t = t.replace("|", ", ")
    t = t.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    return t.ifBlank { markdown.trim() }
}

class ReadAloud(private val graph: AppGraph, private val context: Context) {
    /** The message being read (loading or playing), or null. */
    var current by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var job: Job? = null

    fun toggle(id: String, text: String) = if (current == id) stop() else play(id, text)

    fun stop() {
        job?.cancel(); job = null
        player?.release(); player = null // release() is valid in any state, stop() not while preparing
        tts?.stop()
        current = null; loading = false
    }

    fun play(id: String, text: String) {
        stop()
        current = id
        val spoken = spokenText(text)
        if (graph.store.readAloud == "phone") { speakOnPhone(id, spoken); return }
        loading = true
        job = graph.scope.launch {
            val audio = runCatching {
                val r = graph.client.rest("POST", graph.client.withProfile("/api/audio/speak"), buildJsonObject { put("text", spoken) }, timeoutSec = 90).obj()
                r?.str("error")?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
                (r?.str("data_url") ?: r?.str("dataUrl"))?.substringAfter("base64,")?.let { Base64.decode(it, Base64.DEFAULT) } ?: throw IllegalStateException(tr(R.string.readaloud_no_audio))
            }
            if (current != id) return@launch
            loading = false
            audio.onSuccess { playBytes(id, it) }.onFailure {
                android.util.Log.w("ReadAloud", "Hermes speech failed: ${it.message}")
                // Hermes couldn't speak: the phone reads it instead.
                error = tr(R.string.readaloud_hermes_failed, it.message.orEmpty())
                speakOnPhone(id, spoken)
            }
        }
    }

    private fun playBytes(id: String, bytes: ByteArray) {
        val file = File(context.cacheDir, "read-aloud.audio").apply { writeBytes(bytes) }
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.path)
            // this@ReadAloud: inside apply, a bare stop() is MediaPlayer.stop() and the button would never reset.
            setOnCompletionListener { if (current == id) this@ReadAloud.stop() }
            setOnErrorListener { _, _, _ -> if (current == id) { error = tr(R.string.readaloud_play_failed); this@ReadAloud.stop() }; true }
            // Async: prepare() parses the whole clip on the main thread. Stopped before it's ready = released, no callback.
            setOnPreparedListener { mp ->
                if (current != id) return@setOnPreparedListener
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(graph.store.readSpeed) }
                mp.start()
            }
            prepareAsync()
        }
    }

    private fun speakOnPhone(id: String, text: String) {
        fun speak(engine: TextToSpeech) {
            engine.setSpeechRate(graph.store.readSpeed)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { graph.scope.launch { if (current == id) stop() } }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { graph.scope.launch { if (current == id) { error = tr(R.string.readaloud_phone_failed); stop() } } }
            })
            // TextToSpeech caps one utterance; long replies go in paragraph chunks.
            val chunks = text.split("\n").flatMap { it.chunked(TextToSpeech.getMaxSpeechInputLength() - 1) }.filter { it.isNotBlank() }
            chunks.forEachIndexed { i, chunk -> engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == chunks.lastIndex) id else "$id#$i") }
        }
        loading = false
        tts?.let { speak(it); return }
        loading = true
        tts = TextToSpeech(context) { status ->
            loading = false
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) { error = tr(R.string.readaloud_no_voice); stop(); return@TextToSpeech }
            engine.language = Locale.getDefault()
            if (current == id) speak(engine)
        }
    }
}
