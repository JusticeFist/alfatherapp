package app.hermesandroid.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermesandroid.AppGraph
import app.hermesandroid.net.ServerRequest
import app.hermesandroid.net.bool
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.components.StatusLine
import app.hermesandroid.ui.shell.Avatar
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.Backdrop
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.AfColors
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.bold
import app.hermesandroid.ui.theme.chatTextStyle
import app.hermesandroid.ui.theme.glass
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.ScanLine
import app.hermesandroid.ui.theme.Radii
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import app.hermesandroid.ui.theme.fadeToCanvas
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R
import app.hermesandroid.tr

// Conversation screen.

/** Profile photos for transcript rows (AppGraph.avatars). */
val LocalAvatars = androidx.compose.runtime.staticCompositionLocalOf<Map<String, ByteArray>> { emptyMap() }

private enum class Sheet { Model, Context, Agents, Gateway }

@Composable
fun ChatScreen(graph: AppGraph, onMenu: () -> Unit) {
    val s = graph.chat
    val c = C.colors
    // Home is a fresh chat: Back from a conversation returns there, from a fresh one it leaves the app.
    BackHandler(enabled = s.messages.isNotEmpty()) { s.openNew() }
    val connected by graph.client.connected.collectAsStateWithLifecycle()
    // A fresh scroll state per chat: one shared across chats opened the next chat at the last one's position, and its
    // "scrolled back" stopped the jump to the end.
    val list = remember(s.opened) { LazyListState() }
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val last = s.messages.lastOrNull()
    val atBottom by remember(list) { derivedStateOf { !list.canScrollForward } }
    val files = remember(graph.profile) {
        object : GatewayFiles {
            override suspend fun dataUrl(path: String) = runCatching {
                graph.client.rest("GET", graph.client.withProfile("/api/fs/read-data-url?path=" + URLEncoder.encode(path, "UTF-8"))).obj()?.str("dataUrl")
            }.getOrNull()
            override suspend fun text(path: String) = runCatching {
                graph.client.rest("GET", graph.client.withProfile("/api/fs/read-text?path=" + URLEncoder.encode(path, "UTF-8"))).obj()?.str("text")
            }.getOrNull()
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(graph.readAloud.error) { graph.readAloud.error?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); graph.readAloud.error = null } }
    LaunchedEffect(s.showModelPicker) { if (s.showModelPicker) { sheet = Sheet.Model; s.showModelPicker = false } }
    // Item count computed from state (layoutInfo lags a frame behind a fresh history load).
    val lead = (if (s.hasOlder) 1 else 0) + (if (s.offline) 1 else 0) + (if (s.messages.isEmpty() && !s.running) 1 else 0)
    val trail = (if (s.pendingSend != null) 1 else 0) + (if (s.takeoverOffered) 1 else 0) + (if (s.running || s.statusText != null) 1 else 0)
    val total = lead + s.messages.size + trail
    // A chat opens at its end (the last thing said) and follows the stream (and rows that grow once measured) unless the
    // reader scrolled up. A question asked here only until it reaches the top: from there the answer grows below it and
    // is read from its start, as in Claude's app.
    val totalNow by rememberUpdatedState(total)
    val askAt by rememberUpdatedState(if (!s.asked) -1 else s.messages.indexOfLast { it.role == Role.User }.let { if (it < 0) -1 else lead + it })
    // Keyed on the list: each chat's own state (a Unit key kept watching the first chat's, so later chats never moved).
    LaunchedEffect(list) {
        snapshotFlow { list.canScrollForward to totalNow }.collect { (more, t) ->
            val ask = askAt
            if (!more || t == 0 || list.lastScrolledBackward || (ask >= 0 && list.firstVisibleItemIndex >= ask)) return@collect
            if (ask >= 0) list.scrollToItem(ask) else list.scrollToItem(t - 1, Int.MAX_VALUE / 2)
        }
    }
    CompositionLocalProvider(LocalGatewayFiles provides files, LocalAvatars provides graph.avatars) {
        Box(Modifier.fillMaxSize()) {
            Backdrop(Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                ChatTopBar(s, s.chatProfile ?: graph.profile, connected, graph.client.lastError.collectAsStateWithLifecycle().value, graph.tasks.unread.isNotEmpty(), onMenu, onAgents = { sheet = Sheet.Agents }, onContext = { sheet = Sheet.Context }, onGateway = { sheet = Sheet.Gateway })
                Divider(inset = 0.dp)
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (s.hasOlder) item(key = "older") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (s.loadingOlder) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                                else SettingsButton(stringResource(R.string.chatscreen_load_older)) { s.loadOlder() }
                            }
                        }
                        if (s.offline) item(key = "offline") { StatusLine(stringResource(R.string.chatscreen_offline), c.secondary, AF.Sync) }
                        if (s.messages.isEmpty() && !s.running) item(key = "empty") { EmptyChat(s.agentName) }
                        // The agent's name heads a turn once; tool steps split a turn into several replies.
                        itemsIndexed(s.messages, key = { _, m -> m.key }) { i, m -> MessageItem(s, m, s.chatProfile ?: graph.profile, opensTurn = i == 0 || s.messages[i - 1].role == Role.User) }
                        s.pendingSend?.let { text -> item(key = "pending") { PendingBubble(text) { s.cancelPendingSend() } } }
                        if (s.takeoverOffered) item(key = "takeover") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { SettingsButton(stringResource(R.string.chatscreen_take_over), prominent = true) { s.takeOver() } }
                        }
                        if (s.running) item(key = "working") { WorkingLine(s.chatProfile ?: graph.profile, s.turnStartedAt, s.messages.lastOrNull(), s.statusText, graph.profiles) }
                        else s.statusText?.let { item(key = "status") { StatusLine(it, c.secondary, AF.Sync) } }
                    }
                    if (!atBottom) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp)
                                .size(42.dp).glass(CircleShape)
                                .pressable(label = stringResource(R.string.chatscreen_scroll_end)) { scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE / 2) } },
                            contentAlignment = Alignment.Center,
                        ) { Icon(AF.ArrowDownward, null, tint = c.text, modifier = Modifier.size(17.dp)) }
                    }
                }
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    s.requests.forEach { RequestCard(s, it) }
                    graph.phone.changes.open.forEach { PhoneChangeCard(graph, it) }
                    Composer(s, s.chatProfile ?: graph.profile, onModel = { sheet = Sheet.Model }, onContext = { sheet = Sheet.Context }, onAgents = { sheet = Sheet.Agents })
                }
            }
        }
        when (sheet) {
            Sheet.Model -> ModelPickerSheet(s) { sheet = null }
            Sheet.Context -> ContextSheet(s) { sheet = null }
            Sheet.Agents -> SubagentsSheet(s) { sheet = null }
            Sheet.Gateway -> app.hermesandroid.settings.GatewaySheet(graph) { sheet = null }
            null -> {}
        }
    }
}

@Composable
private fun ChatTopBar(s: ChatState, profile: String, connected: Boolean, error: String?, unread: Boolean, onMenu: () -> Unit, onAgents: () -> Unit, onContext: () -> Unit, onGateway: () -> Unit) {
    val c = C.colors
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.chatscreen_menu), haptic = Haptic.Light, onClick = onMenu), contentAlignment = Alignment.Center) {
            Icon(AF.Menu, stringResource(if (unread) R.string.chatscreen_menu_unread else R.string.chatscreen_menu), tint = c.text, modifier = Modifier.size(22.dp))
            // A scheduled run's result waits in the sidebar (Tareas).
            if (unread) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 9.dp).size(7.dp).clip(CircleShape).background(c.accent))
        }
        Avatar(profile, size = 30.dp, image = LocalAvatars.current[profile])
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = CType.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The model lives in the composer (as in Claude's app); the bar only speaks up when the link is down.
            when {
                connected -> {}
                error == null -> StatusLine(stringResource(R.string.chatscreen_reconnecting), c.orange, AF.Sync)
                else -> StatusLine(stringResource(R.string.chatscreen_disconnected), c.red, AF.WifiOff)
            }
        }
        if (s.messages.isNotEmpty()) Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.chatscreen_new_chat), haptic = Haptic.Light) { s.openNew() }, contentAlignment = Alignment.Center) {
            Icon(AF.Compose, stringResource(R.string.chatscreen_new_chat), tint = c.text, modifier = Modifier.size(20.dp))
        }
        Box {
            Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.chatscreen_more)) { menu = true }, contentAlignment = Alignment.Center) {
                Icon(AF.MoreHoriz, null, tint = c.text, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.chatscreen_delegated_agents)) }, { menu = false; onAgents() }, leadingIcon = { Icon(AF.Group, null) })
                DropdownMenuItem({ Text(stringResource(R.string.chatscreen_context_compress)) }, { menu = false; onContext() }, leadingIcon = { Icon(AF.Psychology, null) })
                DropdownMenuItem({ Text("Gateway") }, { menu = false; onGateway() }, leadingIcon = { Icon(AF.Language, null) })
                s.messages.lastOrNull { it.role == Role.Assistant }?.let { m ->
                    DropdownMenuItem({ Text(stringResource(R.string.chatscreen_branch_last)) }, { menu = false; s.branchFrom(m) }, leadingIcon = { Icon(AF.ForkRight, null) })
                }
            }
        }
    }
}

@Composable
private fun EmptyChat(name: String) {
    val c = C.colors
    val greeting = stringResource(remember {
        when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) { in 5..11 -> R.string.chatscreen_good_morning; in 12..18 -> R.string.chatscreen_good_afternoon; else -> R.string.chatscreen_good_evening }
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(app.hermesandroid.R.drawable.af_empty), null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.2f).clip(Radii.lg).fadeToCanvas(),
        )
        Spacer(Modifier.height(10.dp))
        Eyebrow(greeting)
        Text(stringResource(R.string.chatscreen_empty_title), style = CType.largeTitle, color = c.text)
        Text(stringResource(R.string.chatscreen_empty_ready, name), style = CType.subheadline, color = c.secondary)
    }
}

@Composable
private fun MessageItem(s: ChatState, m: ChatMsg, profile: String, opensTurn: Boolean) {
    when (m.role) {
        Role.User -> UserBubble(s, m)
        Role.Assistant -> AssistantMessage(s, m, profile, opensTurn)
        Role.Tool -> if (isCardTool(m.toolName) && m.done && cardsOf(m).isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cardsOf(m).forEach { RichCard(it) } }
        } else StepCard(m, remember(m.args, s.graph.profiles.size) { consultOf(m.args, s.graph.profiles) })
        Role.System -> SystemNote(m.text, when (m.toolName) {
            "" -> stringResource(R.string.chatscreen_label_system)
            ChatState.NOTE_COMMAND -> stringResource(R.string.chatscreen_label_command)
            ChatState.NOTE_BACKGROUND -> stringResource(R.string.chatscreen_label_background)
            ChatState.NOTE_BTW -> "BTW"
            else -> m.toolName
        }, error = false, command = m.toolName == ChatState.NOTE_COMMAND)
        Role.Error -> SystemNote(m.text, stringResource(R.string.chatscreen_label_notice), error = true)
    }
}

/** A sent message's bubble (chat and bot rooms): a tighter corner on the sender's side. */
internal val BubbleShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)

private val REACTIONS = listOf("👍", "❤️", "😂", "🎉", "🤔", "👎")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(s: ChatState, m: ChatMsg) {
    val c = C.colors
    val clipboard = LocalClipboardManager.current
    val shape = BubbleShape
    val steer = m.toolName == ChatState.STEER_MARK
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Box(
                Modifier
                    .clip(shape)
                    .then(if (steer) Modifier.border(Dp.Hairline, c.aura, shape) else Modifier.background(c.bubble))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = if (steer) 15.dp else 17.dp, vertical = if (steer) 10.dp else 11.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (steer) Icon(AF.Navigation, null, tint = c.aura, modifier = Modifier.size(15.dp))
                    Text(m.text, style = chatTextStyle(), color = c.text)
                }
            }
            MessageMenu(menu, { menu = false }, s, m)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            m.reaction?.let { Text(it, fontSize = 15.sp) }
            if (m.time > 0) Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.time)), style = CType.data, color = c.tertiary)
            Icon(AF.ContentCopy, stringResource(R.string.chatscreen_copy), tint = c.secondary, modifier = Modifier.size(14.dp).pressable(haptic = Haptic.Light) { clipboard.setText(AnnotatedString(m.text)) })
        }
    }
}

@Composable
private fun MessageMenu(open: Boolean, onDismiss: () -> Unit, s: ChatState, m: ChatMsg) {
    val clipboard = LocalClipboardManager.current
    DropdownMenu(open, onDismiss) {
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            REACTIONS.forEach { e -> Text(e, fontSize = 22.sp, modifier = Modifier.pressable(haptic = Haptic.Selection) { s.react(m, e); onDismiss() }.padding(6.dp)) }
        }
        DropdownMenuItem({ Text(stringResource(R.string.chatscreen_copy)) }, { clipboard.setText(AnnotatedString(m.text)); onDismiss() }, leadingIcon = { Icon(AF.ContentCopy, null) })
        DropdownMenuItem({ Text(stringResource(R.string.chatscreen_quote)) }, { s.quote(m.text); onDismiss() }, leadingIcon = { Icon(AF.FormatQuote, null) })
        if (m.role == Role.Assistant) DropdownMenuItem({ Text(stringResource(R.string.chatscreen_branch_here)) }, { s.branchFrom(m); onDismiss() }, leadingIcon = { Icon(AF.ForkRight, null) })
    }
}

@Composable
private fun AssistantMessage(s: ChatState, m: ChatMsg, profile: String, opensTurn: Boolean) {
    val c = C.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1400); copied = false } }
    // Phone-control steps (```acciones) are for the app, not the user: show only what Allfather says.
    val say = remember(m.text) { app.hermesandroid.control.parseReply(m.text).say }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (opensTurn) Eyebrow(profileTitle(profile))
        if (m.reasoning.isNotBlank()) ThinkingCard(m.reasoning)
        if (say.isNotBlank()) {
            ReplyBody(say)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                m.reaction?.let { Text(it, fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp)) }
                Box {
                    ActionIcon(AF.AddReaction, stringResource(R.string.chatscreen_react)) { menu = true }
                    MessageMenu(menu, { menu = false }, s, m)
                }
                ActionIcon(if (copied) AF.Check else AF.ContentCopy, stringResource(R.string.chatscreen_copy), if (copied) c.accent else c.secondary) { clipboard.setText(AnnotatedString(say)); copied = true }
                val ra = s.graph.readAloud
                val reading = ra.current == m.key.toString()
                if (reading && ra.loading) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp) }
                else ActionIcon(if (reading) AF.Stop else AF.VolumeUp, stringResource(if (reading) R.string.chatscreen_stop_reading else R.string.chatscreen_read_aloud), if (reading) c.accent else c.secondary) { ra.toggle(m.key.toString(), say) }
                ActionIcon(AF.FormatQuote, stringResource(R.string.chatscreen_quote)) { s.quote(say) }
                ActionIcon(AF.ForkRight, stringResource(R.string.chatscreen_branch)) { s.branchFrom(m) }
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, label: String, tint: Color = C.colors.secondary, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).pressable(label = label, haptic = Haptic.Light, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PendingBubble(text: String, onCancel: () -> Unit) {
    val c = C.colors
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, style = chatTextStyle(), color = c.secondary, modifier = Modifier.clip(BubbleShape).border(Dp.Hairline, c.composerStroke, BubbleShape).padding(horizontal = 16.dp, vertical = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chatscreen_sends_on_connect), style = CType.caption2.medium(), color = c.secondary)
            Text(stringResource(R.string.chatscreen_cancel), style = CType.caption2.semibold(), color = c.accent, modifier = Modifier.pressable(onClick = onCancel))
        }
    }
}

/** "Working" line: portrait, phrase and timer in mono, and a light sweeping a hairline underneath. */
@Composable
private fun WorkingLine(profile: String, since: Long, last: ChatMsg?, status: String?, profiles: Collection<String>) {
    val c = C.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val phrase = status ?: when {
        last?.role == Role.Tool && !last.done -> consultOf(last.args, profiles)?.running ?: toolStyle(last.toolName, c).running
        last?.role == Role.Assistant && last.text.isEmpty() -> stringResource(R.string.chatscreen_thinking)
        else -> stringResource(R.string.chatscreen_working)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Avatar(profile, 22.dp, image = LocalAvatars.current[profile])
            Text(phrase.lowercase(), style = CType.data.copy(fontSize = CType.footnote.fontSize), color = c.secondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (since > 0) {
                val secs = ((now - since) / 1000).coerceAtLeast(0)
                Text("%d:%02d".format(secs / 60, secs % 60), style = CType.data, color = c.tertiary)
            }
        }
        ScanLine(Modifier.fillMaxWidth())
    }
}

private data class ToolStyle(val icon: ImageVector, val tint: Color, val title: String, val running: String)

/** Tool category → icon / tint / titles (ChatStepStyle). */
private fun toolStyle(name: String, c: AfColors): ToolStyle {
    val n = name.lowercase()
    fun style(icon: ImageVector, tint: Color, title: Int, running: Int) = ToolStyle(icon, tint, tr(title), tr(running))
    return when {
        "terminal" in n || "shell" in n || "bash" in n || "exec" in n || "process" in n -> style(AF.Terminal, c.success, R.string.chatscreen_tool_command, R.string.chatscreen_tool_command_running)
        "patch" in n || "edit" in n || "code" in n -> style(AF.Code, c.success, R.string.chatscreen_tool_code, R.string.chatscreen_tool_code_running)
        "file" in n || "read" in n || "write" in n || "search_files" in n -> style(AF.Description, c.secondary, R.string.chatscreen_tool_files, R.string.chatscreen_tool_files_running)
        "browser" in n -> style(AF.Explore, c.aura, R.string.chatscreen_tool_browser, R.string.chatscreen_tool_browser_running)
        "web" in n || "search" in n || "fetch" in n -> style(AF.Language, c.aura, R.string.chatscreen_tool_web, R.string.chatscreen_tool_web_running)
        "delegate" in n || "agent" in n -> style(AF.Group, c.accent, R.string.chatscreen_tool_delegate, R.string.chatscreen_tool_delegate_running)
        "memory" in n -> style(AF.Psychology, c.scheduled, R.string.chatscreen_tool_memory, R.string.chatscreen_tool_memory_running)
        "image" in n || "vision" in n -> style(AF.Image, c.voice, R.string.chatscreen_tool_images, R.string.chatscreen_tool_images_running)
        "audio" in n || "tts" in n || "speech" in n -> style(AF.GraphicEq, c.voice, R.string.chatscreen_tool_audio, R.string.chatscreen_tool_audio_running)
        "todo" in n || "plan" in n -> style(AF.FactCheck, c.accent, R.string.chatscreen_tool_plan, R.string.chatscreen_tool_plan_running)
        "cron" in n || "schedule" in n -> style(AF.Schedule, c.scheduled, R.string.chatscreen_tool_schedule, R.string.chatscreen_tool_schedule_running)
        "send" in n || "message" in n -> style(AF.Send, c.aura, R.string.chatscreen_tool_message, R.string.chatscreen_tool_message_running)
        "skill" in n -> style(AF.MenuBook, c.scheduled, R.string.chatscreen_tool_skill, R.string.chatscreen_tool_skill_running)
        "mcp" in n || "conduit" in n -> style(AF.Extension, c.secondary, R.string.chatscreen_tool_connector, R.string.chatscreen_tool_connector_running)
        "home" in n -> style(AF.Home, c.success, R.string.chatscreen_tool_home, R.string.chatscreen_tool_home_running)
        else -> name.ifBlank { tr(R.string.chatscreen_tool) }.let { ToolStyle(AF.Build, c.secondary, it, it) }
    }
}

/** The agent asking its helper bots (host skill helper-team): `hermes -p <bot> chat …`, one or several at once
 *  from execute_code, or a board task `kanban create … --assignee <bot>`. [bots] are profile names. */
internal data class Consult(val bots: List<String>, val task: Boolean) {
    private val names get() = bots.map(::profileTitle).let { if (it.size == 1) it[0] else tr(R.string.chatscreen_names_and, it.dropLast(1).joinToString(", "), it.last()) }
    val title get() = tr(if (task) R.string.chatscreen_task_done else R.string.chatscreen_consult_done, names)
    val running get() = tr(if (task) R.string.chatscreen_task_running else R.string.chatscreen_consult_running, names)
}

internal fun consultOf(args: String, profiles: Collection<String>): Consult? {
    if ("hermes" !in args) return null
    val bots = profiles.filter { it != "default" }
    if ("kanban" in args && "create" in args) {
        val who = Regex("--assignee[^A-Za-z0-9_-]{1,6}([A-Za-z0-9_-]+)").findAll(args).map { it.groupValues[1] }.filter { it in bots }.distinct().toList()
        return who.takeIf { it.isNotEmpty() }?.let { Consult(it, task = true) }
    }
    if (!Regex("\\bchat\\b").containsMatchIn(args) || ("-p" !in args && "--profile" !in args)) return null
    val who = bots.filter { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(args) }
    return who.takeIf { it.isNotEmpty() }?.let { Consult(it, task = false) }
}

/** Tool step: one node on a hairline rail (the trace), title, tool name in mono, expandable input and result.
 *  A consult of the helper bots shows who was asked, with their portraits, instead of the command. */
@Composable
private fun StepCard(m: ChatMsg, consult: Consult? = null) {
    val c = C.colors
    val style = toolStyle(m.toolName, c)
    var open by rememberSaveable(m.key) { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // The rail reaches half the list's 18 dp gap past each edge, so consecutive steps read as one line.
        Box(Modifier.width(20.dp).fillMaxHeight().drawBehind {
            val x = 6.dp.toPx()
            val node = Offset(x, 12.dp.toPx())
            drawLine(c.text.copy(alpha = 0.16f), Offset(x, -9.dp.toPx()), Offset(x, size.height + 9.dp.toPx()), strokeWidth = 1.dp.toPx())
            drawCircle(c.canvas, 4.5.dp.toPx(), node)
            if (m.done) drawCircle(c.secondary, 3.dp.toPx(), node, style = Stroke(1.dp.toPx())) else drawCircle(c.text, 3.dp.toPx(), node)
        })
        Column(Modifier.weight(1f).pressable(haptic = Haptic.Light) { open = !open }.animateContentSize()) {
            Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (consult == null) Icon(style.icon, null, tint = c.secondary, modifier = Modifier.size(14.dp))
                else Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { consult.bots.forEach { Avatar(it, 18.dp, image = LocalAvatars.current[it]) } }
                Spacer(Modifier.width(8.dp))
                Text(
                    consult?.let { if (m.done) it.title else it.running } ?: if (m.done) m.text.ifBlank { style.title } else style.running,
                    style = CType.subheadline, color = if (consult != null) c.text else c.secondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (!m.done) CircularProgressIndicator(Modifier.size(12.dp), color = c.text, strokeWidth = 1.5.dp)
                else Icon(AF.ExpandMore, null, tint = c.tertiary, modifier = Modifier.size(15.dp).rotate(angle))
            }
            if (m.toolName.isNotBlank() && consult == null) Text(m.toolName, style = CType.data, color = c.tertiary, modifier = Modifier.padding(start = 22.dp), maxLines = 1)
            if (open) Column(Modifier.padding(start = 22.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RawBox(prettyArgs(m.args))
                if (m.result.isNotBlank()) ResultView(m.result)
            }
        }
    }
}

/** Tool arguments as `key: value` lines when they're a flat JSON object. */
private fun prettyArgs(args: String): String = runCatching {
    val o = kotlinx.serialization.json.Json.parseToJsonElement(args).obj() ?: return args
    o.entries.joinToString("\n") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.contentOrNull ?: v.toString()}" }
}.getOrDefault(args)

/** Results: unified diffs coloured line by line, everything else as raw output (ToolResultViews). */
@Composable
private fun ResultView(text: String) {
    val c = C.colors
    val looksDiff = text.lineSequence().take(40).count { it.startsWith("@@") || it.startsWith("+++") || it.startsWith("---") } >= 2
    if (!looksDiff) return RawBox(text)
    Column(Modifier.fillMaxWidth().clip(Radii.md).background(c.raised).padding(10.dp)) {
        text.lines().take(400).forEach { line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> c.success
                line.startsWith("-") && !line.startsWith("---") -> c.red
                line.startsWith("@@") -> c.aura
                else -> c.text.copy(alpha = 0.8f)
            }
            Text(line, style = CType.data, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RawBox(text: String) {
    if (text.isBlank()) return
    val c = C.colors
    Text(
        text.take(6000),
        style = CType.data,
        color = c.text.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth().clip(Radii.md).background(c.raised).padding(10.dp),
    )
}

@Composable
private fun ThinkingCard(text: String) {
    val c = C.colors
    var open by remember { mutableStateOf(false) }
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Column(Modifier.fillMaxWidth().pressable(haptic = Haptic.Light) { open = !open }.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AF.Pending, null, tint = c.tertiary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.chatscreen_thought), style = CType.data.copy(fontSize = CType.footnote.fontSize), color = c.tertiary, modifier = Modifier.weight(1f))
            Icon(AF.ExpandMore, null, tint = c.tertiary, modifier = Modifier.size(15.dp).rotate(angle))
        }
        if (open) Text(text, style = CType.callout, color = c.secondary, modifier = Modifier.padding(start = 22.dp, top = 8.dp))
    }
}

/** System / command message: accent 6 % r=16 with an uppercase label. */
@Composable
private fun SystemNote(text: String, label: String, error: Boolean, command: Boolean = false) {
    val c = C.colors
    val tint = if (error) c.red else c.accent
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(4.dp).clip(shape).background(tint.copy(alpha = 0.06f)).border(1.dp, tint.copy(alpha = 0.15f), shape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(if (command) AF.Terminal else AF.Info, null, tint = tint, modifier = Modifier.size(12.dp))
            Text(label, style = CType.caption2.semibold().copy(letterSpacing = 0.5.sp), color = tint)
        }
        if (error) Text(text, style = CType.subheadline, color = c.text) else AfMarkdown(text, scale = 0.9f)
    }
}

// ---- approval / clarify / secret cards ----

@Composable
private fun RequestCard(s: ChatState, r: ServerRequest) {
    val c = C.colors
    val tint = if (r.method == "approval") c.attention else c.accent
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().settingsCard(RoundedCornerShape(22.dp), tint = tint.copy(alpha = 0.08f)).padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (r.method) {
            "approval" -> ApprovalBody(s, r, tint)
            "clarify" -> ClarifyBody(s, r, tint)
            else -> SecretBody(s, r, tint)
        }
    }
}

@Composable
private fun ApprovalBody(s: ChatState, r: ServerRequest, tint: Color) {
    val c = C.colors
    val p = r.params
    val view = LocalView.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AF.VerifiedUser, null, tint = tint, modifier = Modifier.size(18.dp))
        Column {
            Text(stringResource(R.string.chatscreen_awaiting_approval), style = CType.caption2.bold().copy(letterSpacing = 0.5.sp), color = tint)
            Text(p.str("description") ?: (p.str("tool_name") ?: stringResource(R.string.chatscreen_asks_permission, s.agentName)), style = CType.subheadline.bold(), color = c.text)
        }
    }
    // Shown verbatim but stripped of bidi controls that could disguise a command (ApprovalDisplayText).
    p.str("command")?.let {
        Text(it.filterNot { ch -> ch in '‪'..'‮' || ch in '⁦'..'⁩' }, style = CType.data, color = c.text, maxLines = 8, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.text.copy(alpha = 0.06f)).padding(12.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        approvalChoices(p).forEach { choice ->
            val deny = choice == "deny"
            Text(
                CHOICE_LABELS[choice]?.let { stringResource(it) } ?: choice,
                style = CType.subheadline.medium(),
                color = if (deny) c.red else if (choice == "once") c.onAccent else c.accent,
                modifier = Modifier.clip(CircleShape)
                    .background(if (choice == "once") c.accent else if (deny) c.red.copy(alpha = 0.1f) else c.accent.copy(alpha = 0.15f))
                    .pressable(haptic = if (deny) Haptic.Warning else Haptic.Success) { s.answerApproval(r, choice) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** A change Allfather wants to make on this phone (calendar, alarm, timer), waiting on the user. */
@Composable
private fun PhoneChangeCard(graph: AppGraph, ch: app.hermesandroid.phone.PhoneChange) {
    val c = C.colors
    val changes = graph.phone.changes
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().settingsCard(RoundedCornerShape(22.dp), tint = c.accent.copy(alpha = 0.08f)).padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(AF.CalendarMonth, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Column {
                Text(stringResource(R.string.chatscreen_phone_change), style = CType.caption2.bold().copy(letterSpacing = 0.5.sp), color = c.accent)
                Text(changes.describe(ch), style = CType.subheadline.bold(), color = c.text)
            }
        }
        ch.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = CType.footnote, color = c.secondary) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chatscreen_do_it), style = CType.subheadline.medium(), color = c.onAccent,
                modifier = Modifier.clip(CircleShape).background(c.accent).pressable(haptic = Haptic.Success) { changes.approve(ch) }.padding(horizontal = 14.dp, vertical = 8.dp))
            Text(stringResource(R.string.chatscreen_not_now), style = CType.subheadline.medium(), color = c.red,
                modifier = Modifier.clip(CircleShape).background(c.red.copy(alpha = 0.1f)).pressable(haptic = Haptic.Warning) { changes.dismiss(ch) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

private data class Question(val id: String, val text: String, val choices: List<Pair<String, String>>, val multi: Boolean)

/** Clarify: `questions[]` (batch, answered per question with clarify.lock) or the scalar single-question shape. */
private fun questionsOf(p: JsonObject): Pair<List<Question>, Boolean> {
    fun textOf(o: JsonObject) = listOf("question", "prompt", "text").firstNotNullOfOrNull { o.str(it)?.takeIf { s -> s.isNotBlank() } }
    fun choicesOf(o: JsonObject) = ((o["choices"] ?: o["options"]) as? JsonArray).orEmpty().mapNotNull { ch ->
        (ch as? JsonPrimitive)?.contentOrNull?.let { it to it } ?: ch.obj()?.let { co ->
            val label = listOf("label", "title", "text", "name", "value").firstNotNullOfOrNull { co.str(it) } ?: return@mapNotNull null
            label to (listOf("value", "answer", "id").firstNotNullOfOrNull { co.str(it) } ?: label)
        }
    }.distinctBy { it.second }
    val batch = (p["questions"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { q ->
        val id = listOf("qid", "question_id", "id").firstNotNullOfOrNull { q.str(it) } ?: return@mapNotNull null
        val text = textOf(q) ?: return@mapNotNull null
        val ch = choicesOf(q)
        Question(id, text, ch, q.bool("multi_select") == true && ch.isNotEmpty())
    }.distinctBy { it.id }
    if (batch.isNotEmpty()) return batch to true
    val ch = choicesOf(p)
    return listOf(Question("q0", textOf(p) ?: tr(R.string.chatscreen_a_question), ch, p.bool("multi_select") == true && ch.isNotEmpty())) to false
}

@Composable
private fun ClarifyBody(s: ChatState, r: ServerRequest, tint: Color) {
    val c = C.colors
    val (questions, batch) = remember(r.id) { questionsOf(r.params) }
    val answers = remember(r.id) { mutableStateMapOf<String, String>() }
    val multi = remember(r.id) { mutableStateMapOf<String, Set<String>>() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AF.ContactSupport, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(pluralStringResource(R.plurals.chatscreen_questions, questions.size, s.agentName, questions.size), style = CType.caption2.bold().copy(letterSpacing = 0.5.sp), color = tint)
    }
    questions.forEach { q ->
        Text(q.text, style = CType.subheadline.bold(), color = c.text)
        q.choices.forEach { (label, value) ->
            val sel = if (q.multi) value in multi[q.id].orEmpty() else answers[q.id] == value
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) c.accent.copy(alpha = 0.13f) else c.text.copy(alpha = 0.05f))
                    .pressable(haptic = Haptic.Selection) {
                        if (q.multi) {
                            val cur = multi[q.id].orEmpty()
                            multi[q.id] = if (value in cur) cur - value else cur + value
                            answers[q.id] = multi[q.id].orEmpty().joinToString(", ")
                        } else answers[q.id] = value
                    }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (sel) AF.CheckCircle else AF.RadioButtonUnchecked, null, tint = if (sel) c.accent else c.tertiary, modifier = Modifier.size(18.dp))
                Text(label, style = CType.subheadline, color = c.text)
            }
        }
        if (q.choices.isEmpty()) AnswerField(answers[q.id].orEmpty(), secret = false) { answers[q.id] = it }
    }
    val ready = questions.all { !answers[it.id].isNullOrBlank() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SettingsButton(stringResource(R.string.chatscreen_answer), AF.Send, prominent = ready) { if (ready) s.answerClarify(r, questions.associate { it.id to answers[it.id].orEmpty() }, batch) }
    }
}

@Composable
private fun SecretBody(s: ChatState, r: ServerRequest, tint: Color) {
    val c = C.colors
    var value by rememberSaveable(r.id) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AF.Lock, null, tint = tint, modifier = Modifier.size(18.dp))
        Text(r.params.str("prompt") ?: r.params.str("message") ?: r.params.str("question") ?: stringResource(R.string.chatscreen_needs_info, s.agentName), style = CType.subheadline.bold(), color = c.text)
    }
    AnswerField(value, secret = true) { value = it }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        SettingsButton(stringResource(R.string.chatscreen_skip)) { s.answerSecret(r, "") }
        SettingsButton(stringResource(R.string.chatscreen_send), prominent = true) { s.answerSecret(r, value) }
    }
}

@Composable
private fun AnswerField(value: String, secret: Boolean, onChange: (String) -> Unit) {
    val c = C.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.text.copy(alpha = 0.05f)).padding(12.dp)) {
        if (value.isEmpty()) Text(stringResource(R.string.chatscreen_type_answer), style = CType.body, color = c.tertiary)
        BasicTextField(value, onChange, singleLine = secret, textStyle = CType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None, modifier = Modifier.fillMaxWidth())
    }
}

private val CHOICE_LABELS = mapOf(
    "once" to R.string.chatscreen_choice_once, "session" to R.string.chatscreen_choice_session,
    "always" to R.string.chatscreen_choice_always, "deny" to R.string.chatscreen_choice_deny,
)

/** Server default when `choices` is missing: once, session (unless smart_denied / allow_session=false), always (unless allow_permanent=false), deny. */
private fun approvalChoices(p: JsonObject): List<String> =
    (p["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?: buildList {
            add("once")
            if (p.bool("smart_denied") != true && p.bool("allow_session") != false) add("session")
            if (p.bool("allow_permanent") != false) add("always")
            add("deny")
        }
