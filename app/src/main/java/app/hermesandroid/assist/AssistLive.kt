package app.hermesandroid.assist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.ScanLine
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.voice.CallStatus
import app.hermesandroid.voice.HudPortrait
import app.hermesandroid.voice.LiveCall

/**
 * Gemini Live as the phone's assistant, in the [AssistCard]: AllFather's portrait with the call's HUD rings following
 * the voice, the last things said, and type / mute.
 */
@Composable
fun AssistLive(graph: AppGraph, problem: String?, starting: Boolean, close: () -> Unit, type: () -> Unit) {
    val c = C.colors
    val call = graph.liveCall
    val connecting = starting || call.state == LiveCall.State.Connecting || call.state == LiveCall.State.Reconnecting
    AssistCard(close) {
        AssistHead(
            stringResource(if (call.active) R.string.assistlive_live else R.string.assistlive_assistant), status = { CallStatus(call, starting = starting) },
            live = call.active && !connecting, close = close,
        ) { HudPortrait(level = { call.level }, live = call.active, size = 64.dp, modifier = Modifier.padding(6.dp)) }
        if (connecting) ScanLine(Modifier.fillMaxWidth()) else Divider(inset = 0.dp)
        Said(call, problem, starting)
        call.jobs.lastOrNull()?.let { j -> Text("${j.request.take(48)} · ${stringResource(app.hermesandroid.voice.jobStatusLabel(j.status))}", style = CType.data, color = c.secondary, maxLines = 1) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val half = Modifier.weight(1f).heightIn(min = 44.dp)
            val muted = call.muted
            SettingsButton(stringResource(R.string.assistlive_type), AF.Keyboard, modifier = half, onClick = type)
            SettingsButton(stringResource(if (muted) R.string.assistlive_unmute else R.string.assistlive_mute), if (muted) AF.MicOff else AF.Mic, prominent = muted, modifier = half) {
                if (call.active) call.muted = !muted
            }
        }
    }
}

/** The last two things said, the newest bright (as on the call screen); its own scope, so streamed words redraw only this. */
@Composable
private fun Said(call: LiveCall, problem: String?, starting: Boolean) {
    val c = C.colors
    val failed = problem ?: call.error.takeUnless { starting }
    val said = if (starting) emptyList() else call.lines.filter { it.second.isNotBlank() }.takeLast(2)
    when {
        failed != null -> Text(failed, style = CType.callout, color = c.red)
        said.isEmpty() -> Text(stringResource(R.string.assistlive_how_can_i_help), style = CType.title3, color = c.tertiary)
        else -> said.forEachIndexed { i, (_, text) ->
            val latest = i == said.lastIndex
            Text(
                text.trim().let { if (latest && it.length > 220) "…" + it.takeLast(220) else it },
                style = if (latest) CType.title3 else CType.callout, color = if (latest) c.text else c.tertiary,
                maxLines = if (latest) 5 else 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
