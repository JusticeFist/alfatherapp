package app.hermesandroid.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Gemini Live on the wrist: this watch is the call's microphone and speaker over a Data Layer channel to the phone,
// which runs the call (the phone side is app/…/watch/WatchLive.kt; keep the paths equal). Hanging up closes the channel.

const val LIVE = "/allfather/live"
const val LIVE_STATE = "/allfather/live/state"
const val LIVE_FLUSH = "/allfather/live/flush"

object Live {
    /** null = no call; else "connecting" | "listening" | "speaking". */
    var state by mutableStateOf<String?>(null)
    var muted by mutableStateOf(false)
    var ended by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: ChannelClient.Channel? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (state != null) return
        state = "connecting"; ended = null; muted = false
        scope.launch {
            try {
                val node = Wearable.getNodeClient(context).connectedNodes.await().firstOrNull() ?: throw IllegalStateException(context.getString(R.string.live_phone_not_connected))
                val client = Wearable.getChannelClient(context)
                val ch = client.openChannel(node.id, LIVE).await().also { channel = it }
                val out = client.getOutputStream(ch).await()
                val input = client.getInputStream(ch).await()
                val am = context.getSystemService(AudioManager::class.java)
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
                val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 2560) * 2).also { record = it }
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
                rec.startRecording()
                // Mic → phone.
                scope.launch {
                    val buf = ByteArray(1280) // 40 ms
                    try {
                        while (isActive && state != null) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n > 0 && !muted) { out.write(buf, 0, n); out.flush() }
                        }
                    } catch (_: Exception) {}
                }
                // Phone → speaker; the phone closing its side ends the call.
                val buf = ByteArray(4800)
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) track?.write(buf, 0, n)
                }
                finish(context, null)
            } catch (e: Exception) {
                finish(context, e.message ?: context.getString(R.string.live_call_failed))
            }
        }
    }

    fun hangUp(context: Context) = scope.launch { finish(context, null) }

    /** The phone's view of the call. */
    fun onState(context: Context, word: String) {
        when {
            word.startsWith("ended") -> { ended = word.substringAfter(": ", "").ifBlank { null }; scope.launch { finish(context, ended) } }
            state != null -> state = word
        }
    }

    fun flush() { track?.run { pause(); flush(); play() } }

    @Synchronized
    private fun finish(context: Context, why: String?) {
        if (state == null && channel == null) return
        state = null
        why?.let { ended = it }
        record?.runCatching { stop(); release() }; record = null
        track?.runCatching { pause(); flush(); release() }; track = null
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        channel?.let { ch -> runCatching { Wearable.getChannelClient(context).close(ch) } }; channel = null
    }
}
