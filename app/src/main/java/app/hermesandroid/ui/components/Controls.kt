package app.hermesandroid.ui.components

import app.hermesandroid.Notices
import androidx.compose.runtime.staticCompositionLocalOf
import app.hermesandroid.Notice
import app.hermesandroid.ui.theme.AppearWith
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateColorAsState
import app.hermesandroid.ui.theme.Swap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import app.hermesandroid.ui.theme.Motion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.res.stringResource
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.semibold
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
import app.hermesandroid.ui.theme.Appearance
import app.hermesandroid.ui.theme.HeroMode
import app.hermesandroid.ui.theme.fadeToCanvas

/**
 * A face in a circle with a hairline rim: the app's identity on login, lock, the assistant and the
 * call. It follows Settings > Appearance — a flat Backdrop in the theme's canvas, the user's own
 * portrait, or the app's art — so the mascot can be turned off everywhere at once.
 */
@Composable
fun FaceArt(art: Int, size: Dp) {
    val c = C.colors
    val rim = Modifier.size(size).clip(CircleShape).border(Dp.Hairline, c.composerStroke, CircleShape)
    if (Appearance.heroMode == HeroMode.Flat) {
        Box(rim.background(c.canvas))
        return
    }
    // The lock screen, the assistant and the call bubble are the app's face, so they follow the
    // user's portrait too (Settings > Appearance) — a bitmap the same size, the same rim.
    val custom = Appearance.portraitImage(with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() })
    if (custom != null) {
        Image(custom, contentDescription = null, contentScale = ContentScale.Crop, modifier = rim)
        return
    }
    Image(painterResource(art), contentDescription = null, contentScale = ContentScale.Crop, modifier = rim)
}

/** AllFather's portrait in a circle: the app's identity wherever it is the app itself. */
@Composable
fun AppIconArt(size: Dp) = FaceArt(R.drawable.af_avatar, size)

/**
 * A hero panel: the strip on login, the greeting's hero in a fresh chat. Three Backdrop modes
 * (Settings > Appearance), and the box and the fade into the canvas are identical in every one, so
 * the text under the hero never moves when the mascot goes away. A backdrop picture whose file is
 * gone (a restored app without its old internal storage) is not an error: it means the app's own art,
 * exactly as a missing portrait does.
 *
 * Callers pass the modifiers that size and shape the panel; the fade is HeroArt's, since every mode
 * needs the same one.
 */
@Composable
fun HeroArt(art: Int, modifier: Modifier = Modifier, px: Int = 1080, fade: Float = 0.5f, alignment: Alignment = Alignment.Center) {
    val c = C.colors
    val image = if (Appearance.heroMode == HeroMode.Picture) Appearance.heroImagePx(px) else null
    when {
        Appearance.heroMode == HeroMode.Flat -> Box(modifier.background(c.canvas).fadeToCanvas(fade))
        image != null -> Image(image, contentDescription = null, contentScale = ContentScale.Crop, alignment = alignment, modifier = modifier.fadeToCanvas(fade))
        else -> Image(painterResource(art), contentDescription = null, contentScale = ContentScale.Crop, alignment = alignment, modifier = modifier.fadeToCanvas(fade))
    }
}

/** Letterspaced uppercase label above a title, a group or a field. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = C.colors.secondary) =
    Text(text.uppercase(), style = CType.eyebrow, color = color, modifier = modifier, maxLines = 1)

/** A list section's letterspaced title with a fold chevron; the count shows while folded. */
@Composable
fun SectionHeader(title: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    val c = C.colors
    val angle by animateFloatAsState(if (collapsed) 0f else 90f, Motion.ease(), label = "chev")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).pressable(haptic = Haptic.Selection, onClick = onToggle).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(title)
        Icon(AF.KeyboardArrowRight, null, tint = c.tertiary, modifier = Modifier.size(13.dp).rotate(angle))
        Spacer(Modifier.weight(1f))
        if (collapsed) Text("%02d".format(count), style = CType.data, color = c.secondary)
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

/** Pull down to reload: the working light sweeps under the header while [onRefresh] runs (at least a beat, so it reads). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        busy, onRefresh = {
            scope.launch {
                busy = true
                val start = System.currentTimeMillis()
                try { onRefresh() } finally {
                    delay((600 - (System.currentTimeMillis() - start)).coerceAtLeast(0))
                    busy = false
                }
            }
        },
        modifier = modifier, state = state,
        indicator = {
            // Composed only while pulled or busy: a hidden scan line would keep the frame clock ticking.
            val showing by remember { derivedStateOf { busy || state.distanceFraction > 0f } }
            if (showing) ScanLine(Modifier.align(Alignment.TopCenter).fillMaxWidth().graphicsLayer { alpha = if (busy) 1f else state.distanceFraction.coerceIn(0f, 1f) })
        },
        content = content,
    )
}

/** Placeholder rows while a list loads: the shape of what's coming, swept by the working light's rhythm (1.5 s). */
@Composable
fun Skeleton(rows: Int = 4, modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    val c = C.colors
    val t = rememberSweep()
    val glow = remember(c) { listOf(Color.Transparent, c.text.copy(alpha = 0.06f), Color.Transparent) }
    // Read in the draw phase only: the bones redraw, nothing recomposes.
    fun Modifier.bone(fraction: Float, height: Dp) = fillMaxWidth(fraction).height(height).clip(Radii.pill).drawBehind {
        drawRect(c.raised)
        if (t != null) {
            val w = size.width * 0.5f
            val x = -w + (size.width + w) * t.value
            drawRect(Brush.horizontalGradient(glow, startX = x, endX = x + w))
        }
    }
    val loading = stringResource(R.string.controls_loading)
    Column(
        modifier.fillMaxWidth().padding(horizontal = inset, vertical = 12.dp).clearAndSetSemantics { contentDescription = loading },
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(rows) { i ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(c.raised))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.bone(BONES[i % BONES.size], 11.dp))
                    Box(Modifier.bone(0.34f, 9.dp))
                }
            }
        }
    }
}

private val BONES = floatArrayOf(0.62f, 0.46f, 0.7f, 0.54f)

/** Nothing here yet: an icon in its well, one line that says so, an optional hint and one way forward. */
@Composable
fun EmptyState(icon: ImageVector, title: String, modifier: Modifier = Modifier, hint: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    val c = C.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IconWell(icon, size = 44.dp, tint = c.secondary)
        Spacer(Modifier.height(6.dp))
        Text(title, style = CType.callout.medium(), color = c.text)
        if (hint != null) Text(hint, style = CType.footnote, color = c.secondary)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            SettingsButton(action, onClick = onAction)
        }
    }
}

/**
 * Something failed: the reason in body text beside a red mark (colour is never the only signal), and a retry when there
 * is one. [inset] is its side margin (Skeleton's too): 0 inside a container that already pads its content.
 */
@Composable
fun ErrorState(text: String, modifier: Modifier = Modifier, inset: Dp = 16.dp, onRetry: (() -> Unit)? = null) {
    val c = C.colors
    Row(
        modifier.fillMaxWidth().padding(horizontal = inset, vertical = 8.dp).settingsCard(Radii.lg)
            .semantics { liveRegion = LiveRegionMode.Polite }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AF.Error, stringResource(R.string.controls_error), tint = c.red, modifier = Modifier.size(16.dp))
        Text(text, style = CType.footnote, color = c.text, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        if (onRetry != null) SettingsButton(stringResource(R.string.controls_retry), onClick = onRetry)
    }
}

/** The working light's clock (1.5 s there and back), shared by ScanLine and Skeleton; null under reduced motion, so nothing ticks. */
@Composable
private fun rememberSweep(): State<Float>? = if (reducedMotion()) null else rememberInfiniteTransition(label = "sweep")
    .animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t")

/** The app's notices, for the places that draw them (the root, and every open sheet). */
val LocalNotices = staticCompositionLocalOf<Notices?> { null }

/** The shared notice (graph.notify): a surface card at the foot, with Undo when it holds one. */
@Composable
fun NoticeBar(notice: Notice?, onUndo: (Notice) -> Unit, onSettle: (Notice) -> Unit, modifier: Modifier = Modifier) {
    val c = C.colors
    LaunchedEffect(notice) { if (notice != null) { delay(notice.shownMs); onSettle(notice) } }
    AppearWith(notice, modifier, resize = false) { n ->
        Row(
            Modifier.fillMaxWidth().settingsCard(Radii.xl).border(Dp.Hairline, c.hairline, Radii.xl)
                .semantics { liveRegion = LiveRegionMode.Polite }.heightIn(min = 52.dp).padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(n.text, style = CType.subheadline, color = c.text, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            if (n.undo != null) Text(
                stringResource(R.string.controls_undo), style = CType.subheadline.semibold(), color = c.accent,
                modifier = Modifier.pressable(haptic = Haptic.Light) { onUndo(n) }.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
    }
}

/** A hairline with a short light sweeping along it: the agent at work. Holds still under reduced motion. */
@Composable
fun ScanLine(modifier: Modifier = Modifier) {
    val c = C.colors
    val t = rememberSweep()
    Box(
        modifier.height(1.dp).drawBehind {
            drawRect(c.hairline)
            val w = size.width * 0.3f
            val x = (size.width - w) * (t?.value ?: 0.5f) // read here: only the draw phase reruns
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
                val line by animateFloatAsState(if (sel) 1f else 0f, Motion.enter(), label = "tab")
                val ink by animateColorAsState(if (sel) c.text else c.secondary, Motion.quick(), label = "tabInk")
                Column(Modifier.pressable(haptic = Haptic.Selection) { onSelect(i) }.padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = CType.callout, color = ink)
                    Box(Modifier.padding(top = 8.dp).height(1.5.dp).width(28.dp).graphicsLayer { scaleX = line }.background(c.text))
                }
            }
        }
        Divider(inset = 0.dp)
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
    val fill by animateColorAsState(if (selected) c.accent else c.accent.copy(alpha = 0f), Motion.quick(), label = "pillFill")
    val rim by animateColorAsState(if (selected) c.accent else c.text.copy(alpha = 0.24f), Motion.quick(), label = "pillRim")
    val ink by animateColorAsState(if (selected) c.onAccent else c.text, Motion.quick(), label = "pillInk")
    Box(
        Modifier
            .clip(Radii.pill)
            .background(fill)
            .border(Dp.Hairline, rim, Radii.pill)
            .pressable(haptic = Haptic.Selection, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, style = CType.footnote.medium(), color = ink) }
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
                if (value.isEmpty()) Text(placeholder, style = CType.body, color = c.secondary)
                BasicTextField(
                    value, onValueChange,
                    singleLine = true,
                    textStyle = CType.body.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = keyboard,
                    visualTransformation = visual,
                    interactionSource = source,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = (label ?: placeholder) },
                )
            }
            trailing?.invoke()
        }
        val line = animateColorAsState(if (focused) c.accent.copy(alpha = 0.8f) else c.text.copy(alpha = 0.18f), Motion.quick(), label = "fieldLine")
        Box(Modifier.fillMaxWidth().height(if (focused) 1.dp else Dp.Hairline).drawBehind { drawRect(line.value) })
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
        Swap(busy, contentAlignment = Alignment.Center) { b ->
            if (b) CircularProgressIndicator(Modifier.size(18.dp), color = c.text, strokeWidth = 1.5.dp)
            else Text(text, style = CType.callout.medium(), color = fg)
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
            .pressable(haptic = Haptic.Light, onClick = onClick)
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
    /** Sign out and the like: red icon and title, no chevron (it acts, it doesn't open a page). */
    destructive: Boolean = false,
    /** Set for a switch row: the whole row toggles (TalkBack reads title and state) and a Switch replaces the chevron. */
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = C.colors
    Column {
        if (!first) Divider(inset = 64.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (checked != null && onCheckedChange != null) Modifier.toggleable(checked, interactionSource = null, indication = null, role = Role.Switch, onValueChange = onCheckedChange) else Modifier)
                .then(if (onClick != null) Modifier.pressable(haptic = Haptic.Light, onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconWell(icon, tint = if (destructive) c.red else c.text)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = CType.callout.medium(), color = if (destructive) c.red else c.text)
                if (detail != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dot != null) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(detail, style = CType.footnote, color = c.secondary, maxLines = 2)
                }
            }
            if (checked != null) Switch(checked, null)
            else if (chevron && !destructive && onClick != null) Icon(AF.Forward, null, tint = c.tertiary, modifier = Modifier.size(16.dp))
        }
    }
}

/** A hairline rule with a leading inset. */
@Composable
fun Divider(inset: Dp = 32.dp, alpha: () -> Float = { 1f }) {
    // Dp.Hairline is "one pixel" only as a stroke width; as a height it lays out at 0 px and nothing draws.
    val c = C.colors
    Box(Modifier.fillMaxWidth().padding(start = inset).height(1.dp).drawBehind { drawRect(c.hairline, size = Size(size.width, 1f), alpha = alpha()) })
}

/** Caption-style status line used under titles ("Reconnecting…"). */
@Composable
fun StatusLine(text: String, color: Color, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, style = CType.caption.medium(), color = color)
    }
}
