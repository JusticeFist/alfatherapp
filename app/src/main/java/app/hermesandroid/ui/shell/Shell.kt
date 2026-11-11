package app.hermesandroid.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermesandroid.R
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.frosted
import app.hermesandroid.ui.theme.glass
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.well
import kotlin.math.sin

// Shell pieces: top bar with the profile strip, frosted floating tab capsule, voice button (AllFather look).

/** Top bar: leading content (profile strip or a title) + a glass capsule of icons. */
@Composable
fun TopBar(leading: @Composable RowScope.() -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = leading)
        if (trailing != null) {
            Row(Modifier.glass(RoundedCornerShape(22.dp)).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** A page's top bar (pages open over the home chat from the sidebar): back, and optional actions on the right. */
@Composable
fun PageBar(onBack: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    TopBar(
        leading = {
            val back = stringResource(R.string.shell_back)
            Box(Modifier.size(44.dp).pressable(label = back, haptic = Haptic.Light, onClick = onBack), contentAlignment = Alignment.CenterStart) {
                Icon(AF.Back, back, tint = C.colors.text, modifier = Modifier.size(24.dp))
            }
        },
        trailing = trailing,
    )
}

/** CapsuleIcon: 16 dp glyph in a 36 frame (44 hit area), optional 8 dp dot; [selected] sits in a raised well. */
@Composable
fun CapsuleIcon(icon: ImageVector, label: String, tint: Color = C.colors.text, dot: Color? = null, selected: Boolean = false, onClick: () -> Unit) {
    Box((if (selected) Modifier.well(Radii.pill) else Modifier).size(width = 40.dp, height = 44.dp).pressable(label = label, haptic = Haptic.Light, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(19.dp))
        if (dot != null) Box(Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 9.dp).size(8.dp).clip(CircleShape).background(dot))
    }
}

/**
 * A screen slid in over the chat (a page, a bot room, the call) that follows Android's predictive back gesture: it
 * shrinks, rounds its corners and leans with the finger, springs back if the gesture is let go, and [onBack] runs
 * when it commits (the caller's exit animation takes it from there). Its state leaves with it.
 */
@Composable
fun PredictiveBackLayer(enabled: Boolean, onBack: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { progress.snapTo(it.progress) }
            onBack()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(0f) }
            throw e
        }
    }
    // It takes every touch (a tap on a gap, or just outside a small button, otherwise lands on what's below), and only
    // where it is: while it slides away the uncovered chat already answers.
    Box(Modifier.fillMaxSize().graphicsLayer {
        val p = progress.value
        scaleX = 1f - 0.1f * p; scaleY = scaleX
        translationX = p * 32.dp.toPx()
        shape = ContinuousRoundedRectangle((28 * p).dp); clip = p > 0f // grows to Radii.xl
    }.pointerInput(Unit) {}, content = content)
}

/** The default profile's name, from its SOUL.md heading (AppGraph reads it on connect); "AllFather" until known. */
var defaultProfileTitle by androidx.compose.runtime.mutableStateOf("AllFather")

fun profileTitle(name: String) = if (name == "default") defaultProfileTitle else name.replaceFirstChar { it.uppercase() }

/** The agent's name from SOUL.md: its first `# Heading`, else the "Eres X," opening. */
fun soulName(soul: String): String? {
    soul.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.takeIf { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.takeIf { it.length in 1..40 }?.let { return it }
    return Regex("""(?:Eres|You are|Your name is|Te llamas)\s+([A-ZÁÉÍÓÚÑ][\wÁÉÍÓÚÑáéíóúñ-]{1,30})""").find(soul)?.groupValues?.get(1)
}

/**
 * A profile's avatar: AllFather's portrait for the default profile, else the profile photo, else its initials
 * in a graphite well. Hairline rim.
 */
@Composable
fun Avatar(name: String, size: Dp = 30.dp, alpha: Float = 1f, color: Color = C.colors.accent, image: ByteArray? = null) {
    val c = C.colors
    val rim = Modifier.size(size).clip(CircleShape).border(Dp.Hairline, c.composerStroke, CircleShape)
    if (name == "default") {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(app.hermesandroid.R.drawable.af_avatar), name,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = rim, alpha = alpha,
        )
        return
    }
    // Decoded once at the size it shows and kept: pages rebuild on every open, and rows (bots, rooms, room events)
    // used to decode the full picture each time, on the main thread.
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap = androidx.compose.runtime.remember(image, px) {
        image?.let { bytes ->
            val key = "avatar:${bytes.contentHashCode()}:$px"
            imageCache.get(key) ?: decodeImage(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)), px, cover = true)?.asImageBitmap()?.also { imageCache.put(key, it) }
        }
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap, name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = rim, alpha = alpha)
        return
    }
    val initials = profileTitle(name).split(' ', '-', '_').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    Box(rim.background(c.raised), contentAlignment = Alignment.Center) {
        Text(initials, color = color.copy(alpha = 0.85f * alpha), fontWeight = FontWeight.Light, fontSize = (size.value * 0.36f).sp, textAlign = TextAlign.Center)
    }
}

/** Voice button: 56 dp off-white circle with five graphite bars; they ripple while `live`. */
@Composable
fun VoiceButton(onClick: () -> Unit, live: Boolean = false, size: Dp = 56.dp) {
    val c = C.colors
    val rest = floatArrayOf(0.34f, 0.62f, 0.9f, 0.62f, 0.34f)
    val t = if (live) {
        rememberInfiniteTransition(label = "bars").animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1047, easing = LinearEasing)), label = "t").value
    } else 0f
    Box(
        Modifier.size(size).clip(CircleShape).background(c.accent).pressable(label = stringResource(R.string.shell_voice), haptic = Haptic.Medium, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val bar = size / 18.7f
        Row(Modifier.size(width = bar * 10, height = size * 0.4f), horizontalArrangement = Arrangement.spacedBy(bar), verticalAlignment = Alignment.CenterVertically) {
            rest.forEachIndexed { i, r ->
                val h = if (live) (r + 0.3f * sin(t + 1.1f * i)).coerceIn(0.22f, 1f) else r
                Box(Modifier.width(bar).fillMaxHeight(h).clip(CircleShape).background(c.onAccent))
            }
        }
    }
}

/**
 * Decodes an image downsampled to about [side] px, EXIF rotation applied. Not on the main thread.
 * `cover`: the shorter side stays ≥ side (to crop a square from); else the longer side ≤ side (to show).
 * `software`: a bitmap that can be cropped, scaled or read pixel by pixel (hardware ones can't).
 */
fun decodeImage(source: android.graphics.ImageDecoder.Source, side: Int, cover: Boolean = false, software: Boolean = false): android.graphics.Bitmap? = runCatching {
    android.graphics.ImageDecoder.decodeBitmap(source) { d, info, _ ->
        val (w, h) = info.size.width to info.size.height
        d.setTargetSampleSize(if (cover) maxOf(1, minOf(w, h) / side) else maxOf(1, (maxOf(w, h) + side - 1) / side))
        if (software) d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

fun decodeImage(bytes: ByteArray, side: Int) = decodeImage(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)), side)

/** Host images already decoded (chat MEDIA blocks, artifact thumbnails), so scrolling back doesn't download them again. */
val imageCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(64 * 1024) {
    override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap) = value.width * value.height / 256 // ≈ KB at 4 bytes/px
}
