package app.hermesandroid.bots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.chat.BubbleShape
import app.hermesandroid.chat.ComposerBar
import app.hermesandroid.chat.ComposerCard
import app.hermesandroid.chat.ComposerField
import app.hermesandroid.chat.AfMarkdown
import app.hermesandroid.chat.ToolCircle
import app.hermesandroid.ui.components.AfField
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.components.ago
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.shell.CapsuleIcon
import app.hermesandroid.ui.shell.PageBar
import app.hermesandroid.ui.theme.Backdrop
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.chatTextStyle
import app.hermesandroid.ui.theme.glass
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import kotlinx.coroutines.delay

// Bots tab, editor (BotEditorSheet) and group rooms (GroupChatView).

/** Bot avatar: AllFather's portrait for the orchestrator, else the bot's photo, else its initial in a graphite well. */
@Composable
fun BotAvatar(bot: Bot, image: ByteArray?, size: Dp = 36.dp) =
    app.hermesandroid.ui.shell.Avatar(if (bot.isDefault) "default" else bot.name, size = size, image = image)

private sealed interface BotsDialog {
    data class Edit(val bot: Bot?) : BotsDialog
    data class View(val bot: Bot) : BotsDialog
    data class Delete(val bot: Bot) : BotsDialog
    data object NewRoom : BotsDialog
}

@Composable
fun BotsScreen(graph: AppGraph, bottomInset: Dp, onBack: () -> Unit) {
    val s = graph.bots
    val c = C.colors
    var dialog by remember { mutableStateOf<BotsDialog?>(null) }
    // The roster comes over the WebSocket: reload whenever it (re)connects, or a dropped load keeps its error.
    val connected by graph.client.connected.collectAsStateWithLifecycle()
    LaunchedEffect(connected) { if (connected) s.load() }
    Column(Modifier.fillMaxSize()) {
        PageBar(onBack, trailing = { CapsuleIcon(AF.Add, stringResource(R.string.botsscreen_new_bot)) { dialog = BotsDialog.Edit(null) } })
        app.hermesandroid.ui.components.ScreenHeader(stringResource(R.string.botsscreen_profiles), stringResource(R.string.botsscreen_title), "%02d".format(s.bots.size))
        s.notice?.let { msg ->
            LaunchedEffect(msg) { delay(6000); s.notice = null }
            Text(msg, style = CType.footnote, color = c.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), tint = c.yellow.copy(alpha = 0.1f)).padding(14.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
            s.error?.let { item { Text(it, style = CType.footnote, color = c.red, modifier = Modifier.padding(16.dp)) } }
            val main = s.bots.filter { it.isDefault }
            val others = s.bots.filter { !it.isDefault }
            if (main.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.botsscreen_main_section)) }
                items(main, key = { it.name }) { bot -> BotRow(graph, bot, onEdit = { dialog = BotsDialog.View(bot) }, onDelete = {}) }
            }
            item { SectionLabel(stringResource(R.string.botsscreen_your_bots)) }
            items(others, key = { it.name }) { bot -> BotRow(graph, bot, onEdit = { dialog = BotsDialog.Edit(bot) }, onDelete = { dialog = BotsDialog.Delete(bot) }) }
            if (others.isEmpty()) item { Text(stringResource(R.string.botsscreen_bots_help, app.hermesandroid.ui.shell.defaultProfileTitle), style = CType.footnote, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            item { CreateRow(stringResource(R.string.botsscreen_new_bot)) { dialog = BotsDialog.Edit(null) } }
            item { SectionLabel(stringResource(R.string.botsscreen_group_chats)) }
            if (!s.groupsAvailable) item { Text(stringResource(R.string.botsscreen_no_groups), style = CType.footnote, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp)) }
            else {
                items(s.rooms, key = { "room-" + it.id }) { r -> RoomRow(graph, r) { s.openRoom(r) } }
                item { CreateRow(stringResource(R.string.botsscreen_new_room)) { dialog = BotsDialog.NewRoom } }
            }
        }
    }
    when (val d = dialog) {
        is BotsDialog.Edit -> BotEditor(graph, d.bot) { dialog = null }
        is BotsDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.botsscreen_delete_title, d.bot.title)) },
            text = { Text(stringResource(R.string.botsscreen_delete_text)) },
            confirmButton = { TextButton({ dialog = null; s.delete(d.bot) }) { Text(stringResource(R.string.botsscreen_delete), color = c.red) } },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.botsscreen_cancel)) } },
        )
        BotsDialog.NewRoom -> NewRoomDialog(graph) { dialog = null }
        is BotsDialog.View -> MainBotSheet(graph, d.bot) { dialog = null }
        null -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotRow(graph: AppGraph, bot: Bot, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = C.colors
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { Haptic.Light.perform(view); graph.chat.openBotChat(bot.name, bot.title) }, onLongClick = { Haptic.Medium.perform(view); menu = true })
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotAvatar(bot, graph.avatars[bot.name])
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(bot.title, style = CType.callout.medium(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (bot.isDefault) Text(stringResource(R.string.botsscreen_orchestrator_badge), style = CType.eyebrow.copy(fontSize = CType.caption2.fontSize), color = c.secondary,
                        modifier = Modifier.border(androidx.compose.ui.unit.Dp.Hairline, c.text.copy(alpha = 0.24f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Text((bot.lastPreview ?: bot.description).ifBlank { "Bot Chat" }, style = CType.subheadline, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (bot.pinned) Icon(AF.PushPin, null, tint = c.text.copy(alpha = 0.35f), modifier = Modifier.size(13.dp).padding(end = 2.dp))
            bot.lastActive?.let { Text(ago(it), style = CType.data, color = c.tertiary) }
            DropdownMenu(menu, { menu = false }) {
                if (bot.isDefault) {
                    DropdownMenuItem({ Text(stringResource(R.string.botsscreen_view_soul)) }, { menu = false; onEdit() }, leadingIcon = { Icon(AF.Edit, null) })
                } else {
                    DropdownMenuItem({ Text(if (bot.pinned) stringResource(R.string.botsscreen_unpin) else stringResource(R.string.botsscreen_pin)) }, { menu = false; graph.bots.togglePin(bot) }, leadingIcon = { Icon(AF.PushPin, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.botsscreen_edit_menu)) }, { menu = false; onEdit() }, leadingIcon = { Icon(AF.Edit, null) })
                }
                if (!bot.isDefault) DropdownMenuItem({ Text(stringResource(R.string.botsscreen_delete_menu), color = c.red) }, { menu = false; onDelete() }, leadingIcon = { Icon(AF.Delete, null, tint = c.red) })
            }
        }
        Divider(inset = 64.dp)
    }
}

@Composable
private fun SectionLabel(text: String) {
    app.hermesandroid.ui.components.Eyebrow(text, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
}

/** Hermes (default profile) is read-only here: its personality and settings are changed from Hermes itself. */
@Composable
private fun MainBotSheet(graph: AppGraph, bot: Bot, onDismiss: () -> Unit) {
    val c = C.colors
    var soul by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }
    LaunchedEffect(bot.name) { runCatching { graph.bots.describe(bot.name) }.getOrNull()?.let { (d, p) -> description = d; soul = p } }
    app.hermesandroid.chat.AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotAvatar(bot, graph.avatars[bot.name], 44.dp)
                Column {
                    Text(bot.title, style = CType.title3.semibold(), color = c.text)
                    Text(stringResource(R.string.botsscreen_main_bot), style = CType.footnote, color = c.accent)
                }
            }
            Text(stringResource(R.string.botsscreen_main_bot_help, bot.title), style = CType.footnote, color = c.secondary)
            if (description.isNotBlank()) Text(description, style = CType.subheadline, color = c.text)
            Text(stringResource(R.string.botsscreen_soul), style = CType.footnote.semibold(), color = c.secondary)
            Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(18.dp)).padding(14.dp)) {
                soul?.let { AfMarkdown(it, 0.9f) } ?: Text(stringResource(R.string.botsscreen_loading), style = CType.footnote, color = c.secondary)
            }
        }
    }
}

/** A room's picture, else the group glyph. */
@Composable
private fun RoomPicture(image: ByteArray?, size: Dp) {
    val c = C.colors
    if (image != null) { app.hermesandroid.ui.shell.Avatar("room", size = size, image = image); return }
    Box(Modifier.size(size).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(AF.Groups, null, tint = c.accent, modifier = Modifier.size(size / 2)) }
}

@Composable
private fun RoomRow(graph: AppGraph, r: Room, onClick: () -> Unit) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().pressable(haptic = Haptic.Light, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        RoomPicture(graph.bots.roomPictures[r.id], 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = CType.callout.semibold(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.members.joinToString(", ") { "@" + it.handle }, style = CType.subheadline, color = c.text.copy(alpha = 0.58f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CreateRow(title: String, onClick: () -> Unit) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(AF.Add, null, tint = c.accent, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, style = CType.callout.semibold(), color = c.accent)
    }
}

/** BotEditorSheet: name, what it's for, personality (SOUL.md), colour, photo. */
@Composable
private fun BotEditor(graph: AppGraph, bot: Bot?, onDismiss: () -> Unit) {
    val s = graph.bots
    val c = C.colors
    var title by remember { mutableStateOf(bot?.title.orEmpty()) }
    var description by remember { mutableStateOf(bot?.description.orEmpty()) }
    var soul by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(bot?.color) }
    var photo by remember { mutableStateOf<android.net.Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photo = it }
    LaunchedEffect(bot?.name) { bot?.let { runCatching { s.describe(it.name) }.getOrNull()?.let { (d, p) -> description = d; soul = p } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bot == null) stringResource(R.string.botsscreen_new_bot) else stringResource(R.string.botsscreen_edit_title, bot.title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AfField(title, { title = it }, stringResource(R.string.botsscreen_name), stringResource(R.string.botsscreen_name_hint))
                if (bot == null && title.isNotBlank()) Text(stringResource(R.string.botsscreen_profile_slug, BotsState.slug(title)), style = CType.caption, color = c.secondary)
                AfField(description, { description = it }, stringResource(R.string.botsscreen_purpose), stringResource(R.string.botsscreen_purpose_hint))
                Text(stringResource(R.string.botsscreen_soul), style = CType.footnote.semibold(), color = c.secondary)
                Box(Modifier.fillMaxWidth().heightIn(min = 100.dp).clip(RoundedCornerShape(17.dp)).background(c.text.copy(alpha = 0.06f)).padding(14.dp)) {
                    if (soul.isEmpty()) Text(stringResource(R.string.botsscreen_soul_hint), style = CType.subheadline, color = c.tertiary)
                    BasicTextField(soul, { soul = it }, textStyle = CType.subheadline.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.botsscreen_color), style = CType.footnote.semibold(), color = c.secondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotsState.SWATCHES.forEach { hex ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(hex)))
                            .then(if (color == hex) Modifier.border(2.dp, c.text, CircleShape) else Modifier).pressable { color = hex })
                    }
                }
                SettingsButton(if (photo != null) stringResource(R.string.botsscreen_photo_picked) else stringResource(R.string.botsscreen_pick_photo), AF.Image) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
        },
        confirmButton = {
            TextButton({
                if (bot == null) s.create(title, description, soul, color, photo, onDismiss) else s.update(bot, title, description, soul, color, photo, onDismiss)
            }, enabled = title.isNotBlank()) { Text(if (bot == null) stringResource(R.string.botsscreen_create) else stringResource(R.string.botsscreen_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.botsscreen_cancel)) } },
    )
}

/** Rooms hold 2–6 bots. */
@Composable
private fun NewRoomDialog(graph: AppGraph, onDismiss: () -> Unit) {
    val s = graph.bots
    val c = C.colors
    var name by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.botsscreen_new_room)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AfField(name, { name = it }, stringResource(R.string.botsscreen_name), stringResource(R.string.botsscreen_room_name_hint))
                Text(stringResource(R.string.botsscreen_members), style = CType.footnote.semibold(), color = c.secondary)
                s.bots.forEach { b ->
                    val sel = b.name in picked
                    Row(Modifier.fillMaxWidth().pressable { if (sel) picked.remove(b.name) else if (picked.size < 6) picked.add(b.name) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (sel) AF.CheckCircle else AF.RadioButtonUnchecked, null, tint = if (sel) c.accent else c.tertiary)
                        Spacer(Modifier.width(10.dp))
                        BotAvatar(b, graph.avatars[b.name], 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(b.title, style = CType.body, color = c.text)
                    }
                }
                if (s.bots.size < 2) Text(stringResource(R.string.botsscreen_need_two), style = CType.caption, color = c.secondary)
            }
        },
        confirmButton = { TextButton({ s.createRoom(name, s.bots.filter { it.name in picked }, onDismiss) }, enabled = name.isNotBlank() && picked.size in 2..6) { Text(stringResource(R.string.botsscreen_create)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.botsscreen_cancel)) } },
    )
}

// ---- room screen ----

@Composable
fun RoomScreen(graph: AppGraph, r: Room) {
    val s = graph.bots
    val c = C.colors
    var text by remember { mutableStateOf("") }
    var confirmDisband by remember { mutableStateOf(false) }
    var pictureMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { s.setRoomPicture(r, it) } }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { s.addRoomFiles(it) }
    val focus = LocalFocusManager.current
    LaunchedEffect(s.events.size) { if (s.events.isNotEmpty()) list.animateScrollToItem(s.events.size - 1) }
    val visible = s.events.filter { it.text != null || it.kind.startsWith("turn.") }
    Box(Modifier.fillMaxSize()) {
        Backdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.botsscreen_back)) { s.closeRoom() }, contentAlignment = Alignment.Center) { Icon(AF.ArrowBack, null, tint = c.accent) }
                Box {
                    Box(Modifier.padding(end = 10.dp).clip(CircleShape).pressable(label = stringResource(R.string.botsscreen_room_picture)) { pictureMenu = true }) { RoomPicture(s.roomPictures[r.id], 34.dp) }
                    androidx.compose.material3.DropdownMenu(pictureMenu, { pictureMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.botsscreen_change_picture)) }, { pictureMenu = false; pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, leadingIcon = { Icon(AF.Image, null) })
                        if (s.roomPictures[r.id] != null) androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.botsscreen_remove_picture)) }, { pictureMenu = false; s.removeRoomPicture(r) }, leadingIcon = { Icon(AF.Delete, null) })
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = CType.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (s.roomWorking) stringResource(R.string.botsscreen_working) else r.members.joinToString(" ") { "@" + it.handle }, style = CType.caption, color = if (s.roomWorking) c.accent else c.secondary, maxLines = 1)
                }
                if (s.roomWorking) Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.botsscreen_stop)) { s.stop() }, contentAlignment = Alignment.Center) { Icon(AF.Stop, null, tint = c.red) }
                Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.botsscreen_disband)) { confirmDisband = true }, contentAlignment = Alignment.Center) { Icon(AF.Delete, null, tint = c.secondary) }
            }
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (visible.isEmpty()) item { Text(stringResource(R.string.botsscreen_room_empty), style = CType.subheadline, color = c.secondary) }
                items(visible, key = { it.seq }) { e -> RoomEventRow(graph, r, e) }
            }
            s.approvals.forEach { a ->
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().settingsCard(RoundedCornerShape(22.dp), tint = c.attention.copy(alpha = 0.08f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(AF.VerifiedUser, null, tint = c.attention, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.botsscreen_asks_permission, r.members.firstOrNull { it.id == a.memberId }?.handle ?: a.memberId), style = CType.subheadline.semibold(), color = c.text)
                    }
                    if (a.description.isNotBlank()) Text(a.description, style = CType.footnote, color = c.text)
                    if (a.command.isNotBlank()) Text(a.command, style = CType.data, color = c.text, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.text.copy(alpha = 0.06f)).padding(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.choices.forEach { ch -> SettingsButton(when (ch) { "once" -> stringResource(R.string.botsscreen_approve_once); "session" -> stringResource(R.string.botsscreen_approve_session); "always" -> stringResource(R.string.botsscreen_approve_always); "deny" -> stringResource(R.string.botsscreen_approve_deny); else -> ch }, prominent = ch == "once") { s.approve(a, ch) } }
                    }
                }
            }
            // @mention chips (BotMentions): tap to add a member's handle.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.members.forEach { m -> Text("@" + m.handle, style = CType.caption.semibold(), color = c.accent, modifier = Modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.12f)).pressable { text = (text.trimEnd() + " @" + m.handle + " ").trimStart() }.padding(horizontal = 10.dp, vertical = 5.dp)) }
            }
            if (s.roomFiles.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.roomFiles.forEach { f ->
                    Text("${f.name}  ✕", style = CType.caption, color = c.text, maxLines = 1,
                        modifier = Modifier.clip(CircleShape).background(c.text.copy(alpha = 0.08f)).pressable(label = stringResource(R.string.botsscreen_remove_file, f.name)) { s.roomFiles.remove(f) }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            // The chat's composer card: the field over a bar with attach and send (the keyboard goes down on send).
            ComposerCard {
                ComposerField(text, { text = it }, stringResource(R.string.botsscreen_message_hint, r.name))
                ComposerBar {
                    ToolCircle(AF.Add, stringResource(R.string.botsscreen_attach)) { pickFiles.launch("*/*") }
                    Spacer(Modifier.weight(1f))
                    val canSend = text.isNotBlank() || s.roomFiles.isNotEmpty()
                    ToolCircle(AF.ArrowUpward, stringResource(R.string.botsscreen_send), main = true, enabled = canSend) { focus.clearFocus(); s.send(text); text = "" }
                }
            }
        }
    }
    if (confirmDisband) AlertDialog(
        onDismissRequest = { confirmDisband = false },
        title = { Text(stringResource(R.string.botsscreen_disband_title, r.name)) },
        text = { Text(stringResource(R.string.botsscreen_disband_text)) },
        confirmButton = { TextButton({ confirmDisband = false; s.disband() }) { Text(stringResource(R.string.botsscreen_disband), color = c.red) } },
        dismissButton = { TextButton({ confirmDisband = false }) { Text(stringResource(R.string.botsscreen_cancel)) } },
    )
}

@Composable
private fun RoomEventRow(graph: AppGraph, r: Room, e: RoomEvent) {
    val c = C.colors
    when {
        e.kind == "message.user" -> Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.End) {
            Text(e.text.orEmpty(), style = chatTextStyle(), color = c.text, modifier = Modifier.clip(BubbleShape).background(c.bubble).padding(horizontal = 17.dp, vertical = 11.dp))
        }
        e.text != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val bot = graph.bots.bots.firstOrNull { it.name == e.actorProfile }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bot != null) BotAvatar(bot, graph.avatars[bot.name], 26.dp)
                Text(r.members.firstOrNull { it.profile == e.actorProfile }?.display ?: e.actor, style = CType.caption.semibold(), color = c.text)
            }
            AfMarkdown(e.text)
        }
        else -> Text(
            when (e.kind) { "turn.settled" -> stringResource(R.string.botsscreen_turn_settled); "turn.failed" -> stringResource(R.string.botsscreen_turn_failed); "turn.cancelled" -> stringResource(R.string.botsscreen_turn_cancelled); else -> e.kind },
            style = CType.caption, color = c.secondary, modifier = Modifier.fillMaxWidth(),
        )
    }
}
