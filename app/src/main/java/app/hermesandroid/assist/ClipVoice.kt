package app.hermesandroid.assist

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.hermesandroid.AppGraph
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.voice.spokenText
import app.hermesandroid.watch.speakableClips
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Speaks an answer as it's being written, in Hermes' voice: whole sentences go to `/api/audio/speak` one by one and
 * play in order, so the first words come after the first sentence rather than the whole reply (like the watch).
 */
class ClipVoice(private val graph: AppGraph, private val context: Context) {
    private var clips: Channel<String>? = null
    private var worker: Job? = null
    private var spokenUpTo = 0
    private val queue = ArrayDeque<File>()
    private var player: MediaPlayer? = null
    private var n = 0

    /** Follows the answer: `final` flushes the rest. */
    fun feed(text: String, final: Boolean) {
        val ch = clips ?: Channel<String>(Channel.UNLIMITED).also { clips = it; start(it) }
        val (ready, upTo) = speakableClips(text, spokenUpTo, final)
        spokenUpTo = upTo
        ready.forEach { ch.trySend(it) }
        if (final) { ch.close(); clips = null; spokenUpTo = 0 }
    }

    private fun start(ch: Channel<String>) {
        worker = graph.scope.launch {
            for (clip in ch) runCatching {
                val r = graph.client.rest("POST", "/api/audio/speak", buildJsonObject { put("text", spokenText(clip)) }, timeoutSec = 30).obj()
                val bytes = (r?.str("data_url") ?: r?.str("dataUrl"))?.substringAfter("base64,")?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) } ?: return@runCatching
                queue.addLast(File(context.cacheDir, "assist${n++ % 30}.mp3").apply { writeBytes(bytes) })
                if (player == null) next()
            }
        }
    }

    private fun next() {
        val f = queue.removeFirstOrNull() ?: return
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(f.path)
            setOnCompletionListener { it.release(); player = null; next() }
            setOnErrorListener { mp, _, _ -> mp.release(); player = null; next(); true }
            prepare(); start()
        }
    }

    val speaking get() = player != null || queue.isNotEmpty()

    fun stop() {
        clips?.close(); clips = null; spokenUpTo = 0
        worker?.cancel(); worker = null
        queue.clear()
        player?.runCatching { stop(); release() }; player = null
    }
}
