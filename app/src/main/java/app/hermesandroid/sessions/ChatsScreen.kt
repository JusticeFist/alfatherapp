package app.hermesandroid.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.net.str
import app.hermesandroid.ui.components.AfField
import app.hermesandroid.ui.components.ago
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.components.Wordmark
import app.hermesandroid.ui.shell.CapsuleIcon
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.AfColors
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.glass
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import java.util.Calendar
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.Page
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import app.hermesandroid.ui.shell.Avatar
import app.hermesandroid.ui.shell.PageBar
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.IconWell
import app.hermesandroid.ui.components.Pill
import app.hermesandroid.ui.components.ScreenHeader
import app.hermesandroid.ui.components.SectionHeader
import app.hermesandroid.ui.theme.Radii
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R
import app.hermesandroid.tr

// Chats tab.

private enum class Section(@StringRes val title: Int) {
    Pinned(R.string.chatsscreen_pinned), Today(R.string.chatsscreen_today), Yesterday(R.string.chatsscreen_yesterday),
    Week(R.string.chatsscreen_week), Older(R.string.chatsscreen_older),
}

private fun sectionOf(row: SessionRow, startOfToday: Long): Section {
    if (row.pinned) return Section.Pinned
    val t = (row.updated * 1000).toLong()
    val days = (startOfToday - t) / 86_400_000.0
    return when {
        t >= startOfToday -> Section.Today
        days < 1 -> Section.Yesterday
        days < 7 -> Section.Week
        else -> Section.Older
    }
}

private sealed interface Dialog {
    data class Rename(val row: SessionRow) : Dialog
    data class Delete(val row: SessionRow) : Dialog
    data class Move(val row: SessionRow) : Dialog
    data object NewProject : Dialog
    data class RenameProject(val p: Project) : Dialog
    data class DeleteProject(val p: Project) : Dialog
}

/**
 * The sidebar (the app's structure follows Claude's: the chat is home, everything else lives here): wordmark,
 * filters and search; the other sections; pinned and recent chats; at the foot the profile (Settings, long press
 * to switch profiles) and a new chat.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(graph: AppGraph, onClose: () -> Unit) {
    val s = graph.chats
    val c = C.colors
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var searching by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var profilesSheet by remember { mutableStateOf(false) }
    if (profilesSheet) app.hermesandroid.settings.ProfilesSheet(graph) { profilesSheet = false }
    fun go(page: Page) { graph.page = page; onClose() }
    fun open(id: String, title: String, profile: String? = null) { graph.chat.open(id, title, profile); onClose() }
    val connected by graph.client.connected.collectAsStateWithLifecycle()
    val lastError by graph.client.lastError.collectAsStateWithLifecycle()
    val waiting = graph.requests.mapNotNull { it.params.str("session_id") }.toSet()
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } == true } }
    LaunchedEffect(nearEnd) { if (nearEnd) s.loadMore() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(stringResource(R.string.app_name), CType.title3, Modifier.weight(1f))
            Box {
                CapsuleIcon(AF.FilterList, stringResource(R.string.chatsscreen_filters), tint = if (s.filter != Filter.All) c.text else c.secondary, dot = if (graph.requests.isNotEmpty()) c.attention else null) { filterMenu = true }
                FilterMenu(s, filterMenu) { filterMenu = false }
            }
            CapsuleIcon(AF.Search, stringResource(R.string.chatsscreen_search), tint = if (searching) c.text else c.secondary) { searching = !searching; if (!searching) s.onQueryChange("") }
        }
        if (searching) SearchField(s, profileTitle(graph.profile))
        filterLabel(s.filter)?.let { label -> Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Pill(label, selected = true) { s.applyFilter(Filter.All) } } }
        val startOfToday = remember { Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
        val visible = s.visible()
        val flat = s.filter == Filter.Archived || s.filter == Filter.Scheduled
        val grouped = if (flat) mapOf(Section.Older to visible) else visible.groupBy { sectionOf(it, startOfToday) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
            if (!searching) {
                item(key = "nav") {
                    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                        NavRow(AF.Compose, stringResource(R.string.chatsscreen_new_chat)) { graph.chat.openNew(); onClose() }
                        if (graph.hostPlugin.reports("pulse")) NavRow(AF.Pulse, "Pulse", graph.pulse.pending.size) { go(Page.Pulse) }
                        NavRow(AF.Board, stringResource(R.string.chatsscreen_tasks), graph.tasks.unread.size) { go(Page.Tasks) }
                        NavRow(AF.Bots, "Bots") { go(Page.Bots) }
                        NavRow(AF.Folder, stringResource(R.string.chatsscreen_projects)) { go(Page.Projects) }
                        NavRow(AF.GridView, "Artifacts") { go(Page.Artifacts) }
                        Divider(inset = 22.dp)
                    }
                }
            }
            if (!connected && lastError != null) item(key = "conn") { Problem(lastError!!) }
            s.error?.let { item(key = "err") { Problem(it) } }
            Section.entries.forEach { section ->
                val rows = grouped[section] ?: return@forEach
                val isCollapsed = section.name in s.collapsed
                if (!flat) item(key = "h-${section.name}") {
                    SectionHeader(stringResource(section.title), rows.size, isCollapsed) { if (isCollapsed) s.collapsed.remove(section.name) else s.collapsed.add(section.name) }
                }
                if (!isCollapsed || flat) items(rows, key = { it.id }) { row -> SidebarRow(s, row, row.id in waiting, { dialog = it }) { open(row.id, row.title, row.profile) } }
            }
            if (s.searchHits.isNotEmpty() || s.searching) {
                item(key = "h-search") { SectionHeader(stringResource(R.string.chatsscreen_in_messages), s.searchHits.size, collapsed = false) {} }
                items(s.searchHits, key = { "hit-" + it.id }) { hit -> SearchHitRow(hit) { open(hit.id, hit.title ?: "Chat") } }
                if (s.searching) item(key = "searching") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 1.5.dp) } }
            }
            if (visible.isEmpty() && s.searchHits.isEmpty() && !s.refreshing) item(key = "empty") {
                Text(stringResource(if (s.filter == Filter.All) R.string.chatsscreen_no_chats else R.string.chatsscreen_no_match), style = CType.subheadline, color = c.secondary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
            }
        }
        Divider(inset = 0.dp)
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp).clip(Radii.pill)
                .combinedClickable(onClick = { go(Page.Settings) }, onLongClick = { profilesSheet = true }).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(graph.profile, size = 36.dp, image = graph.avatars[graph.profile])
            Column {
                Text(profileTitle(graph.profile), style = CType.callout.medium(), color = c.text, maxLines = 1)
                Text(stringResource(R.string.chatsscreen_settings), style = CType.data, color = c.tertiary)
            }
        }
    }
    Dialogs(s, dialog) { dialog = null }
}

/** A sidebar entry that opens one of the app's sections. */
@Composable
private fun NavRow(icon: ImageVector, label: String, count: Int = 0, onClick: () -> Unit) {
    val c = C.colors
    Row(
        Modifier.fillMaxWidth().pressable(haptic = Haptic.Light, onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = c.secondary, modifier = Modifier.size(19.dp))
        Text(label, style = CType.callout, color = c.text, modifier = Modifier.weight(1f))
        if (count > 0) Text("%02d".format(count), style = CType.data, color = c.text)
    }
}

/** A chat in the sidebar: glyph and title; approval, work or unread on the right; long press for every action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidebarRow(s: ChatsState, row: SessionRow, needsApproval: Boolean, onDialog: (Dialog) -> Unit, onClick: () -> Unit) {
    val c = C.colors
    val view = LocalView.current
    val runState = s.running[row.id]
    val isRunning = runState != null && runState != "idle" && !needsApproval
    val unread = s.isUnread(row)
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { Haptic.Light.perform(view); onClick() }, onLongClick = { Haptic.Medium.perform(view); menu = true })
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(sourceStyle(row.source, c).first, null, tint = c.tertiary, modifier = Modifier.size(16.dp))
            Text(row.title, style = if (unread) CType.callout.medium() else CType.callout, color = if (unread) c.text else c.text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            s.botOf(row)?.let { Text(it, style = CType.footnote.medium(), color = c.secondary, maxLines = 1) }
            when {
                needsApproval -> Icon(AF.VerifiedUser, stringResource(R.string.chatsscreen_awaiting_approval), tint = c.attention, modifier = Modifier.size(14.dp))
                isRunning -> CircularProgressIndicator(Modifier.size(12.dp), color = c.text, strokeWidth = 1.5.dp)
                unread -> Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent))
                row.id in s.starred -> Icon(AF.Star, null, tint = c.tertiary, modifier = Modifier.size(11.dp))
            }
        }
        SessionMenu(menu, { menu = false }, s, row, unread, onDialog)
    }
}

/** Every action on one chat (long press on a row). */
@Composable
private fun SessionMenu(open: Boolean, onDismiss: () -> Unit, s: ChatsState, row: SessionRow, unread: Boolean, onDialog: (Dialog) -> Unit) {
    val c = C.colors
    DropdownMenu(open, onDismiss) {
        DropdownMenuItem({ Text(stringResource(if (row.pinned) R.string.chatsscreen_unpin else R.string.chatsscreen_pin)) }, { onDismiss(); s.setPinned(row, !row.pinned) }, leadingIcon = { Icon(AF.PushPin, null) })
        DropdownMenuItem({ Text(stringResource(if (row.id in s.starred) R.string.chatsscreen_unstar else R.string.chatsscreen_star)) }, { onDismiss(); s.toggleStar(row) }, leadingIcon = { Icon(AF.StarBorder, null) })
        DropdownMenuItem({ Text(stringResource(if (unread) R.string.chatsscreen_mark_read else R.string.chatsscreen_mark_unread)) }, { onDismiss(); if (unread) s.markSeen(row.id) else s.markUnread(row) },
            leadingIcon = { Icon(if (unread) AF.MarkEmailRead else AF.MarkEmailUnread, null) })
        DropdownMenuItem({ Text(stringResource(R.string.chatsscreen_rename_menu)) }, { onDismiss(); onDialog(Dialog.Rename(row)) }, leadingIcon = { Icon(AF.Edit, null) })
        DropdownMenuItem({ Text(stringResource(R.string.chatsscreen_move_menu)) }, { onDismiss(); onDialog(Dialog.Move(row)) }, leadingIcon = { Icon(AF.DriveFileMove, null) })
        DropdownMenuItem({ Text(stringResource(if (row.archived) R.string.chatsscreen_restore else R.string.chatsscreen_archive)) }, { onDismiss(); s.setArchived(row, !row.archived) },
            leadingIcon = { Icon(if (row.archived) AF.Unarchive else AF.Archive, null) })
        DropdownMenuItem({ Text(stringResource(R.string.chatsscreen_delete_menu), color = c.red) }, { onDismiss(); onDialog(Dialog.Delete(row)) }, leadingIcon = { Icon(AF.Delete, null, tint = c.red) })
    }
}

/** Projects, a page over the home chat: the list, or one project's conversations. */
@Composable
fun ProjectsScreen(graph: AppGraph, onBack: () -> Unit) {
    val s = graph.chats
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    LaunchedEffect(Unit) { s.loadProjects() }
    Column(Modifier.fillMaxSize()) {
        PageBar(onBack)
        val p = s.openProject
        if (p != null) ProjectPage(s, p, onOpen = { id, title -> graph.chat.open(id, title) }, onNewChat = { graph.chat.openNew(it) }, bottomInset = 0.dp) { dialog = it }
        else {
            ScreenHeader(stringResource(R.string.chatsscreen_spaces), stringResource(R.string.chatsscreen_projects), "%02d".format(s.projects.size))
            ProjectList(s, 0.dp) { dialog = it }
        }
    }
    Dialogs(s, dialog) { dialog = null }
}

@Composable
private fun FilterMenu(s: ChatsState, open: Boolean, onDismiss: () -> Unit) {
    val c = C.colors
    @Composable
    fun item(f: Filter, label: String, icon: ImageVector, count: Int? = null) {
        DropdownMenuItem(
            { Row { Text(label, modifier = Modifier.weight(1f)); count?.let { Text("$it", color = c.secondary) } } },
            { s.applyFilter(f); onDismiss() },
            leadingIcon = { Icon(icon, null, tint = if (s.filter == f) c.accent else c.secondary) },
        )
    }
    DropdownMenu(open, onDismiss) {
        item(Filter.All, stringResource(R.string.chatsscreen_filter_all), AF.Inbox)
        item(Filter.Starred, stringResource(R.string.chatsscreen_filter_starred), AF.StarBorder, s.listed.count { it.id in s.starred })
        HorizontalDivider()
        item(Filter.Unread, stringResource(R.string.chatsscreen_filter_unread), AF.Circle, s.listed.count(s::isUnread))
        item(Filter.Approval, stringResource(R.string.chatsscreen_filter_approval_menu), AF.VerifiedUser)
        item(Filter.Running, stringResource(R.string.chatsscreen_filter_running), AF.Sync, s.running.values.count { it != "idle" })
        HorizontalDivider()
        item(Filter.Voice, stringResource(R.string.chatsscreen_filter_voice), AF.GraphicEq)
        item(Filter.Scheduled, stringResource(R.string.chatsscreen_filter_scheduled), AF.Schedule)
        s.sources().forEach { src -> item(Filter.Source(src), src.replaceFirstChar { it.uppercase() }, sourceStyle(src, c).first, s.listed.count { it.source == src }) }
        HorizontalDivider()
        item(Filter.Archived, stringResource(R.string.chatsscreen_filter_archived), AF.Archive)
        DropdownMenuItem({ Text(stringResource(R.string.chatsscreen_mark_all_read)) }, { s.markAllRead(); onDismiss() }, leadingIcon = { Icon(AF.Drafts, null) })
    }
}

private fun filterLabel(f: Filter) = when (f) {
    Filter.All -> null
    Filter.Starred -> tr(R.string.chatsscreen_filter_starred)
    Filter.Unread -> tr(R.string.chatsscreen_filter_unread)
    Filter.Approval -> tr(R.string.chatsscreen_filter_approval)
    Filter.Running -> tr(R.string.chatsscreen_filter_running)
    Filter.Voice -> tr(R.string.chatsscreen_filter_voice)
    Filter.Scheduled -> tr(R.string.chatsscreen_filter_scheduled)
    Filter.Archived -> tr(R.string.chatsscreen_filter_archived)
    is Filter.Source -> f.name.replaceFirstChar { it.uppercase() }
}

@Composable
private fun SearchField(s: ChatsState, profile: String) {
    val c = C.colors
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(Radii.pill)
            .background(c.raised)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AF.Search, null, tint = c.secondary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        filterLabel(s.filter)?.let { label ->
            Row(
                Modifier.clip(CircleShape).background(c.accent).pressable(label = stringResource(R.string.chatsscreen_remove_filter)) { s.applyFilter(Filter.All) }.padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(label, style = CType.footnote.semibold(), color = c.onAccent)
                Icon(AF.Close, null, tint = c.onAccent, modifier = Modifier.size(11.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f)) {
            if (s.query.isEmpty()) Text(stringResource(R.string.chatsscreen_search_in, profile), style = CType.subheadline, color = c.secondary)
            BasicTextField(s.query, s::onQueryChange, singleLine = true, textStyle = CType.subheadline.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
        if (s.query.isNotEmpty()) Box(Modifier.size(32.dp).pressable(label = stringResource(R.string.chatsscreen_clear)) { s.onQueryChange("") }, contentAlignment = Alignment.Center) {
            Icon(AF.Cancel, null, tint = c.tertiary, modifier = Modifier.size(17.dp))
        }
    }
}

/** Glyph per session source; monochrome, the glyph alone tells them apart. */
fun sourceStyle(source: String, c: AfColors): Pair<ImageVector, Color> = when (source.lowercase()) {
    "voice", "voice_job", "voicejob" -> AF.GraphicEq
    "cron", "scheduled" -> AF.Schedule
    "telegram" -> AF.Send
    "discord" -> AF.ChatBubble
    "webhook" -> AF.Webhook
    "api" -> AF.Language
    else -> AF.ChatBubble
} to c.text

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowView(s: ChatsState, row: SessionRow, needsApproval: Boolean, onDialog: (Dialog) -> Unit, onClick: () -> Unit) {
    val c = C.colors
    val view = LocalView.current
    val (icon, tint) = sourceStyle(row.source, c)
    val runState = s.running[row.id]
    val isRunning = runState != null && runState != "idle" && !needsApproval
    val unread = s.isUnread(row)
    var menu by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { Haptic.Light.perform(view); onClick() }, onLongClick = { Haptic.Medium.perform(view); menu = true })
                .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconWell(icon, size = 38.dp, tint = tint)
                if (unread) Box(Modifier.align(Alignment.TopEnd).offset(x = 1.dp, y = (-1).dp).size(11.dp).clip(CircleShape).background(c.canvas).padding(2.dp).clip(CircleShape).background(c.accent))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(row.title, style = CType.callout.medium(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (row.id in s.starred) Icon(AF.Star, null, tint = c.accent, modifier = Modifier.size(11.dp))
                    if (row.updated > 0) Text(
                        ago(row.updated),
                        style = CType.data, color = c.tertiary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    when {
                        needsApproval -> Icon(AF.VerifiedUser, null, tint = c.attention, modifier = Modifier.size(14.dp))
                        isRunning -> CircularProgressIndicator(Modifier.size(12.dp), color = c.accent, strokeWidth = 1.5.dp)
                        row.pinned -> Icon(AF.PushPin, null, tint = c.secondary, modifier = Modifier.size(12.dp))
                    }
                    s.botOf(row)?.let { Text(it, style = CType.subheadline.medium(), color = c.text, maxLines = 1) }
                    Text(row.preview ?: " ", style = CType.subheadline, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when {
                        needsApproval -> Text(stringResource(R.string.chatsscreen_approve), style = CType.footnote.medium(), color = c.attention)
                        isRunning -> Text(stringResource(R.string.chatsscreen_working), style = CType.footnote.medium(), color = c.accent)
                    }
                }
            }
            SessionMenu(menu, { menu = false }, s, row, unread, onDialog)
        }
        Divider(inset = 66.dp)
    }
}

/** Snippet with Hermes' `>>>match<<<` marks shown as accent semibold runs. */
@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    val c = C.colors
    val snippet = buildAnnotatedString {
        var rest = hit.snippet.replace('\n', ' ')
        while (true) {
            val a = rest.indexOf(">>>")
            val b = if (a >= 0) rest.indexOf("<<<", a + 3) else -1
            if (a < 0 || b < 0) { append(rest); break }
            append(rest.substring(0, a))
            withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.SemiBold)) { append(rest.substring(a + 3, b)) }
            rest = rest.substring(b + 3)
        }
    }
    Column(Modifier.fillMaxWidth().pressable(haptic = Haptic.Light, onClick = onClick).padding(start = 66.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
        Text(hit.title ?: "Chat", style = CType.subheadline.semibold(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(snippet, style = CType.footnote, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Problem(text: String) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AF.WifiOff, null, tint = c.red, modifier = Modifier.size(16.dp))
        Text(text, style = CType.footnote.medium(), color = c.red)
    }
}

// ---- projects ----

@Composable
private fun ProjectList(s: ChatsState, bottomInset: Dp, onDialog: (Dialog) -> Unit) {
    val c = C.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomInset + 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.chatsscreen_projects), style = CType.title3.semibold(), color = c.text, modifier = Modifier.weight(1f))
            SettingsButton(stringResource(R.string.chatsscreen_new_project)) { onDialog(Dialog.NewProject) }
        } }
        s.projectsError?.let { item { Text(it, style = CType.footnote, color = c.red) } }
        items(s.projects, key = { it.id }) { p -> ProjectTile(s, p, onDialog) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectTile(s: ChatsState, p: Project, onDialog: (Dialog) -> Unit) {
    val c = C.colors
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).combinedClickable(onClick = { s.openProjectPage(p) }, onLongClick = { if (!p.isHome) menu = true }).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(AF.Folder, null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (p.isHome) stringResource(R.string.chatsscreen_no_project) else p.title, style = CType.callout.semibold(), color = c.text)
                p.path?.let { Text(it, style = CType.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text("${p.count}", style = CType.footnote, color = c.secondary)
        }
        p.previews.take(3).forEach { Text("• ${it.title}", style = CType.footnote, color = c.text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(stringResource(R.string.chatsscreen_rename_menu)) }, { menu = false; onDialog(Dialog.RenameProject(p)) }, leadingIcon = { Icon(AF.Edit, null) })
            DropdownMenuItem({ Text(stringResource(R.string.chatsscreen_remove_project_menu), color = c.red) }, { menu = false; onDialog(Dialog.DeleteProject(p)) }, leadingIcon = { Icon(AF.Delete, null, tint = c.red) })
        }
    }
}

@Composable
private fun ProjectPage(s: ChatsState, p: Project, onOpen: (String, String) -> Unit, onNewChat: (String?) -> Unit, bottomInset: Dp, onDialog: (Dialog) -> Unit) {
    val c = C.colors
    androidx.activity.compose.BackHandler { s.openProject = null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (p.isHome) stringResource(R.string.chatsscreen_no_project) else p.title, style = CType.title3.semibold(), color = c.text)
                    p.path?.let { Text(it, style = CType.caption, color = c.secondary) }
                }
                SettingsButton(stringResource(R.string.chatsscreen_new_conversation), prominent = true) { onNewChat(p.path) }
            }
        }
        s.lanes.forEach { lane ->
            item(key = "lane-" + lane.id) { SectionHeader(lane.title, lane.sessions.size, collapsed = false) {} }
            items(lane.sessions, key = { lane.id + it.id }) { row -> SessionRowView(s, row, false, onDialog) { onOpen(row.id, row.title) } }
        }
        if (s.lanes.isEmpty()) item { Text(stringResource(R.string.chatsscreen_project_empty), style = CType.subheadline, color = c.secondary, modifier = Modifier.padding(16.dp)) }
    }
}

// ---- dialogs ----

@Composable
private fun Dialogs(s: ChatsState, d: Dialog?, onDismiss: () -> Unit) {
    val c = C.colors
    when (d) {
        null -> {}
        is Dialog.Rename -> TextDialog(stringResource(R.string.chatsscreen_rename_chat), d.row.title, stringResource(R.string.chatsscreen_title_label), onDismiss) { s.rename(d.row, it) }
        is Dialog.RenameProject -> TextDialog(stringResource(R.string.chatsscreen_rename_project), d.p.title, stringResource(R.string.chatsscreen_name), onDismiss) { s.renameProject(d.p, it) }
        is Dialog.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.chatsscreen_delete_title, d.row.title)) },
            text = { Text(stringResource(R.string.chatsscreen_delete_text)) },
            confirmButton = { TextButton({ onDismiss(); s.delete(d.row) }) { Text(stringResource(R.string.chatsscreen_delete), color = c.red) } },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.chatsscreen_cancel)) } },
        )
        is Dialog.DeleteProject -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.chatsscreen_remove_project_title, d.p.title)) },
            text = { Text(stringResource(R.string.chatsscreen_remove_project_text)) },
            confirmButton = { TextButton({ onDismiss(); s.deleteProject(d.p) }) { Text(stringResource(R.string.chatsscreen_remove), color = c.red) } },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.chatsscreen_cancel)) } },
        )
        is Dialog.Move -> {
            LaunchedEffect(Unit) { if (s.projects.isEmpty()) s.loadProjects() }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.chatsscreen_move_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val targets = s.projects.filter { !it.isHome && it.path != null }
                        if (targets.isEmpty()) Text(stringResource(R.string.chatsscreen_no_folder_projects), color = c.secondary)
                        targets.forEach { p ->
                            Text(p.title, style = CType.body, color = c.text, modifier = Modifier.fillMaxWidth().pressable { onDismiss(); s.moveTo(d.row, p) }.padding(vertical = 10.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.chatsscreen_cancel)) } },
            )
        }
        Dialog.NewProject -> {
            var name by remember { mutableStateOf("") }
            var folder by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.chatsscreen_new_project)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AfField(name, { name = it }, stringResource(R.string.chatsscreen_name), stringResource(R.string.chatsscreen_project_name_hint))
                        AfField(folder, { folder = it }, stringResource(R.string.chatsscreen_host_folder), stringResource(R.string.chatsscreen_host_folder_hint))
                    }
                },
                confirmButton = { TextButton({ onDismiss(); s.createProject(name, folder) }, enabled = name.isNotBlank() && folder.isNotBlank()) { Text(stringResource(R.string.chatsscreen_create)) } },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.chatsscreen_cancel)) } },
            )
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, label: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { AfField(value, { value = it }, label, initial) },
        confirmButton = { TextButton({ onDismiss(); onSave(value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.chatsscreen_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.chatsscreen_cancel)) } },
    )
}
