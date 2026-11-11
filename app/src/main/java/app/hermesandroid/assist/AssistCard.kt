package app.hermesandroid.assist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hermesandroid.R
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.Wordmark
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.shell.CapsuleIcon
import app.hermesandroid.ui.shell.defaultProfileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Motion
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.settingsCard

/**
 * The assistant's window over whatever app is open (Live and the listening panel): the app dims toward the bottom and
 * stays readable above, and a floating card holds the assistant. Tapping outside the card closes it; no [scrim]
 * while Allfather works the phone.
 */
@Composable
internal fun AssistCard(close: () -> Unit, scrim: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = C.colors
    val dim = MaterialTheme.colorScheme.scrim
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        Modifier.fillMaxSize()
            .then(if (scrim) Modifier.background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to dim)) else Modifier)
            .clickable(remember { MutableInteractionSource() }, null) { close() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visibleState = shown, enter = slideInVertically(Motion.ease()) { it / 4 } + fadeIn(Motion.ease())) {
            Column(
                Modifier.navigationBarsPadding().padding(10.dp).widthIn(max = 560.dp).fillMaxWidth()
                    .settingsCard(Radii.xl).border(Dp.Hairline, c.hairline, Radii.xl)
                    .clickable(remember { MutableInteractionSource() }, null) {}.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

/** The card's head: [portrait], an eyebrow (with the live dot) over AllFather's name, the state in mono, and close. */
@Composable
internal fun AssistHead(eyebrow: String, status: (@Composable () -> Unit)? = null, live: Boolean = false, close: () -> Unit, portrait: @Composable () -> Unit) {
    val c = C.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        portrait()
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) Box(Modifier.size(6.dp).clip(CircleShape).background(c.success))
                Eyebrow(eyebrow)
            }
            Wordmark(defaultProfileTitle, CType.title3)
            status?.invoke()
        }
        CapsuleIcon(AF.Close, stringResource(R.string.assistcard_close), tint = c.secondary, onClick = close)
    }
}
