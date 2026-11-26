package app.hermesandroid.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.hermesandroid.tasks.Segmented
import app.hermesandroid.ui.components.HeroArt
import app.hermesandroid.ui.theme.HeroMode
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.Tint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.shell.Avatar
import app.hermesandroid.ui.theme.Appearance
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.shell.decodeImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Settings > Appearance: the user's own portrait, shown in the chat, the sidebar, the lock screen and
 * the assistant instead of AllFather's art.
 *
 * The picked picture is copied into the app's own storage as a 256 px square PNG (the app never keeps
 * a content Uri — every other image path here copies bytes), so it survives the picker's temporary
 * grant, the app's restart, and a source photo that is later edited, moved or deleted.
 */
@Composable
fun AppearancePage(graph: AppGraph) {
    val c = C.colors
    val context = LocalContext.current
    val scope = graph.scope
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // The Backdrop card keeps its own busy/failed: a backdrop pick must not spin the Portrait card.
    var heroBusy by remember { mutableStateOf(false) }
    var heroFailed by remember { mutableStateOf(false) }
    val pickHero = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            heroBusy = true
            heroFailed = false
            scope.launch {
                val path = withContext(Dispatchers.IO) { storeBackdrop(context, uri) }
                if (path != null) graph.setHeroImage(path) else heroFailed = true
                heroBusy = false
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            failed = false
            scope.launch {
                val path = withContext(Dispatchers.IO) { storePortrait(context, uri) }
                if (path != null) graph.setPortrait(path) else failed = true
                busy = false
            }
        }
    }
    Card(stringResource(R.string.appearancepage_theme), AF.Grid) {
        Help(stringResource(R.string.appearancepage_theme_help))
        ThemePreview(Appearance.tint)
        // Seven names do not fit a Segmented, so each theme is a row carrying its own ramp as chips. It is a
        // card of its own and not a detail of the Backdrop: a theme paints every screen, and someone who
        // keeps the app's own art must still be able to pick one. Tapping a row retints the app under the
        // page and keeps you here, so the ramps can be flicked through and compared.
        Tint.values().forEachIndexed { i, tint ->
            CapRow(stringResource(tint.label), null, i == 0, onClick = { graph.setTint(tint.key) }) {
                TintChips(tint, tint == Appearance.tint)
            }
        }
    }

    Card(stringResource(R.string.appearancepage_portrait), AF.Image) {
        Help(stringResource(R.string.appearancepage_portrait_help))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar("default", 64.dp)
            Text(
                stringResource(if (Appearance.custom) R.string.appearancepage_using_custom else R.string.appearancepage_using_default),
                style = CType.subheadline, color = c.text,
            )
        }
        Help(stringResource(R.string.appearancepage_square_help))
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                Help(stringResource(R.string.appearancepage_working))
            }
            else -> {
                SettingsButton(
                    stringResource(if (Appearance.custom) R.string.appearancepage_replace else R.string.appearancepage_pick),
                    AF.Image, prominent = !Appearance.custom,
                ) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                if (Appearance.custom) SettingsButton(stringResource(R.string.appearancepage_reset), AF.Close) {
                    scope.launch {
                        withContext(Dispatchers.IO) { deletePortrait(context) }
                        graph.setPortrait("")
                    }
                }
            }
        }
        if (failed) Help(stringResource(R.string.appearancepage_failed))
    }

    Card(stringResource(R.string.appearancepage_backdrop), AF.Sparkles) {
        Help(stringResource(R.string.appearancepage_backdrop_help))
        // A live sample of the choice: the greeting's own hero, shown at the height of a settings row.
        Box(Modifier.fillMaxWidth().height(84.dp).clip(Radii.md).border(Dp.Hairline, c.hairline, Radii.md)) {
            HeroArt(R.drawable.af_empty, Modifier.fillMaxSize(), px = 420)
        }
        Segmented(
            listOf(
                stringResource(R.string.appearancepage_mode_art),
                stringResource(R.string.appearancepage_mode_flat),
                stringResource(R.string.appearancepage_mode_picture),
            ),
            Appearance.heroMode.ordinal,
        ) { graph.setHeroMode(MODES[it]) }
        when (Appearance.heroMode) {
            HeroMode.Art -> {}
            HeroMode.Flat -> Help(stringResource(R.string.appearancepage_flat_help))
            HeroMode.Picture -> {
                Help(stringResource(R.string.appearancepage_picture_help))
                when {
                    heroBusy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                        Help(stringResource(R.string.appearancepage_working))
                    }
                    else -> SettingsButton(
                        stringResource(if (Appearance.heroImage != null) R.string.appearancepage_replace else R.string.appearancepage_pick),
                        AF.Image, prominent = Appearance.heroImage == null,
                    ) { pickHero.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
            }
        }
        if (Appearance.heroCustom) SettingsButton(stringResource(R.string.appearancepage_backdrop_reset), AF.Close) {
            scope.launch {
                withContext(Dispatchers.IO) { deleteBackdrop(context) }
                graph.setHeroImage("")
                graph.setHeroMode("art")
            }
        }
        if (heroFailed) Help(stringResource(R.string.appearancepage_failed))
    }
}

/**
 * The picked picture, centre-cropped to a 256 px square in the app's private files, or null when it
 * cannot be read. Heavy decoding stays off the caller (CONTRIBUTING), same shape as a bot avatar.
 */
private fun storePortrait(context: Context, uri: Uri): String? = runCatching {
    val src = decodeImage(ImageDecoder.createSource(context.contentResolver, uri), 256, cover = true, software = true) ?: return@runCatching null
    val edge = min(src.width, src.height)
    val square = Bitmap.createBitmap(src, (src.width - edge) / 2, (src.height - edge) / 2, edge, edge)
    val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
    val out = File(File(context.filesDir, "appearance").apply { mkdirs() }, "portrait.png")
    out.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    out.absolutePath
}.getOrNull()

/** The stored copy goes with the setting: a reset leaves no orphan file behind. */
private fun deletePortrait(context: Context) { runCatching { File(context.filesDir, "appearance/portrait.png").delete() } }

/** The mode order and their stored names: the segmented control and [MODES] stay in step by index. */
private val MODES = listOf("art", "flat", "picture")

/**
 * A sample of the picked theme, drawn from its own tokens: the ground a screen is laid on, a card on it,
 * a raised well as the composer with its accent button, and the status dots. The ink, the accent and the
 * status colours are deliberately not faked here — a theme moves the ground only, so every colour this
 * panel shows for the sample text is exactly the colour the app will use. The panel is the app under your
 * finger, framed, so a ramp can be read before it covers the screen.
 */
@Composable
private fun ThemePreview(tint: Tint) {
    val c = C.colors
    // Hoisted: stringResource is a composable call and the semantics lambda is not one.
    val label = stringResource(R.string.appearancepage_theme_preview_of, stringResource(tint.label))
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = label }
            .clip(Radii.md).background(tint.canvas).border(Dp.Hairline, c.hairline, Radii.md).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().clip(Radii.sm).background(tint.surface).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(stringResource(R.string.appearancepage_theme_sample_user), style = CType.footnote, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.appearancepage_theme_sample_reply), style = CType.caption, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier.fillMaxWidth().clip(Radii.sm).background(tint.raised).padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.appearancepage_theme_sample_composer),
                style = CType.footnote, color = c.tertiary, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.size(24.dp).clip(Radii.pill).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(AF.Send, null, tint = c.onAccent, modifier = Modifier.size(13.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf(c.success, c.attention, c.blue).forEach { dot ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            }
            Text(stringResource(R.string.appearancepage_theme_preview), style = CType.caption2, color = c.tertiary, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

/**
 * The three tokens a theme moves, as chips at the end of its row, with the mark of the picked one. The
 * chips are decoration inside a tappable row, so the group needs a name of its own: a bare coloured Box
 * says nothing to TalkBack, and a press label only names the click action, never the control.
 */
@Composable
private fun TintChips(tint: Tint, selected: Boolean) {
    val c = C.colors
    val chips = stringResource(R.string.appearancepage_theme_chips)
    Row(
        Modifier.semantics { contentDescription = chips },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(tint.canvas, tint.surface, tint.raised).forEach { chip ->
            Box(Modifier.size(16.dp).clip(Radii.sm).background(chip).border(Dp.Hairline, c.hairline, Radii.sm))
        }
        if (selected) Icon(AF.Check, stringResource(R.string.appearancepage_theme_selected), tint = c.accent, modifier = Modifier.size(16.dp))
    }
}

/**
 * The picked backdrop, kept at screen width in the app's private files as JPEG (a hero is a photo, not
 * an icon, so PNG would be several MB for nothing). Decoded by max dimension, never cropped: the panel
 * crops with `ContentScale.Crop`, so one picture fits the login strip and the chat hero alike.
 */
private fun storeBackdrop(context: Context, uri: Uri): String? = runCatching {
    val src = decodeImage(ImageDecoder.createSource(context.contentResolver, uri), 1440, software = true) ?: return@runCatching null
    val out = File(File(context.filesDir, "appearance").apply { mkdirs() }, "backdrop.jpg")
    out.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    out.absolutePath
}.getOrNull()

/** The stored backdrop goes with the setting, like the portrait. */
private fun deleteBackdrop(context: Context) { runCatching { File(context.filesDir, "appearance/backdrop.jpg").delete() } }
