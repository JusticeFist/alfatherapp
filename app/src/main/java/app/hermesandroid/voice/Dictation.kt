package app.hermesandroid.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Composer dictation (ComposerDictationService): the phone's own recognizer (on-device when it has one, live
// partial text) or Hermes' configured STT (record, then `/api/audio/transcribe`). Settings > Voice picks one.

/** 16 kHz mono 16-bit PCM wrapped as a WAV file. */
fun wav(pcm: ByteArray, sampleRate: Int = 16_000): ByteArray {
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
    h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
    h.put("data".toByteArray()).putInt(pcm.size)
    return h.array() + pcm
}

/** Joins what was typed with what was said. */
fun joinDictation(base: String, said: String): String = when {
    said.isBlank() -> base
    base.isBlank() -> said.trim()
    base.endsWith(" ") || base.endsWith("\n") -> base + said.trim()
    else -> base + " " + said.trim()
}

class Dictation(private val graph: AppGraph, private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    /** "Transcribiendo…" while Hermes works on a recording. */
    var transcribing by mutableStateOf(false)
        private set
    /** 0…1 microphone level for the button's ring. */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)

    private var recognizer: SpeechRecognizer? = null
    private var recording: Job? = null

    fun toggle(base: String, onText: (String) -> Unit) = if (listening) stop() else start(base, onText)

    fun start(base: String, onText: (String) -> Unit) {
        error = null
        if (graph.store.dictation == "hermes") startHermes(base, onText) else startPhone(base, onText)
    }

    fun stop() {
        recognizer?.stopListening()
        recording?.let { it.cancel(); recording = null }
        if (recognizer == null) listening = false
    }

    /** Hands-free: the phone's recognizer, which stops by itself when you stop talking; `onFinal` gets the result
     *  (empty when nothing was heard). For the assistant, whatever the composer's engine is. */
    fun listenOnce(onText: (String) -> Unit, onFinal: (String) -> Unit) { error = null; startPhone("", onText, onFinal, silenceMs = 900L) }

    /** `silenceMs`: how long a pause ends the dictation (the assistant answers sooner; the composer lets you think). */
    private fun startPhone(base: String, onText: (String) -> Unit, onFinal: ((String) -> Unit)? = null, silenceMs: Long = 2500L,
                           onDevice: Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { error = tr(R.string.dictation_no_recognizer); return }
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Settings > Voice; without it a phone in English heard Spanish as English words.
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, graph.store.speechTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
        // The recognizer can end with an error after having heard words (partials): the last partial is the answer.
        var lastPartial = ""
        var finished = false
        fun final(said: String) { if (!finished) { finished = true; onFinal?.invoke(said) } }
        fun done() { listening = false; level = 0f; r.destroy(); if (recognizer === r) recognizer = null }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(code: Int) {
                // No pack for the language on the device yet: ask for it for next time and use Google's online recognizer now.
                if (onDevice && (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { r.triggerModelDownload(request) }
                    done(); startPhone(base, onText, onFinal, silenceMs, onDevice = false); return
                }
                error = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> tr(R.string.dictation_not_heard)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> tr(R.string.dictation_no_mic_permission)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> tr(R.string.dictation_needs_network)
                    SpeechRecognizer.ERROR_CLIENT -> null
                    else -> tr(R.string.dictation_failed, code)
                }
                done()
                if (lastPartial.isNotBlank()) { error = null; final(lastPartial) } else final("")
            }
            override fun onResults(results: Bundle?) {
                val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                said?.let { onText(joinDictation(base, it)) }
                done()
                final(said?.takeIf { it.isNotBlank() } ?: lastPartial)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial = it; onText(joinDictation(base, it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listening = true
        r.startListening(request)
    }

    /** Records until the button is tapped again (at most 2 min), then asks Hermes for the transcript. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun startHermes(base: String, onText: (String) -> Unit) {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { error = tr(R.string.dictation_mic_failed); return }
        val pcm = ByteArrayOutputStream()
        listening = true
        val job = graph.scope.launch(Dispatchers.IO) {
            val buf = ShortArray(min)
            rec.startRecording()
            try {
                while (isActive && pcm.size() < rate * 2 * 120) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) { sum += buf[i] * buf[i].toDouble(); bytes.putShort(buf[i]) }
                    pcm.write(bytes.array())
                    level = (sqrt(sum / n) / 6000).toFloat().coerceIn(0f, 1f)
                }
            } finally {
                rec.stop(); rec.release()
            }
        }
        recording = job
        job.invokeOnCompletion {
            graph.scope.launch {
                listening = false; level = 0f
                if (pcm.size() < rate / 2) { error = tr(R.string.dictation_too_short); return@launch }
                transcribing = true
                val said = runCatching {
                    val url = "data:audio/wav;base64," + Base64.encodeToString(wav(pcm.toByteArray(), rate), Base64.NO_WRAP)
                    val r = graph.client.rest("POST", graph.client.withProfile("/api/audio/transcribe"), buildJsonObject { put("data_url", url); put("mime_type", "audio/wav") }, timeoutSec = 120).obj()
                    r?.str("error")?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
                    r?.str("transcript") ?: r?.str("text") ?: throw IllegalStateException(tr(R.string.dictation_no_transcript))
                }
                transcribing = false
                said.onSuccess { onText(joinDictation(base, it)) }.onFailure { error = tr(R.string.dictation_transcribe_failed, it.message.orEmpty()) }
            }
        }
    }
}
