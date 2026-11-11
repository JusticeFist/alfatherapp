package app.hermesandroid.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import java.text.Normalizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Wake phrase : while the app is on screen and no voice is running, the
// phone's on-device recognizer listens in short cycles for the phrase ("Hey Allfather"); hearing it starts a Gemini
// Live call (one long session fed with our own audio, so the recognizer doesn't beep). Audio never leaves the phone and nothing is kept. Not in the background: that would need an always-on
// microphone service.

/** Finds the wake phrase in a running transcript (WakePhraseMatcher, plus slack for how recognizers spell names). */
object WakePhrase {
    /** Lowercased, accent-folded runs of letters and digits. */
    fun tokens(text: String): List<String> = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Two words at least: a single common word would fire all the time. */
    fun usable(phrase: String) = tokens(phrase).size >= 2

    /** The phrase's words in order, or the same letters with the spaces moved ("all father") or one slip per 6 letters ("alfather"). */
    fun matches(transcript: String, phrase: String): Boolean {
        val p = tokens(phrase)
        val t = tokens(transcript)
        if (p.size < 2 || t.isEmpty()) return false
        if (t.windowed(p.size).any { it == p }) return true
        val need = p.joinToString("")
        val have = t.joinToString("")
        if (need in have) return true
        val slack = need.length / 6
        if (slack == 0) return false
        return (maxOf(0, need.length - slack)..need.length + slack).any { len ->
            len <= have.length && (0..have.length - len).any { start -> edits(have.substring(start, start + len), need) <= slack }
        }
    }

    private fun edits(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}

class Wake(private val graph: AppGraph, private val context: Context) {
    private val prefs = context.getSharedPreferences("wake", Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false))
        private set
    /** Blank = "Hey <agent name>" in the app's language. */
    var phrase by mutableStateOf(prefs.getString("phrase", "").orEmpty())
        private set
    var listening by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    fun effectivePhrase() = phrase.ifBlank { tr(R.string.wake_default_phrase, app.hermesandroid.ui.shell.defaultProfileTitle) }

    fun choose(on: Boolean, text: String = phrase) {
        enabled = on; phrase = text.trim()
        prefs.edit().putBoolean("enabled", on).putString("phrase", phrase).apply()
        if (on && visible) arm() else disarm()
    }

    private var visible = false
    private var recognizer: SpeechRecognizer? = null
    private var cycle: Job? = null
    private var failures = 0

    /** The app came to the screen. */
    fun shown() { visible = true; arm() }

    /** The app left the screen. */
    fun hidden() { visible = false; disarm() }

    /** Voice is free: no call, no dictation, no reading aloud. */
    private fun idle() = !graph.liveCall.active && !graph.dictation.listening

    private fun arm() {
        if (!enabled || !visible || recognizer != null || cycle?.isActive == true) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { problem = tr(R.string.wake_no_mic_permission); return }
        if (android.os.Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) { problem = tr(R.string.wake_no_recognizer); return }
        if (!WakePhrase.usable(effectivePhrase())) { problem = tr(R.string.wake_two_words); return }
        problem = null; failures = 0
        listen()
    }

    fun disarm() {
        cycle?.cancel(); cycle = null
        stopFeed?.invoke(); stopFeed = null
        recognizer?.runCatching { cancel(); destroy() }; recognizer = null
        listening = false
    }

    /**
     * One long session fed from our own AudioRecord (EXTRA_AUDIO_SOURCE, segmented, API 33+): Google's recognizer
     * plays a sound each time it opens or closes the microphone itself, which in short cycles beeped every few seconds.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun listen() {
        if (!enabled || !visible) return
        if (!idle()) { later(2_000); return } // a call or dictation has the microphone: try again once it's done
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        val (source, sink) = android.os.ParcelFileDescriptor.createPipe()
        val rate = 16_000
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200) * 2)
        val feed = graph.scope.launch(Dispatchers.IO) {
            val buf = ByteArray(1280) // 40 ms
            runCatching {
                rec.startRecording()
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                    while (isActive) { val n = rec.read(buf, 0, buf.size); if (n > 0) out.write(buf, 0, n) }
                }
            }
            runCatching { rec.stop() }; rec.release()
        }
        stopFeed = { feed.cancel(); runCatching { source.close() } }
        fun next(wait: Long) {
            stopFeed?.invoke(); stopFeed = null
            r.runCatching { destroy() }; if (recognizer === r) recognizer = null
            listening = false; later(wait)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partial: Bundle?) { check(partial, r) }
            override fun onSegmentResults(segmentResults: Bundle) { if (!check(segmentResults, r)) failures = 0 }
            override fun onEndOfSegmentedSession() { next(500) }
            override fun onResults(results: Bundle?) { if (!check(results, r)) next(500) }
            override fun onError(code: Int) {
                when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { failures = 0; next(500) }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { problem = tr(R.string.wake_no_mic_permission); next(0); disarm() }
                    else -> if (++failures >= 5) { problem = tr(R.string.wake_failed, code); next(0); disarm() } else next(1_000L * failures)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE))
        }.onFailure { next(2_000) }
    }

    /** Stops the AudioRecord feeding the current session. */
    private var stopFeed: (() -> Unit)? = null

    private fun later(ms: Long) {
        cycle?.cancel()
        cycle = graph.scope.launch { delay(ms); cycle = null; listen() }
    }

    /** The phrase heard: stop listening (the call needs the microphone) and start a Live call. */
    private fun check(b: Bundle?, r: SpeechRecognizer): Boolean {
        val heard = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (heard.none { WakePhrase.matches(it, effectivePhrase()) }) return false
        android.util.Log.i("Wake", "phrase heard")
        stopFeed?.invoke(); stopFeed = null
        r.runCatching { cancel(); destroy() }
        if (recognizer === r) recognizer = null
        listening = false
        graph.talkWhenReady { problem = it }
        later(3_000) // re-arms once the call (which holds the microphone meanwhile) has ended
        return true
    }
}
