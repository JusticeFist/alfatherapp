package app.hermesandroid.settings

import android.speech.SpeechRecognizer
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.obj
import app.hermesandroid.tasks.Segmented
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import app.hermesandroid.ui.icons.AF
import java.util.Locale

// Settings > Voice (VoiceSettingsView): dictation engine, read-aloud voice and speed, and what Hermes itself
// uses for speech. Gemini Live has its own card once the host can mint tokens.

private val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f)

/** Speech languages offered for dictation and Gemini Live ("" = the phone's own). */
private val SPEECH_LANGUAGES = listOf("es-MX", "es-ES", "es-US", "en-US", "en-GB", "pt-BR", "fr-FR", "de-DE", "it-IT")

@Composable
fun VoicePage(graph: AppGraph) {
    val c = C.colors
    val ctx = LocalContext.current
    var dictation by remember { mutableStateOf(graph.store.dictation) }
    var voice by remember { mutableStateOf(graph.store.readAloud) }
    var speed by remember { mutableFloatStateOf(graph.store.readSpeed) }
    var providers by remember { mutableStateOf<Pair<String, String>?>(null) }
    var language by remember { mutableStateOf(graph.store.speechLanguage) }
    val automatic = stringResource(R.string.voicepage_automatic)
    LaunchedEffect(graph.profile) {
        runCatching { graph.hostConfig.get() }.getOrNull()?.let { cfg ->
            providers = (cfg.at("stt.provider").settingText() ?: automatic) to (cfg.at("tts.provider").settingText() ?: "edge")
        }
    }
    Card(stringResource(R.string.voicepage_dictation), AF.Mic) {
        Help(stringResource(R.string.voicepage_dictation_help))
        Segmented(listOf(stringResource(R.string.voicepage_engine_phone), "Hermes"), if (dictation == "hermes") 1 else 0) { i ->
            dictation = if (i == 1) "hermes" else "phone"; graph.store.dictation = dictation
        }
        Help(if (dictation == "hermes") stringResource(R.string.voicepage_engine_hermes_help, providers?.first ?: "…")
            else if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) stringResource(R.string.voicepage_engine_on_device_help)
            else stringResource(R.string.voicepage_engine_phone_help))
        // Each language by its own localized name: "Spanish (Mexico)" / "español (México)".
        val languages = listOf("" to stringResource(R.string.voicepage_language_phone)) +
            SPEECH_LANGUAGES.map { it to Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()).replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) } }
        Picker(stringResource(R.string.voicepage_language), language, languages) { language = it; graph.store.speechLanguage = it }
        Help(stringResource(R.string.voicepage_language_help))
    }
    Card(stringResource(R.string.voicepage_read_aloud), AF.RecordVoiceOver) {
        Help(stringResource(R.string.voicepage_read_aloud_help))
        Segmented(listOf(stringResource(R.string.voicepage_voice_hermes), stringResource(R.string.voicepage_voice_phone)), if (voice == "phone") 1 else 0) { i ->
            voice = if (i == 1) "phone" else "hermes"; graph.store.readAloud = voice
        }
        Help(if (voice == "hermes") stringResource(R.string.voicepage_voice_hermes_help, providers?.second ?: "…") else stringResource(R.string.voicepage_voice_phone_help))
        Text(stringResource(R.string.voicepage_speed, speed), style = CType.footnote, color = c.secondary)
        Slider(
            SPEEDS.indexOfFirst { it >= speed - 0.01f }.coerceAtLeast(0).toFloat(), { i -> speed = SPEEDS[i.toInt()]; graph.store.readSpeed = speed },
            valueRange = 0f..(SPEEDS.size - 1).toFloat(), steps = SPEEDS.size - 2, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
        )
        val reading = graph.readAloud.current == "voice-test"
        val sample = stringResource(R.string.voicepage_voice_sample, app.hermesandroid.ui.shell.defaultProfileTitle)
        SettingsButton(stringResource(if (reading) R.string.voicepage_stop else R.string.voicepage_test_voice)) { graph.readAloud.toggle("voice-test", sample) }
    }
    Card(stringResource(R.string.voicepage_voice_hermes), AF.SettingsVoice) {
        Text(stringResource(R.string.voicepage_transcription, providers?.first ?: "…"), style = CType.subheadline, color = c.text)
        Help(stringResource(R.string.voicepage_hermes_voice_help))
        providers?.second?.let { HermesVoicePicker(graph, it) { p -> providers = providers?.copy(second = p) } }
    }
    AssistantCard(graph)
    GeminiLiveCard(graph)
    WakeCard(graph)
    if (graph.live.available) CallsCard(graph)
}

/** Settings > Calls from Hermes (HermesCallSettingsViews): when the plugin may ring this phone. */
@Composable
private fun CallsCard(graph: AppGraph) {
    val c = C.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var settings by remember { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val path = graph.client.withProfile("/api/plugins/conduit_push/calls")
    LaunchedEffect(graph.profile) {
        runCatching { graph.client.rest("GET", path).obj() }
            .onSuccess { o -> if (o?.get("paired").let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "false" }) note = tr(R.string.voicepage_calls_pair) else settings = o?.get("settings").obj() }
            .onFailure { note = tr(R.string.voicepage_calls_unsupported, it.message.orEmpty()) }
    }
    Card(stringResource(R.string.voicepage_calls_title, app.hermesandroid.ui.shell.defaultProfileTitle), AF.Call) {
        Help(stringResource(R.string.voicepage_calls_help))
        note?.let { Help(it) }
        val s = settings ?: return@Card
        fun flag(k: String, def: Boolean) = s.at(k).settingBool() ?: def
        fun set(k: String, v: Boolean) {
            val prev = s
            settings = s.with(k, kotlinx.serialization.json.JsonPrimitive(v))
            scope.launch {
                runCatching { graph.client.rest("PUT", path, kotlinx.serialization.json.buildJsonObject { put("settings", kotlinx.serialization.json.buildJsonObject { put(k, kotlinx.serialization.json.JsonPrimitive(v)) }) }).obj() }
                    .onSuccess { o -> o?.get("settings").obj()?.let { settings = it } }
                    .onFailure { settings = prev; note = tr(R.string.voicepage_save_failed, it.message.orEmpty()) }
            }
        }
        SwitchRow(stringResource(R.string.voicepage_calls_allow), null, flag("enabled", false)) { set("enabled", it) }
        if (flag("enabled", false)) {
            SwitchRow(stringResource(R.string.voicepage_calls_when_asked), stringResource(R.string.voicepage_calls_when_asked_help), flag("when_asked", true)) { set("when_asked", it) }
            SwitchRow(stringResource(R.string.voicepage_calls_alerts), stringResource(R.string.voicepage_calls_alerts_help), flag("alerts", false)) { set("alerts", it) }
            SwitchRow(stringResource(R.string.voicepage_calls_decides), stringResource(R.string.voicepage_calls_decides_help), flag("decides", false)) { set("decides", it) }
        }
    }
}

/** Edge TTS voices worth offering in Spanish and English (plus one multilingual), as `tts.edge.voice` takes them, with the speaker's name. */
private val EDGE_VOICES = listOf(
    "es-ES-AlvaroNeural" to "Álvaro", "es-MX-JorgeNeural" to "Jorge", "es-US-AlonsoNeural" to "Alonso",
    "es-CO-GonzaloNeural" to "Gonzalo", "es-AR-TomasNeural" to "Tomás", "en-US-BrianMultilingualNeural" to "Brian",
    "es-MX-DaliaNeural" to "Dalia", "es-US-PalomaNeural" to "Paloma", "es-ES-ElviraNeural" to "Elvira",
    "en-US-AriaNeural" to "Aria", "en-US-GuyNeural" to "Guy", "en-US-JennyNeural" to "Jenny",
    "en-GB-SoniaNeural" to "Sonia", "en-GB-RyanNeural" to "Ryan",
)

/** "Jorge · Mexico": the speaker and the voice's country in the phone's language; Aria is Hermes' own default. */
@Composable
private fun edgeVoices(): List<Pair<String, String>> {
    val multilingual = stringResource(R.string.voicepage_voice_multilingual)
    val hermesDefault = stringResource(R.string.voicepage_voice_hermes_default)
    return EDGE_VOICES.map { (id, name) ->
        val where = if ("Multilingual" in id) multilingual else Locale.forLanguageTag(id.substringBeforeLast('-')).displayCountry
        id to ("$name · $where" + if (id == "en-US-AriaNeural") " $hermesDefault" else "")
    }
}

/** Gemini's prebuilt voices (TTS and Live share them), with Google's one-word character. */
private val GEMINI_VOICES = listOf(
    "Algenib" to R.string.voicepage_gemini_gravelly, "Charon" to R.string.voicepage_gemini_informative, "Orus" to R.string.voicepage_gemini_firm,
    "Iapetus" to R.string.voicepage_gemini_clear, "Sadaltager" to R.string.voicepage_gemini_knowledgeable, "Rasalgethi" to R.string.voicepage_gemini_informative,
    "Alnilam" to R.string.voicepage_gemini_firm, "Schedar" to R.string.voicepage_gemini_even, "Enceladus" to R.string.voicepage_gemini_breathy,
    "Fenrir" to R.string.voicepage_gemini_excitable, "Puck" to R.string.voicepage_gemini_upbeat, "Umbriel" to R.string.voicepage_gemini_easygoing,
    "Kore" to R.string.voicepage_gemini_firm_woman, "Aoede" to R.string.voicepage_gemini_breezy_woman, "Leda" to R.string.voicepage_gemini_youthful_woman,
    "Zephyr" to R.string.voicepage_gemini_bright_woman,
)

@Composable
private fun geminiVoices(): List<Pair<String, String>> = GEMINI_VOICES.map { (id, trait) -> id to "$id · ${stringResource(trait)}" }

private fun withCurrent(list: List<Pair<String, String>>, v: String) = if (list.any { it.first == v }) list else listOf(v to v) + list

/** `tts.provider` (Edge or Gemini, the two that need no extra key) and that provider's `voice`, for the whole Hermes. */
@Composable
private fun HermesVoicePicker(graph: AppGraph, provider: String, onProvider: (String) -> Unit) {
    val c = C.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var voice by remember(provider) { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph.profile, provider) {
        voice = runCatching { graph.hostConfig.get().at("tts.$provider.voice").settingText() }.getOrNull()
            ?: when (provider) { "edge" -> "en-US-AriaNeural"; "gemini" -> "Kore"; else -> "" }
    }
    fun save(key: String, value: String, then: () -> Unit) = scope.launch {
        message = runCatching { graph.hostConfig.set(key, kotlinx.serialization.json.JsonPrimitive(value)); then(); tr(R.string.voicepage_saved) }.getOrElse { tr(R.string.voicepage_save_failed, it.message.orEmpty()) }
    }
    Picker(stringResource(R.string.voicepage_synthesis), provider, withCurrent(listOf("gemini" to "Gemini TTS", "edge" to "Edge TTS"), provider)) { next ->
        if (next != provider) save("tts.provider", next) { onProvider(next) }
    }
    val v = voice ?: return
    val voices = when (provider) { "edge" -> edgeVoices(); "gemini" -> geminiVoices(); else -> null } ?: return
    Picker(stringResource(R.string.voicepage_voice), v, withCurrent(voices, v)) { next -> voice = next; save("tts.$provider.voice", next) {} }
    if (provider == "gemini") Help(stringResource(R.string.voicepage_gemini_tts_help))
    message?.let { Text(it, style = CType.footnote, color = c.secondary) }
}

@Composable
private fun GeminiLiveCard(graph: AppGraph) {
    val live = graph.live
    LaunchedEffect(graph.profile) { live.refresh() }
    Card("Gemini Live", AF.GraphicEq) {
        Help(stringResource(R.string.voicepage_live_help))
        Text(live.statusText(), style = CType.subheadline, color = if (live.available) C.colors.success else C.colors.secondary)
        if (live.available) {
            SwitchRow(stringResource(R.string.voicepage_live_use), stringResource(R.string.voicepage_live_use_help), live.enabled) { live.choose(it) }
            var voice by remember { mutableStateOf(graph.store.liveVoice) }
            Picker(stringResource(R.string.voicepage_live_voice), voice, withCurrent(geminiVoices(), voice)) { voice = it; graph.store.liveVoice = it }
            var save by remember { mutableStateOf(graph.store.saveCalls) }
            SwitchRow(stringResource(R.string.voicepage_live_save), stringResource(R.string.voicepage_live_save_help), save) { save = it; graph.store.saveCalls = it }
        }
    }
}

/** The wake phrase (voice/Wake.kt): only while the app is on screen. */
@Composable
private fun WakeCard(graph: AppGraph) {
    val w = graph.wake
    val ctx = LocalContext.current
    val mic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) w.choose(true) }
    Card(stringResource(R.string.voicepage_wake), AF.GraphicEq) {
        Help(stringResource(R.string.voicepage_wake_help, w.effectivePhrase()))
        SwitchRow(stringResource(R.string.voicepage_wake_listen), null, w.enabled) { on ->
            if (on && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) mic.launch(android.Manifest.permission.RECORD_AUDIO)
            else w.choose(on)
        }
        if (w.enabled) {
            var text by remember { mutableStateOf(w.phrase) }
            androidx.compose.material3.OutlinedTextField(text, { text = it; w.choose(true, it) }, androidx.compose.ui.Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.voicepage_wake_phrase)) }, placeholder = { Text(w.effectivePhrase()) })
            Text(w.problem ?: stringResource(if (w.listening) R.string.voicepage_wake_listening else R.string.voicepage_wake_paused),
                style = CType.footnote, color = if (w.problem != null) C.colors.red else C.colors.secondary)
        }
    }
}

/** The phone's digital assistant: whether Allfather is it, and the way to the system setting (the user's choice). */
@Composable
private fun AssistantCard(graph: AppGraph) {
    val ctx = LocalContext.current
    var isDefault by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        isDefault = android.provider.Settings.Secure.getString(ctx.contentResolver, "assistant")?.startsWith(ctx.packageName) == true
        onPauseOrDispose { }
    }
    val name = app.hermesandroid.ui.shell.defaultProfileTitle
    Card(stringResource(R.string.voicepage_assistant), AF.SettingsVoice) {
        Help(stringResource(R.string.voicepage_assistant_help, name))
        Text(if (isDefault) stringResource(R.string.voicepage_assistant_is_default, name) else stringResource(R.string.voicepage_assistant_not_default), style = CType.subheadline, color = if (isDefault) C.colors.success else C.colors.secondary)
        var live by remember { mutableStateOf(graph.store.assistLive) }
        Segmented(listOf("Gemini Live", stringResource(R.string.voicepage_assistant_panel)), if (live) 0 else 1) { i -> live = i == 0; graph.store.assistLive = live }
        Help(stringResource(if (live) R.string.voicepage_assistant_live_help else R.string.voicepage_assistant_panel_help))
        if (!isDefault) SettingsButton(stringResource(R.string.voicepage_assistant_choose)) {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }
    var control by remember { mutableStateOf(app.hermesandroid.control.ControlService.instance != null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { control = app.hermesandroid.control.ControlService.instance != null; onPauseOrDispose { } }
    Card(stringResource(R.string.voicepage_control), AF.RecordVoiceOver) {
        Help(stringResource(R.string.voicepage_control_help, name))
        Text(stringResource(if (control) R.string.voicepage_control_on else R.string.voicepage_control_off), style = CType.subheadline, color = if (control) C.colors.success else C.colors.secondary)
        if (!control) {
            Help(stringResource(R.string.voicepage_control_steps))
            SettingsButton(stringResource(R.string.voicepage_control_open)) { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }
}
