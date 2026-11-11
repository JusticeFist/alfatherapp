package app.hermesandroid.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.derivedStateOf
import app.hermesandroid.ui.theme.well
import app.hermesandroid.ui.theme.Radii
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import app.hermesandroid.ui.shell.VoiceButton
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.chatTextStyle
import app.hermesandroid.ui.theme.glass
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.semibold
import java.io.File
import app.hermesandroid.ui.icons.AF
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R
import app.hermesandroid.tr

// ComposerBar, as in Claude's app: one card that stays put, the field over a bar (attach, model, context, agents,
// dictate, and the main button: voice, send or stop). docs/design-allfather.md.

@Composable
fun Composer(s: ChatState, profile: String, onModel: () -> Unit, onContext: () -> Unit, onAgents: () -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    var fullEditor by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { s.addAttachments(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { s.addAttachments(it) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { s.addAttachments(listOf(it)) } }

    Column {
        val slashQuery = s.draft.takeIf { it.startsWith("/") && ' ' !in it }?.drop(1)?.lowercase()
        if (slashQuery != null) SlashPopover(s.slashCommands.filter { it.name.startsWith(slashQuery) }) { s.draft = "/${it.name} " }
        ComposerCard {
            if (s.attachments.isNotEmpty() || s.council) AttachmentStrip(s)
            Row(verticalAlignment = Alignment.Top) {
                ComposerField(s.draft, { s.draft = it }, stringResource(R.string.composer_message_to, profileTitle(profile)), Modifier.weight(1f))
                if (s.draft.lines().size > 3) Box(Modifier.padding(top = 4.dp).size(40.dp).pressable(label = stringResource(R.string.composer_full_editor)) { fullEditor = true }, contentAlignment = Alignment.Center) {
                    Icon(AF.OpenInFull, null, tint = c.secondary, modifier = Modifier.size(14.dp))
                }
            }
            ComposerBar {
                Box {
                    ToolCircle(AF.Add, stringResource(R.string.composer_attach)) { attachMenu = true }
                    AttachMenu(attachMenu, { attachMenu = false }, pickMedia, pickFiles, onCouncil = { s.council = true }.takeIf { s.graph.profiles.size > 1 }) { cameraUri = newCameraUri(ctx); takePicture.launch(cameraUri!!) }
                }
                Box(Modifier.weight(1f)) { ModelPill(s, onModel) }
                val working by remember(s) { derivedStateOf { s.subagents.count { it.status == "running" } } }
                if (working > 0) Row(Modifier.pressable(label = stringResource(R.string.composer_agents), onClick = onAgents), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AF.Group, null, tint = c.accent, modifier = Modifier.size(15.dp))
                    Text("$working", style = CType.data.semibold(), color = c.accent)
                }
                // The ring speaks up once the context is filling; the chat's menu always has the details.
                s.contextPercent?.takeIf { it >= 50 }?.let { ContextRing(it, onContext) }
                DictateButton(s)
                MainButton(s)
            }
        }
    }
    if (fullEditor) FullEditor(s, profile) { fullEditor = false }
}

/** The bar's height: its round tools, the model pill and the main button. */
private val BarItem = 36.dp

/** The composer's card (the chat and bot rooms): the field over a bar of tools, as in Claude's app. */
@Composable
internal fun ComposerCard(content: @Composable ColumnScope.() -> Unit) {
    val c = C.colors
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp).fillMaxWidth()
            .background(c.composer, Radii.xl).border(Dp.Hairline, c.composerStroke, Radii.xl),
        content = content,
    )
}

/** The card's bottom bar: tools on the left, the main button on the right. */
@Composable
internal fun ComposerBar(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** The model as Claude's app names it ("Opus 5.5" and the effort), in a raised pill; a tap changes it. */
@Composable
private fun ModelPill(s: ChatState, onClick: () -> Unit) {
    val c = C.colors
    Row(
        Modifier.heightIn(min = BarItem).well(Radii.pill).pressable(label = stringResource(R.string.composer_model), onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (s.yolo) Icon(AF.RemoveModerator, "YOLO", tint = c.orange, modifier = Modifier.size(13.dp))
        Text(modelLabel(s.model), style = CType.subheadline.medium(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        s.reasoning?.let { Text(reasoningLabel(it), style = CType.subheadline, color = c.secondary, maxLines = 1) }
    }
}

private val CLAUDE_MODEL = Regex("""^(?:.*/)?claude-([a-z]+)-(\d+)(?:[-.](\d{1,2}))?(?:-\d+)?$""")

/** "claude-opus-5-5" → "Opus 5.5" ("-5.1", "-5" and date suffixes too), as Claude's app names it; other ids only lose a "provider/" prefix. */
internal fun modelLabel(id: String?): String {
    if (id.isNullOrBlank()) return tr(R.string.composer_model)
    CLAUDE_MODEL.find(id)?.destructured?.let { (family, major, minor) ->
        return "${family.replaceFirstChar { it.uppercase() }} $major" + if (minor.isEmpty()) "" else ".$minor"
    }
    return id.substringAfterLast('/')
}

/** Composer mic: dictates into the draft (Settings > Voice picks the phone's recognizer or Hermes' STT). */
@Composable
private fun DictateButton(s: ChatState) {
    val c = C.colors
    val ctx = LocalContext.current
    val d = s.graph.dictation
    fun go() = d.toggle(s.draft) { s.draft = it }
    val micNeeded = stringResource(R.string.composer_mic_needed)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) go() else d.error = micNeeded }
    androidx.compose.runtime.LaunchedEffect(d.error) { d.error?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show(); d.error = null } }
    val active = d.listening || d.transcribing
    Box(contentAlignment = Alignment.Center) {
        if (d.listening) Box(Modifier.size((38 + 10 * d.level).dp).clip(CircleShape).background(c.accent.copy(alpha = 0.18f)))
        if (d.transcribing) androidx.compose.material3.CircularProgressIndicator(Modifier.size(38.dp), color = c.accent, strokeWidth = 2.dp)
        ToolCircle(if (d.listening) AF.Stop else AF.Mic, stringResource(if (d.listening) R.string.composer_stop_dictation else R.string.composer_dictate), tint = if (active) c.accent else c.text) {
            if (d.transcribing) return@ToolCircle
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) go()
            else permission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
}

private fun newCameraUri(ctx: android.content.Context): Uri {
    val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
    val file = File(dir, "photo-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
}

@Composable
private fun AttachMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    pickMedia: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, List<Uri>>,
    pickFiles: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    onCouncil: (() -> Unit)?,
    onCamera: () -> Unit,
) {
    DropdownMenu(open, onDismiss) {
        onCouncil?.let { DropdownMenuItem({ Text(stringResource(R.string.composer_ask_helpers)) }, { onDismiss(); it() }, leadingIcon = { Icon(AF.Group, null) }) }
        DropdownMenuItem({ Text(stringResource(R.string.composer_camera)) }, { onDismiss(); onCamera() }, leadingIcon = { Icon(AF.PhotoCamera, null) })
        DropdownMenuItem({ Text(stringResource(R.string.composer_photos)) }, { onDismiss(); pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, leadingIcon = { Icon(AF.Image, null) })
        DropdownMenuItem({ Text(stringResource(R.string.composer_document)) }, { onDismiss(); pickFiles.launch(arrayOf("*/*")) }, leadingIcon = { Icon(AF.Description, null) })
    }
}

@Composable
private fun AttachmentStrip(s: ChatState) {
    val c = C.colors
    @Composable
    fun chip(icon: ImageVector, label: String, onRemove: () -> Unit) = Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(c.text.copy(alpha = 0.06f)).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(15.dp))
        Text(label, style = CType.caption, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Box(Modifier.size(24.dp).pressable(label = stringResource(R.string.composer_remove)) { onRemove() }, contentAlignment = Alignment.Center) {
            Icon(AF.Close, null, tint = c.secondary, modifier = Modifier.size(13.dp))
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.council) chip(AF.Group, stringResource(R.string.composer_with_helpers)) { s.council = false }
        s.attachments.forEach { a -> chip(if (a.isImage) AF.Image else AF.Description, a.name) { s.attachments.remove(a) } }
    }
}

@Composable
private fun SlashPopover(commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    val c = C.colors
    if (commands.isEmpty()) return
    LazyColumn(Modifier.padding(horizontal = 14.dp).fillMaxWidth().heightIn(max = 280.dp).glass(RoundedCornerShape(20.dp))) {
        items(commands.take(40), key = { it.name }) { cmd ->
            Row(Modifier.fillMaxWidth().pressable(haptic = Haptic.Selection) { onPick(cmd) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("/${cmd.name}", style = CType.subheadline.semibold().copy(fontFamily = CType.mono), color = c.text, modifier = Modifier.widthIn(min = 90.dp))
                Text(cmd.description, style = CType.footnote, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                cmd.category?.let { Text(it, style = CType.caption2, color = c.accent, modifier = Modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1) }
            }
        }
    }
}

@Composable
internal fun ComposerField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = C.colors
    Box(modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), contentAlignment = Alignment.TopStart) {
        if (value.isEmpty()) Text(placeholder, style = chatTextStyle(), color = c.secondary)
        BasicTextField(
            value, onValue,
            textStyle = chatTextStyle().copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FullEditor(s: ChatState, profile: String, onClose: () -> Unit) {
    val c = C.colors
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.canvas).statusBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.composer_close), onClick = onClose), contentAlignment = Alignment.Center) { Icon(AF.CloseFullscreen, null, tint = c.accent) }
                Text(stringResource(R.string.composer_message_to_title, profileTitle(profile)), style = CType.headline, color = c.text, modifier = Modifier.weight(1f))
                ToolCircle(AF.ArrowUpward, stringResource(R.string.composer_send), main = true, enabled = s.draft.isNotBlank()) { onClose(); s.send() }
            }
            BasicTextField(s.draft, { s.draft = it }, textStyle = chatTextStyle().copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxSize().padding(18.dp))
        }
    }
}

/** A round tool in the composer's bar: a raised well, or off-white when it's the main action. */
@Composable
internal fun ToolCircle(
    icon: ImageVector, label: String, main: Boolean = false, enabled: Boolean = true, tint: Color? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val c = C.colors
    Box(
        Modifier.size(BarItem).clip(CircleShape).background(if (main) c.accent.copy(alpha = if (enabled) 1f else 0.3f) else c.raised)
            .pressable(enabled = enabled, label = label, haptic = Haptic.Light, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint ?: if (main) c.onAccent else c.text, modifier = Modifier.size(17.dp)) }
}

/**
 * The bar's main button, as in Claude's app: voice while there's nothing to send, send once there is (the keyboard
 * goes down so the answer can be read), stop while a turn runs, and the busy action when typing during one (a long
 * press offers the other ways to send).
 */
@Composable
private fun MainButton(s: ChatState) {
    val focus = LocalFocusManager.current
    var menu by remember { mutableStateOf(false) }
    val hasText = s.draft.isNotBlank() || s.attachments.isNotEmpty()
    val blocked = s.runtimeId == null && s.pendingSend != null
    if (!s.running && !hasText && !blocked) {
        VoiceButton(onClick = app.hermesandroid.voice.rememberVoiceAction(s.graph), live = s.graph.liveCall.active, size = BarItem)
        return
    }
    val stopping = s.running && !hasText
    data class A(val icon: ImageVector, val label: String, val run: () -> Unit)
    val a = when {
        stopping -> A(AF.Stop, stringResource(R.string.composer_stop)) { s.stop() }
        s.running -> when (s.busyAction) {
            BusyAction.Queue -> A(AF.Queue, stringResource(R.string.composer_queue)) { s.send(BusyAction.Queue) }
            BusyAction.Steer -> A(AF.Navigation, stringResource(R.string.composer_steer)) { s.send(BusyAction.Steer) }
            BusyAction.Interrupt -> A(AF.BackHand, stringResource(R.string.composer_interrupt)) { s.send(BusyAction.Interrupt) }
        }
        blocked -> A(AF.Lock, stringResource(R.string.composer_unavailable)) {}
        else -> A(AF.ArrowUpward, stringResource(R.string.composer_send)) { focus.clearFocus(); s.send() }
    }
    Box {
        ToolCircle(a.icon, a.label, main = !stopping, enabled = !blocked, onLongClick = if (s.running && hasText) ({ menu = true }) else null, onClick = a.run)
        DropdownMenu(menu, { menu = false }) {
            BusyAction.entries.forEach { b -> DropdownMenuItem({ Text(stringResource(b.label)) }, { menu = false; s.send(b) }) }
        }
    }
}

/** Context ring: a thin track with the used share from 12 o'clock; tap opens the context sheet. */
@Composable
private fun ContextRing(percent: Int, onClick: () -> Unit) {
    val c = C.colors
    Box(Modifier.size(BarItem).pressable(label = stringResource(R.string.composer_context, percent), onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val st = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
            val inset = st.width / 2
            val arcSize = Size(size.width - st.width, size.height - st.width)
            drawArc(c.secondary.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = st)
            drawArc(if (percent >= 85) c.orange else c.text, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arcSize, style = st)
        }
    }
}
