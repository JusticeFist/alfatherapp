package app.hermesandroid.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.Wordmark
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.theme.Backdrop
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.reducedMotion
import app.hermesandroid.ui.theme.settingsCard
import kotlinx.coroutines.delay

// The live call screen (LiveVoiceCallSheet): AllFather's profile inside thin HUD rings that follow the voice,
// the transcript, Hermes jobs, mute and hang up.

@Composable
fun LiveCallScreen(graph: AppGraph, onClose: () -> Unit) {
    val c = C.colors
    val call = graph.liveCall
    val list = rememberLazyListState()
    LaunchedEffect(call.lines.size) { if (call.lines.isNotEmpty()) list.animateScrollToItem(call.lines.size - 1) }
    Box(Modifier.fillMaxSize()) {
        Backdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow(stringResource(if (call.active) R.string.livecallscreen_live else R.string.livecallscreen_call), Modifier.padding(top = 20.dp))
            Wordmark(app.hermesandroid.ui.shell.defaultProfileTitle, CType.title2, Modifier.padding(top = 4.dp))
            CallStatus(call, Modifier.padding(top = 6.dp))
            HudPortrait(level = { call.level }, live = call.active, modifier = Modifier.padding(vertical = 26.dp))
            call.error?.let { Text(it, style = CType.footnote, color = c.red, textAlign = TextAlign.Center) }
            call.savedSession?.let { sid ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.livecallscreen_saved) + "  ", style = CType.footnote, color = c.secondary)
                    Text(stringResource(R.string.livecallscreen_open), style = CType.footnote.medium(), color = c.text, modifier = Modifier.pressable { onClose(); graph.chat.open(sid, call.savedTitle) })
                }
            }
            if (call.jobs.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).settingsCard(Radii.lg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(stringResource(R.string.livecallscreen_jobs))
                call.jobs.forEach { j ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${j.n}. ${j.request}", style = CType.footnote, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(stringResource(jobStatusLabel(j.status)), style = CType.data, color = if (j.status == "done") c.success else if (j.status == "error") c.red else c.attention)
                        if (j.storedId != null) Text("  " + stringResource(R.string.livecallscreen_open), style = CType.caption.medium(), color = c.text, modifier = Modifier.pressable { onClose(); graph.chat.open(j.storedId, j.request.take(40)) })
                    }
                }
            }
            // The newest line is bright, the ones before it fade back.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(call.lines) { i, (user, text) ->
                    val latest = i == call.lines.lastIndex
                    Text(text, style = CType.callout, color = if (latest) c.text else if (user) c.tertiary else c.secondary, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                if (call.active) RoundButton(if (call.muted) AF.MicOff else AF.Mic, stringResource(if (call.muted) R.string.livecallscreen_unmute else R.string.livecallscreen_mute), c.raised, c.text, 60.dp) { call.muted = !call.muted }
                RoundButton(AF.CallEnd, stringResource(if (call.active) R.string.livecallscreen_hang_up else R.string.livecallscreen_close), c.red, c.text, 68.dp) { if (call.active) call.hangUp() else onClose() }
            }
        }
    }
}

/**
 * "listening · 00:42" in mono: the call's state in a word and how long it has run, ticking each second while live
 * (its own scope, so the tick redraws only this line). [starting]: the assistant's call isn't up yet.
 */
@Composable
internal fun CallStatus(call: LiveCall, modifier: Modifier = Modifier, starting: Boolean = false) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.active) { while (call.active) { now = System.currentTimeMillis(); delay(1000) } }
    val state = when (call.state) {
        LiveCall.State.Connecting -> R.string.livecallscreen_connecting
        LiveCall.State.Listening -> if (call.muted) R.string.livecallscreen_muted else R.string.livecallscreen_listening
        LiveCall.State.Speaking -> R.string.livecallscreen_speaking
        LiveCall.State.Reconnecting -> R.string.livecallscreen_reconnecting
        LiveCall.State.Ended, LiveCall.State.Idle -> R.string.livecallscreen_ended
    }.let { stringResource(it) }
    val connecting = stringResource(R.string.livecallscreen_connecting)
    val secs = if (call.active && call.startedAt > 0) ((now - call.startedAt) / 1000).coerceAtLeast(0) else null
    val text = if (starting) connecting else listOfNotNull(state, secs?.let { "%02d:%02d".format(it / 60, it % 60) }).joinToString(" · ")
    Text(text, style = CType.data, color = C.colors.secondary, maxLines = 1, modifier = modifier)
}

/**
 * AllFather's profile in a circle with thin concentric HUD rings around it. The rings swell with [level] (read every
 * frame, smoothed) and their open arcs turn slowly while [live]; reduced motion keeps them still.
 */
@Composable
internal fun HudPortrait(level: () -> Float, live: Boolean, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val c = C.colors
    val still = reducedMotion()
    var smooth by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(live, still) {
        if (!live || still) { smooth = 0f; return@LaunchedEffect }
        while (true) withFrameNanos { smooth += (level() - smooth) * if (level() > smooth) 0.35f else 0.08f }
    }
    val spin = rememberInfiniteTransition(label = "hud").animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "spin")
    Box(
        // Its own layer: the rings redraw every frame without re-recording the screen around them.
        modifier.size(size).graphicsLayer().drawBehind {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val base = this.size.minDimension * 0.34f
            val turn = if (live && !still) spin.value else 0f
            // Three rings, each a little further out and fainter; the louder the voice, the further they open.
            for (k in 0 until 3) {
                val r = base + ((14 + 18 * k) + smooth * (10 + 8 * k)) * this.size.minDimension / 240f
                val a = (0.5f - 0.13f * k) * (0.55f + 0.45f * smooth)
                val sweep = 300f - 40f * k
                rotate(turn * (if (k % 2 == 0) 1f else -0.6f) + 50f * k, center) {
                    drawArc(c.text.copy(alpha = a), 0f, sweep, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(1.dp.toPx()))
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.af_voice), null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size * 0.62f).clip(CircleShape).border(androidx.compose.ui.unit.Dp.Hairline, c.composerStroke, CircleShape),
        )
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color, size: Dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(size).clip(CircleShape).background(bg).pressable(label = label, haptic = Haptic.Medium, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
        }
        Text(label, style = CType.caption, color = C.colors.secondary)
    }
}

/** The voice button's action: back to a running call, or ask for the microphone and start one. */
@Composable
fun rememberVoiceAction(graph: AppGraph): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun begin() { graph.startVoice()?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() } }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else android.widget.Toast.makeText(ctx, app.hermesandroid.tr(R.string.livecallscreen_mic_needed, app.hermesandroid.ui.shell.defaultProfileTitle), android.widget.Toast.LENGTH_SHORT).show()
    }
    return {
        when {
            graph.liveCall.active -> graph.showCall = true
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED -> begin()
            else -> permission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
}
