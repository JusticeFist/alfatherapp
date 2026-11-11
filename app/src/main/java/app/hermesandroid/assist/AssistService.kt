package app.hermesandroid.assist

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import app.hermesandroid.graph
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// Allfather as the phone's digital assistant (Settings > Apps > Default apps > Digital assistant): holding the power
// button (or swiping from a bottom corner) opens AssistActivity listening, with the text on screen as context.

class AllfatherAssistService : VoiceInteractionService() {
    // Only the active assistant's service is ready; it can open the panel itself (the Quick Settings tile does).
    override fun onReady() { super.onReady(); instance = this }
    override fun onShutdown() { instance = null; super.onShutdown() }

    companion object {
        @Volatile var instance: AllfatherAssistService? = null
    }
}

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

class AssistSession(context: android.content.Context) : VoiceInteractionSession(context),
    androidx.lifecycle.LifecycleOwner, androidx.savedstate.SavedStateRegistryOwner {
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedState = androidx.savedstate.SavedStateRegistryController.create(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = savedState.savedStateRegistry
    private val panel = AssistPanel(context) { hide() }
    private var live by androidx.compose.runtime.mutableStateOf(false)
    private var problem by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** Waiting for this call to start (the last call's Ended state is still there until then). */
    private var starting by androidx.compose.runtime.mutableStateOf(false)
    private var watch: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
    }

    /** The panel is this session's own window (no activity on top), so closing it leaves nothing behind. */
    override fun onCreateContentView(): android.view.View {
        window.window?.decorView?.let { v ->
            v.setViewTreeLifecycleOwner(this)
            v.setViewTreeSavedStateRegistryOwner(this)
        }
        return androidx.compose.ui.platform.ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@AssistSession)
            setViewTreeSavedStateRegistryOwner(this@AssistSession)
            setContent {
                app.hermesandroid.ui.theme.AfTheme {
                    if (live) AssistLive(context.graph, problem, starting, close = ::close, type = {
                        startAssistantActivity(app.hermesandroid.launcher.launchIntent(context, app.hermesandroid.launcher.LAUNCH_NEW_CHAT)); close()
                    }) else panel.Content()
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistScreen.text = null; AssistScreen.app = null
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        // Without the microphone permission the panel can't listen: the app asks for it once.
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startAssistantActivity(Intent(context, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide(); return
        }
        // A Gemini Live call in this window by default (Settings > Voice), else the listening panel. The current assistant
        // may start a microphone service from the background, so the call runs here over any app, even locked.
        live = context.graph.store.assistLive
        if (!live) return panel.start()
        val graph = context.graph
        problem = null; starting = !graph.liveCall.active
        graph.talkWhenReady(show = false) { problem = it; starting = false }
        // Once this call ends by itself (Gemini's goodbye, silence), the window goes too.
        watch?.cancel()
        watch = graph.scope.launch {
            androidx.compose.runtime.snapshotFlow { graph.liveCall.active }.first { it }
            starting = false
            androidx.compose.runtime.snapshotFlow { graph.liveCall.active }.first { !it }
            if (graph.liveCall.error == null) { kotlinx.coroutines.delay(1200); hide() }
        }
    }

    /** Closing it ends the call; the system hiding it (Home, an app the call opened) leaves the call going, with its notice. */
    private fun close() {
        val call = context.graph.liveCall
        if (live && call.active) call.hangUp()
        // Closed while connecting: the call on its way hangs up as soon as it starts.
        else if (live && starting) context.graph.scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(30_000) { androidx.compose.runtime.snapshotFlow { call.active }.first { it } }?.let { call.hangUp() }
        }
        hide()
    }

    override fun onBackPressed() = close()

    override fun onHide() {
        watch?.cancel(); watch = null
        panel.stop()
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    /** What's on screen when the user called (only when "use text from screen" is on in the assistant settings). */
    override fun onHandleAssist(state: AssistState) {
        val structure = state.assistStructure ?: return
        AssistScreen.app = structure.activityComponent?.packageName
        AssistScreen.text = screenText(structure).takeIf { it.isNotBlank() }
    }

    private fun screenText(s: AssistStructure): String {
        val out = StringBuilder()
        fun walk(n: AssistStructure.ViewNode) {
            if (out.length > 4000) return
            // Never a password field, whatever it shows.
            val password = n.inputType and android.text.InputType.TYPE_MASK_VARIATION in setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            if (!password) (n.text?.toString() ?: n.contentDescription?.toString())?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it.take(300)).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }
        for (i in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(i).rootViewNode)
        return out.toString().lines().distinct().joinToString("\n").take(4000)
    }
}

/** The screen the assistant was called over. */
object AssistScreen {
    @Volatile var text: String? = null
    @Volatile var app: String? = null
}

/** Android requires an assistant to name a recognition service; Allfather's listening is SpeechRecognizer's. */
class AssistRecognition : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
