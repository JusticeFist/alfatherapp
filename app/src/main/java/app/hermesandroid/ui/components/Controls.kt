package app.hermesandroid.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.hermesandroid.R
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.reducedMotion
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.settingsCard

/** AllFather's portrait in a circle with a hairline rim: the app's identity on login, lock and the assistant. */
@Composable
fun AppIconArt(size: Dp) {
    Image(
        painterResource(R.drawable.af_avatar),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape).border(Dp.Hairline, C.colors.composerStroke, CircleShape),
    )
}

/** Letterspaced uppercase label above a title, a group or a field. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = C.colors.secondary) =
    Text(text.uppercase(), style = CType.eyebrow, color = color, modifier = modifier, maxLines = 1)

/** A list section's letterspaced title with a fold chevron; the count shows while folded. */
@Composable
fun SectionHeader(title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    val c = C.colors
    val angle by animateFloatAsState(if (collapsed) 0f else 90f, label = "chev")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).pressable(haptic = Haptic.Selection, onClick = onToggle).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(title)
        Icon(AF.KeyboardArrowRight, null, tint = c.tertiary, modifier = Modifier.size(13.dp).rotate(angle))
        Spacer(Modifier.weight(1f))
        if (collapsed) Text("%02d".format(count), style = CType.data, color = c.tertiary)
    }
}

/** "5 min. ago" for a host timestamp in epoch seconds (chat list, bots, Pulse). */
fun ago(epochSeconds: Double): String =
    DateUtils.getRelativeTimeSpanString((epochSeconds * 1000).toLong(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** The brand's letterspaced uppercase name: login, lock, the sidebar's head, calls. */
@Composable
fun Wordmark(text: String, style: TextStyle = CType.largeTitle, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = style.copy(letterSpacing = 0.32.em), color = C.colors.text, modifier = modifier, maxLines = 1)

/** A tab's opening: letterspaced eyebrow, a light uppercase title, and an optional mono figure on the right. */
@Composable
fun ScreenHeader(eyebrow: String, title: String, data: String? = null, modifier: Modifier = Modifier) {
    val c = C.colors
    Column(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(eyebrow)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title.uppercase(), style = CType.largeTitle, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
            if (data != null) Text(data, style = CType.data.copy(fontSize = CType.callout.fontSize), color = c.secondary, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

/** A hairline with a short light sweeping along it: the agent at work. Holds still under reduced motion. */
@Composable
fun ScanLine(modifier: Modifier = Modifier) {
    val c = C.colors
    val still = reducedMotion()
    val t = rememberInfiniteTransition(label = "scan")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t")
    Box(
        modifier.height(1.dp).drawBehind {
            drawRect(c.hairline)
            val w = size.width * 0.3f
            val x = (size.width - w) * (if (still) 0.5f else t.value) // read here: only the draw phase reruns
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.text, Color.Transparent), startX = x, endX = x + w), Offset(x, 0f), Size(w, size.height))
        },
    )
}

/** Text tabs: the selected one bright with a thin underline, the rest grey; a hairline runs under the row. */
@Composable
fun UnderlineTabs(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = C.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                Column(Modifier.pressable(haptic = Haptic.Selection) { onSelect(i) }.padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = CType.callout, color = if (sel) c.text else c.tertiary)
                    Box(Modifier.padding(top = 8.dp).height(1.5.dp).width(if (sel) 28.dp else 0.dp).background(c.text))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(c.hairline))
    }
}

/** Progress as a row of thin segments (at most 12), the filled ones bright. */
@Composable
fun SegmentBar(done: Int, total: Int, modifier: Modifier = Modifier) {
    val c = C.colors
    val n = total.coerceIn(1, 12)
    val lit = if (total <= 0) 0 else Math.round(done.toFloat() / total * n)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(n) { Box(Modifier.weight(1f).height(2.dp).clip(Radii.pill).background(if (it < lit) c.text else c.text.copy(alpha = 0.14f))) }
    }
}

/** A filter chip: hairline-outlined pill, filled off-white when selected. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = C.colors
    Box(
        Modifier
            .clip(Radii.pill)
            .background(if (selected) c.accent else Color.Transparent)
            .border(Dp.Hairline, if (selected) c.accent else c.text.copy(alpha = 0.24f), Radii.pill)
            .pressable(haptic = Haptic.Selection, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, style = CType.footnote.medium(), color = if (selected) c.onAccent else c.text) }
}

/** Login-style field: eyebrow label, open layout, a bottom hairline that lights up when focused. */
@Composable
fun AfField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    placeholder: String,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = C.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Eyebrow(label)
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = CType.body, color = c.tertiary)
                BasicTextField(
                    value, onValueChange,
                    singleLine = true,
                    textStyle = CType.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = keyboard,
                    visualTransformation = visual,
                    interactionSource = source,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
        Box(Modifier.fillMaxWidth().height(if (focused) 1.dp else Dp.Hairline).background(if (focused) c.accent.copy(alpha = 0.8f) else c.text.copy(alpha = 0.18f)))
    }
}

/** The screen's one primary action: full-width graphite block, medium label. */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val c = C.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(Radii.md)
            .background(c.text.copy(alpha = if (enabled) 0.12f else 0.05f))
            .border(Dp.Hairline, c.composerStroke, Radii.md)
            .pressable(enabled = enabled && !busy, haptic = Haptic.Medium, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) c.text else c.tertiary
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = c.text, strokeWidth = 1.5.dp)
        } else {
            Text(text, style = CType.callout.medium(), color = fg)
        }
    }
}

/** Secondary action: hairline-outlined pill; `prominent` fills it off-white. */
@Composable
fun SettingsButton(text: String, icon: ImageVector? = null, prominent: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = C.colors
    Row(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(Radii.pill)
            .background(if (prominent) c.accent else Color.Transparent)
            .border(Dp.Hairline, if (prominent) c.accent else c.text.copy(alpha = 0.28f), Radii.pill)
            .pressable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        val fg = if (prominent) c.onAccent else c.text
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, style = CType.subheadline.medium(), color = fg)
    }
}

/** A small icon centred in a dark circular well (rows, list avatars). */
@Composable
fun IconWell(icon: ImageVector, size: Dp = 34.dp, tint: Color = C.colors.text) {
    Box(Modifier.size(size).clip(CircleShape).background(C.colors.raised), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Eyebrow + soft card. Rows inside get a hairline between them, inset to the text. */
@Composable
fun SettingsGroup(title: String, @Suppress("UNUSED_PARAMETER") icon: ImageVector, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title, Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxWidth().settingsCard(Radii.lg)) { content() }
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    detail: String? = null,
    first: Boolean = false,
    dot: Color? = null,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val c = C.colors
    Column {
        if (!first) Divider(inset = 64.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.pressable(haptic = Haptic.Light, onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconWell(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = CType.callout.medium(), color = c.text)
                if (detail != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dot != null) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(detail, style = CType.footnote, color = c.secondary, maxLines = 2)
                }
            }
            if (chevron && onClick != null) Icon(AF.Forward, null, tint = c.tertiary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A hairline rule with a leading inset. */
@Composable
fun Divider(inset: Dp = 32.dp) {
    Box(Modifier.fillMaxWidth().padding(start = inset).height(Dp.Hairline).background(C.colors.hairline))
}

/** Caption-style status line used under titles ("Reconnecting…"). */
@Composable
fun StatusLine(text: String, color: Color, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, style = CType.caption.medium(), color = color)
    }
}
