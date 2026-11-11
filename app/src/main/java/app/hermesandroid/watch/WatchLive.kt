package app.hermesandroid.watch

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.voice.LiveCall
import app.hermesandroid.voice.RemoteAudio
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Gemini Live from the watch: the watch opens a Data Layer channel (`/allfather/live`) and is the call's microphone
// and speaker; this phone runs the call itself (LiveCall: token, persona, Allfather's tools, saved transcript).
//   watch → phone  channel bytes: 16 kHz mono PCM16 from the watch's mic; closing it hangs up
//   phone → watch  channel bytes: 24 kHz mono PCM16 to play
//   phone → watch  /allfather/live/state  "connecting" | "listening" | "speaking" | "ended[: why]"
//   phone → watch  /allfather/live/flush  Gemini was interrupted: drop queued audio

const val LIVE = "/allfather/live"
const val LIVE_STATE = "/allfather/live/state"
const val LIVE_FLUSH = "/allfather/live/flush"

class WatchLive(private val graph: AppGraph, private val context: Context) {
    private var watching: Job? = null

    fun open(channel: ChannelClient.Channel) = graph.scope.launch {
        val client = Wearable.getChannelClient(context)
        val node = channel.nodeId
        val call = graph.liveCall
        // The watch often wakes this app from scratch: connect and ask the host about Gemini Live before judging.
        if (graph.signedIn && !graph.live.available) {
            graph.client.start()
            kotlinx.coroutines.withTimeoutOrNull(20_000) { graph.client.connected.first { it } }
            runCatching { graph.live.refresh() }
        }
        if (call.active || !graph.signedIn || !graph.live.available) {
            state(node, "ended: " + tr(if (call.active) R.string.watchlive_call_on_phone else R.string.watchlive_unavailable))
            client.close(channel); return@launch
        }
        android.util.Log.i("WatchLive", "channel from watch; signedIn=${graph.signedIn} available=${graph.live.available} active=${call.active}")
        val input = runCatching { client.getInputStream(channel).await() }.getOrNull()
        val output = runCatching { client.getOutputStream(channel).await() }.getOrNull()
        if (input == null || output == null) { client.close(channel); return@launch }
        val audio = ChannelAudio(input, output, node) { graph.scope.launch { client.close(channel) } }
        follow(node, call)
        call.start(remote = audio)
    }

    /** Mirrors the call's state on the watch until it ends. */
    private fun follow(node: String, call: LiveCall) {
        watching?.cancel()
        watching = graph.scope.launch {
            snapshotFlow { call.state to call.error }.collect { (s, err) ->
                val word = when (s) {
                    LiveCall.State.Connecting, LiveCall.State.Reconnecting -> "connecting"
                    LiveCall.State.Listening -> "listening"
                    LiveCall.State.Speaking -> "speaking"
                    LiveCall.State.Ended, LiveCall.State.Idle -> "ended" + (err?.let { ": $it" } ?: "")
                }
                android.util.Log.i("WatchLive", "state $word")
                state(node, word)
            }
        }
    }

    private fun state(node: String, word: String) =
        Wearable.getMessageClient(context).sendMessage(node, LIVE_STATE, word.toByteArray())

    /** The channel as the call's audio: mic chunks read in, Gemini's speech written out by one writer, in order. */
    private inner class ChannelAudio(private val input: InputStream, private val output: OutputStream, private val node: String, private val closeChannel: () -> Unit) : RemoteAudio {
        private val queue = Channel<ByteArray>(Channel.UNLIMITED)
        private var reader: Job? = null
        private var writer: Job? = null

        override fun open(onMic: (ByteArray) -> Unit) {
            reader = graph.scope.launch(Dispatchers.IO) {
                val buf = ByteArray(1280) // 40 ms at 16 kHz
                try {
                    while (isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) onMic(buf.copyOf(n))
                    }
                } catch (_: Exception) {}
                // The watch hung up (closed its side) or the link dropped.
                graph.scope.launch { if (graph.liveCall.active) graph.liveCall.hangUp() }
            }
            writer = graph.scope.launch(Dispatchers.IO) {
                try { for (pcm in queue) { output.write(pcm); output.flush() } } catch (_: Exception) {}
            }
        }

        override fun play(pcm: ByteArray) { queue.trySend(pcm) }

        override fun flush() {
            while (queue.tryReceive().isSuccess) Unit
            Wearable.getMessageClient(context).sendMessage(node, LIVE_FLUSH, ByteArray(0))
        }

        override fun close() {
            reader?.cancel(); writer?.cancel(); queue.close()
            runCatching { input.close() }; runCatching { output.close() }
            closeChannel()
        }
    }
}
