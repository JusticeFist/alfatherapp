package app.hermesandroid.assist

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.chat.ReplyBody
import app.hermesandroid.graph
import app.hermesandroid.ui.components.AppIconArt
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.control.Action
import app.hermesandroid.control.CONTROL_RULES
import app.hermesandroid.control.ControlService
import app.hermesandroid.control.PhoneAgent
import app.hermesandroid.control.parseReply
import kotlinx.coroutines.launch
import app.hermesandroid.ui.icons.AF

/**
 * The assistant panel, drawn inside the system's assistant session (one window: closing it is `hide()`, nothing left
 * behind): listens at once, shows what you said and Allfather's answer as it's written, reads it aloud sentence by
 * sentence. Its chat is the "Assistant" one.
 */
class AssistPanel(private val context: Context, private val close: () -> Unit) {
    private val graph = context.graph
    private var heard by mutableStateOf("")
    private var answer by mutableStateOf("")
    private var status by mutableStateOf(tr(R.string.assistpanel_listening))
    private var busy by mutableStateOf(false)
    private val voice = ClipVoice(graph, context)
    /** Phone control in progress: the step count (risky steps are asked on the control bubble, over the top). */
    private var steps by mutableStateOf(0)
    private val controlling get() = steps > 0

    fun start() {
        heard = ""; answer = ""; busy = false; steps = 0
        listen()
    }

    fun stop() { graph.dictation.stop(); voice.stop(); steps = 0 }

    private fun listen() {
        voice.stop()
        heard = ""; status = tr(R.string.assistpanel_listening)
        // The chat gets ready (connection, session) while you speak, not after.
        graph.scope.launch { graph.assist.prepare() }
        graph.dictation.listenOnce(onText = { heard = it }, onFinal = { said ->
            android.util.Log.i("Assist", "heard: ${said.length} chars")
            if (said.isBlank()) status = graph.dictation.error ?: tr(R.string.assistpanel_not_heard)
            else ask(said)
        })
    }

    private fun ask(question: String) {
        heard = question; answer = ""; status = tr(R.string.assistpanel_thinking); busy = true; steps = 0
        send(prompt(question))
    }

    /** One turn; with phone control on, a reply carrying steps runs them and sends back the new screen. */
    private fun send(message: String, image: ByteArray? = null) {
        graph.scope.launch {
            graph.assist.ask(message, image, onText = { soFar, done ->
                // The steps block is for the app, not for reading: show and speak only the words around it.
                val words = soFar.substringBefore("```").trim()
                answer = if (done) parseReply(soFar).say.ifBlank { words } else words
                if (!done) { status = if (controlling) tr(R.string.assistpanel_step, steps) else tr(R.string.assistpanel_answering); return@ask }
                val reply = parseReply(soFar)
                if (reply.actions.isNotEmpty() && ControlService.instance != null) graph.scope.launch { act(reply.actions) }
                else { status = ""; busy = false; steps = 0; PhoneAgent.finished(answer); voice.feed(answer, final = true) }
            }, onError = { err -> android.util.Log.w("Assist", "ask: $err"); status = err; busy = false; steps = 0 })
        }
    }

    /** Runs Allfather's steps (the bubble asks for the risky ones), then shows it the screen they led to. */
    private suspend fun act(actions: List<Action>) {
        val step = PhoneAgent.run(actions, steps, notify = false, progress = { status = it }) ?: return
        steps = step.steps
        send(step.text, step.shot)
    }

    /** What the agent gets: how to answer here, the screen (numbered when it can act on it), then the question. */
    private fun prompt(question: String): String = buildString {
        append("[Phone assistant: reply in 1 to 3 sentences, in the user's language; it will be read aloud]")
        val screen = PhoneAgent.screenBlock()
        if (screen != null) append("\n").append(CONTROL_RULES).append("\n").append(screen)
        else AssistScreen.text?.let { screen ->
            append("\n[On screen").append(AssistScreen.app?.let { " ($it)" } ?: "").append(":\n").append(screen.take(3000)).append("\n]")
        }
        append("\n").append(question)
    }

    private fun openChat() {
        val stored = graph.assist.storedId ?: return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(app.hermesandroid.push.EXTRA_SESSION, stored).putExtra(app.hermesandroid.push.EXTRA_TITLE, tr(R.string.assistpanel_chat_title)))
        close()
    }

    @Composable
    fun Content() {
        val c = C.colors
        // The open app stays clear while Allfather works it.
        AssistCard(close, scrim = !controlling) {
            AssistHead(stringResource(R.string.assistpanel_assistant), close = close) { AppIconArt(44.dp) }
            if (heard.isNotBlank()) Text(stringResource(R.string.assistpanel_heard, heard), style = CType.subheadline, color = c.secondary)
            if (answer.isNotBlank()) Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { ReplyBody(answer) }
            if (status.isNotBlank()) Text(status, style = CType.footnote, color = c.tertiary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val listening = graph.dictation.listening
                Box(Modifier.size(56.dp).clip(CircleShape).background(if (listening) c.accent else c.raised)
                    .pressable(label = stringResource(R.string.assistpanel_speak)) { if (!busy) listen() }, contentAlignment = Alignment.Center) {
                    Icon(AF.Mic, null, tint = if (listening) c.onAccent else c.text)
                }
                if (graph.assist.storedId != null) SettingsButton(stringResource(R.string.assistpanel_open_chat), AF.ChatBubbleOutline) { openChat() }
            }
        }
    }
}
