package app.hermesandroid.control

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R
import app.hermesandroid.ui.components.AppIconArt
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.ScanLine
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.shell.CapsuleIcon
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.AfTheme
import app.hermesandroid.ui.theme.Motion
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.settingsCard

/** What the bubble is showing: Allfather at a step, thinking of the next, asking before a risky one, stopping, done. */
enum class Phase { Working, Thinking, Asking, Stopping, Done }

/** The bubble's content: the phase, one line (the step, the question, the answer) and the steps taken. */
class Bubble(val phase: Phase, val line: String, val steps: Int)

/**
 * A bubble over every app while Allfather drives the phone (a notification is easy to miss): its portrait, what it's
 * doing, the step count and «Parar»; it asks there before a risky step, and says when it's done. An accessibility
 * overlay (no "draw over apps" permission), never focused and only as wide as its card, so the app keeps its touches.
 */
internal class ControlBubble(private val service: AccessibilityService) : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Main thread. The bubble then follows [PhoneAgent.bubble] and takes its window away once it has slid out. */
    fun show() {
        if (view != null) return
        val v = ComposeView(service).apply {
            setViewTreeLifecycleOwner(this@ControlBubble)
            setViewTreeSavedStateRegistryOwner(this@ControlBubble)
            setContent { AfTheme { BubbleWindow(onGone = ::close) } }
        }
        registry.currentState = Lifecycle.State.RESUMED
        runCatching { wm.addView(v, params) }.onSuccess { view = v }
    }

    fun close() {
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Out of the way (invisible, touches pass through) while a capture or a tap gesture happens under it. Main thread. */
    fun aside(on: Boolean) {
        val v = view ?: return
        v.alpha = if (on) 0f else 1f
        params.flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(v, params) }
    }
}

@Composable
private fun BubbleWindow(onGone: () -> Unit) {
    val now = PhoneAgent.bubble
    // The last bubble stays drawn while it slides out.
    val last = remember { mutableStateOf(now) }
    if (now != null) SideEffect { last.value = now }
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = now != null
    LaunchedEffect(shown.isIdle, shown.currentState) { if (shown.isIdle && !shown.currentState) onGone() }
    val b = now ?: last.value ?: return
    AnimatedVisibility(shown, enter = slideInVertically(Motion.ease()) { -it } + fadeIn(Motion.ease()), exit = slideOutVertically(Motion.ease()) { -it } + fadeOut(Motion.ease())) {
        BubbleCard(b)
    }
}

@Composable
private fun BubbleCard(b: Bubble) {
    val c = C.colors
    val busy = b.phase == Phase.Working || b.phase == Phase.Thinking
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(min = 280.dp, max = 440.dp)
            .settingsCard(Radii.xl).border(Dp.Hairline, c.hairline, Radii.xl).clip(Radii.xl)
            .animateContentSize(Motion.ease()),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AppIconArt(34.dp)
                if (busy) Box(Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(c.surface).padding(2.dp).clip(CircleShape).background(c.success))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow(when (b.phase) {
                        Phase.Working -> stringResource(R.string.controlbubble_working)
                        Phase.Thinking -> stringResource(R.string.controlbubble_thinking)
                        Phase.Asking -> stringResource(R.string.controlbubble_asking)
                        Phase.Stopping -> stringResource(R.string.controlbubble_stopping)
                        Phase.Done -> stringResource(R.string.controlbubble_done)
                    })
                    if (b.steps > 0) Text("${b.steps}/$MAX_STEPS", style = CType.data, color = c.tertiary)
                }
                Text(b.line, style = CType.subheadline, color = c.text, maxLines = if (busy) 1 else 3, overflow = TextOverflow.Ellipsis)
            }
            when (b.phase) {
                Phase.Working, Phase.Thinking -> CapsuleIcon(AF.Stop, stringResource(R.string.controlbubble_stop), onClick = PhoneAgent::stopFromBubble)
                Phase.Done -> CapsuleIcon(AF.Close, stringResource(R.string.controlbubble_close), tint = c.secondary) { PhoneAgent.dismissBubble(b) }
                else -> Spacer(Modifier.width(12.dp))
            }
        }
        if (b.phase == Phase.Asking) Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            SettingsButton(stringResource(R.string.controlbubble_not_now)) { PhoneAgent.answerFromBubble(false) }
            SettingsButton(stringResource(R.string.controlbubble_do_it), prominent = true) { PhoneAgent.answerFromBubble(true) }
        }
        if (busy) ScanLine(Modifier.fillMaxWidth())
    }
}
