package app.hermesandroid.ui.theme

import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** The app canvas: plain matte graphite (no glows, no grid; the user turned both down). */
@Composable
fun Backdrop(modifier: Modifier = Modifier) {
    val c = C.colors
    Box(modifier.drawBehind { drawRect(c.canvas) })
}

/** System animations off or battery saver: ambient motion (seams, rings) holds still. */
@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        val scale = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scale == 0f || ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    }
}

/** Floating chrome (capsules over content): translucent graphite with a hairline rim. */
fun Modifier.glass(shape: Shape, tint: Color? = null, prominent: Boolean = false): Modifier = composed {
    val c = C.colors
    this
        .clip(shape)
        .background(if (prominent && tint != null) tint.copy(alpha = 0.92f) else c.raised.copy(alpha = 0.86f))
        .then(if (!prominent && tint != null) Modifier.background(tint) else Modifier)
        .border(Dp.Hairline, c.composerStroke, shape)
}

/** The screen content that floating chrome blurs (MainActivity.Root marks it with `hazeSource`). */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * Frosted glass for chrome floating over scrolling content (tab capsule, top-bar capsule): real blur of what's
 * behind on API 31+, Haze's translucent fallback below. Without a [LocalHaze] source it is plain [glass].
 */
fun Modifier.frosted(shape: Shape): Modifier = composed {
    val state = LocalHaze.current ?: return@composed glass(shape)
    val c = C.colors
    this
        .clip(shape)
        .hazeBlur(HazeInput.Backdrop(state), HazeBlurStyle {
            blurRadius(28.dp)
            backgroundColor(c.canvas)
            colorEffects(listOf(HazeColorEffect.tint(c.raised.copy(alpha = 0.62f))))
            noiseFactor(0.08f)
        })
        .border(Dp.Hairline, c.composerStroke, shape)
}

/** A soft graphite card: one step above the canvas, no stroke. */
fun Modifier.settingsCard(shape: Shape, tint: Color? = null): Modifier = composed {
    val c = C.colors
    this
        .clip(shape)
        .background(c.surface)
        .then(if (tint != null) Modifier.background(tint) else Modifier)
}

/** A raised well with a hairline stroke: code, headers and asks set into a card, sheet or page. */
fun Modifier.well(shape: Shape): Modifier = composed {
    val c = C.colors
    background(c.raised, shape).border(Dp.Hairline, c.hairline, shape)
}

/** Fades what this draws into the canvas, from `from` (0 = top) to the bottom edge: hero portraits. */
fun Modifier.fadeToCanvas(from: Float = 0.5f): Modifier = composed {
    val canvas = C.colors.canvas
    drawWithCache {
        val fade = Brush.verticalGradient(from to Color.Transparent, 1f to canvas)
        onDrawWithContent { drawContent(); drawRect(fade) }
    }
}

/** Every tappable scales to 0.95 on the overshoot spring while pressed. */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    label: String? = null,
    haptic: Haptic? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.overshoot(), label = "press")
    val view = LocalView.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(source, null, enabled = enabled, onClickLabel = label, role = role, onLongClick = onLongClick) {
            haptic?.perform(view)
            onClick()
        }
}

/** The haptic vocabulary mapped onto View haptic constants. */
enum class Haptic {
    Selection, Light, Medium, Warning, Success;

    fun perform(view: View) {
        if (!DisplayPrefs.haptics) return
        val r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        view.performHapticFeedback(
            when (this) {
                Selection -> HapticFeedbackConstants.CLOCK_TICK
                Light -> HapticFeedbackConstants.KEYBOARD_TAP
                Medium -> HapticFeedbackConstants.CONTEXT_CLICK
                Warning -> if (r) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                Success -> if (r) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            }
        )
    }
}

