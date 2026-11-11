package app.hermesandroid.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.chat.AfMarkdown
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.ui.components.AfField
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.shell.CapsuleIcon
import app.hermesandroid.ui.shell.TopBar
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.AfColors
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.glass
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.shell.PageBar
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.Pill
import app.hermesandroid.ui.components.ScanLine
import app.hermesandroid.ui.components.ScreenHeader
import app.hermesandroid.ui.components.SegmentBar
import app.hermesandroid.ui.components.UnderlineTabs
import app.hermesandroid.ui.theme.Radii
import androidx.compose.foundation.layout.height

// Tasks tab: segmented Board | Scheduled.

data class StatusStyle(val label: String, val icon: ImageVector, val tint: Color)

@Composable
fun statusStyle(s: String, c: AfColors) = when (s) {
    "triage" -> StatusStyle(stringResource(R.string.tasksscreen_status_triage), AF.Inbox, c.secondary)
    "todo" -> StatusStyle(stringResource(R.string.tasksscreen_status_todo), AF.RadioButtonUnchecked, c.secondary)
    "scheduled" -> StatusStyle(stringResource(R.string.tasksscreen_status_scheduled), AF.Schedule, c.secondary)
    "ready" -> StatusStyle(stringResource(R.string.tasksscreen_status_ready), AF.PlayCircle, c.text)
    "running" -> StatusStyle(stringResource(R.string.tasksscreen_status_running), AF.Sync, c.success)
    "blocked" -> StatusStyle(stringResource(R.string.tasksscreen_status_blocked), AF.Lock, c.red)
    "review" -> StatusStyle(stringResource(R.string.tasksscreen_status_review), AF.Visibility, c.attention)
    "done" -> StatusStyle(stringResource(R.string.tasksscreen_status_done), AF.CheckCircle, c.secondary)
    "archived" -> StatusStyle(stringResource(R.string.tasksscreen_status_archived), AF.Archive, c.secondary)
    else -> StatusStyle(s.replace('_', ' ').replaceFirstChar { it.uppercase() }, AF.Circle, c.secondary)
}

@Composable
fun TasksScreen(graph: AppGraph, bottomInset: Dp, onBack: () -> Unit, onOpenChat: (String, String, String?) -> Unit) {
    val s = graph.tasks
    val c = C.colors
    var newTask by remember { mutableStateOf(false) }
    var editJob by remember { mutableStateOf<CronJob?>(null) }
    var newJob by remember { mutableStateOf(false) }
    var jobEditor by remember { mutableStateOf<CronJob?>(null) }
    LaunchedEffect(s.page) {
        // Live events while the board is on screen; polling every 8 s without them, every minute as a safety net with them.
        if (s.page == TasksPage.Board) {
            s.loadBoards(); s.watchBoard()
            try { while (true) { delay(if (s.live) 60_000 else 8_000); s.loadBoard() } } finally { s.stopWatching() }
        } else s.loadJobs()
    }
    LaunchedEffect(s.showJob, s.jobs.size) {
        s.jobs.firstOrNull { it.id == s.showJob }?.let { editJob = it; s.showJob = null }
    }
    Column(Modifier.fillMaxSize()) {
        PageBar(
            onBack,
            trailing = {
                if (s.page == TasksPage.Board) CapsuleIcon(AF.Bolt, stringResource(R.string.tasksscreen_nudge)) { s.nudge() }
                CapsuleIcon(AF.Add, if (s.page == TasksPage.Board) stringResource(R.string.tasksscreen_new_task) else stringResource(R.string.tasksscreen_new_scheduled)) { if (s.page == TasksPage.Board) newTask = true else newJob = true }
            },
        )
        if (s.page == TasksPage.Board) {
            val total = s.columns.sumOf { it.tasks.size }
            val done = s.columns.firstOrNull { it.name == "done" }?.tasks?.size ?: 0
            ScreenHeader(stringResource(R.string.tasksscreen_board), stringResource(R.string.tasksscreen_tasks), "%02d / %02d".format(done, total))
            SegmentBar(done, total, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
        } else ScreenHeader("Cron", stringResource(R.string.tasksscreen_scheduled), "%02d".format(s.jobs.size))
        UnderlineTabs(listOf(stringResource(R.string.tasksscreen_board), if (s.unread.isEmpty()) stringResource(R.string.tasksscreen_scheduled) else stringResource(R.string.tasksscreen_scheduled_unread, s.unread.size)), s.page.ordinal, Modifier.padding(bottom = 12.dp)) { s.page = TasksPage.entries[it] }
        s.notice?.let { msg ->
            LaunchedEffect(msg) { delay(6000); s.notice = null }
            Text(msg, style = CType.footnote.medium(), color = c.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().settingsCard(Radii.lg).padding(horizontal = 16.dp, vertical = 12.dp))
        }
        when (s.page) {
            TasksPage.Board -> Board(s, bottomInset)
            TasksPage.Scheduled -> Scheduled(s, bottomInset) { editJob = it }
        }
    }
    s.detail?.let { TaskSheet(s, it, graph.profiles) }
    if (newTask) NewTaskDialog(s, s.assignees + graph.profiles) { newTask = false }
    editJob?.let { JobSheet(s, it, onOpenChat, onTalk = { text -> graph.chat.openNew(); graph.chat.quote(text) }, onEdit ={ j -> editJob = null; newJob = false; jobEditor = j }) { editJob = null } }
    if (newJob) JobEditor(s, null, profileTitle(graph.profile)) { newJob = false }
    jobEditor?.let { j -> JobEditor(s, j, profileTitle(graph.profile)) { jobEditor = null } }
}

/** Segmented control: a graphite track with the selected option raised. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = C.colors
    Row(modifier.fillMaxWidth().clip(Radii.md).background(c.raised).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(Radii.sm)
                    .background(if (sel) c.text.copy(alpha = 0.12f) else Color.Transparent)
                    .pressable(haptic = Haptic.Selection) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = CType.subheadline.medium(), color = if (sel) c.text else c.secondary) }
        }
    }
}

// ---- Board ----

@Composable
private fun Board(s: TasksState, bottomInset: Dp) {
    val c = C.colors
    Column(Modifier.fillMaxSize()) {
        if (s.boards.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.boards.forEach { b ->
                val sel = b.slug == s.board
                Pill(b.name, sel) { s.selectBoard(b.slug) }
            }
        }
        // A chip per helper bot with tasks on this board: what each one is working on.
        var who by remember(s.board) { mutableStateOf<String?>(null) }
        val assignees = s.columns.flatMap { it.tasks }.mapNotNull { it.assignee }.distinct().sorted()
        if (assignees.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.tasksscreen_all), who == null) { who = null }
            assignees.forEach { a -> Pill(profileTitle(a), who == a) { who = if (who == a) null else a } }
        }
        s.boardError?.let { Text(it, style = CType.footnote, color = c.red, modifier = Modifier.padding(16.dp)) }
        if (s.columns.isEmpty() && s.loadingBoard) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        // One vertical list: a section per status in the order they matter, empty ones hidden, finished ones folded.
        val open = remember { androidx.compose.runtime.mutableStateListOf<String>() }
        val cols = s.columns.map { col -> if (who == null) col else col.copy(tasks = col.tasks.filter { it.assignee == who }) }.filter { it.tasks.isNotEmpty() }.sortedBy { ORDER.indexOf(it.name).let { i -> if (i < 0) ORDER.size else i } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (cols.isEmpty() && !s.loadingBoard) item { Text(stringResource(R.string.tasksscreen_no_tasks), style = CType.subheadline, color = c.secondary, modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp)) }
            cols.forEach { col ->
                val foldable = col.name in FOLDED
                val folded = foldable && col.name !in open
                item(key = "h-" + col.name) { StatusHeader(col, foldable, folded) { if (col.name in open) open.remove(col.name) else open.add(col.name) } }
                if (!folded) items(col.tasks, key = { it.id }) { t -> TaskCard(t) { s.openTask(t.id) } }
            }
        }
    }
}

/** Statuses in the order they matter on the board; finished ones start folded. */
private val ORDER = listOf("running", "blocked", "review", "ready", "todo", "triage", "scheduled", "done", "archived")
private val FOLDED = setOf("done", "archived")

@Composable
private fun StatusHeader(col: KColumn, foldable: Boolean, folded: Boolean, onToggle: () -> Unit) {
    val c = C.colors
    val st = statusStyle(col.name, c)
    Row(
        Modifier.fillMaxWidth().then(if (foldable) Modifier.pressable(haptic = Haptic.Selection, onClick = onToggle) else Modifier).padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(st.icon, null, tint = st.tint, modifier = Modifier.size(13.dp))
        Eyebrow(st.label, Modifier.weight(1f))
        Text("%02d".format(col.tasks.size), style = CType.data, color = c.tertiary)
        if (foldable) Icon(if (folded) AF.Forward else AF.ExpandMore, null, tint = c.tertiary, modifier = Modifier.size(14.dp))
    }
}


@Composable
private fun TaskCard(t: KTask, onClick: () -> Unit) {
    val c = C.colors
    val st = statusStyle(t.status, c)
    Column(Modifier.fillMaxWidth().clip(Radii.md).background(c.surface).pressable(haptic = Haptic.Light, onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(top = 6.dp).size(6.dp).clip(CircleShape).background(st.tint))
            Text(t.title, style = CType.subheadline.medium(), color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val meta = listOfNotNull(t.assignee?.let(::profileTitle), if (t.priority > 0) "P${t.priority}" else null, if (t.comments > 0) pluralStringResource(R.plurals.tasksscreen_comments, t.comments, t.comments) else null).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = CType.data, color = c.tertiary)
        (t.failure ?: t.summary)?.let { Text(it, style = CType.caption, color = if (t.failure != null) c.red else c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (t.status == "running") ScanLine(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun TaskSheet(s: TasksState, d: KDetail, profiles: List<String>) {
    val c = C.colors
    val t = d.task
    var statusMenu by remember { mutableStateOf(false) }
    var assignMenu by remember { mutableStateOf(false) }
    var reply by remember(t.id) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    AfSheet(s::closeTask) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val st = statusStyle(t.status, c)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(st.icon, null, tint = st.tint, modifier = Modifier.size(16.dp))
                Text(st.label.uppercase(), style = CType.caption2.semibold(), color = st.tint)
                Spacer(Modifier.weight(1f))
                Text(t.id, style = CType.caption2.copy(fontFamily = CType.mono), color = c.tertiary)
            }
            Text(t.title, style = CType.title3.semibold(), color = c.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    SettingsButton(stringResource(R.string.tasksscreen_status_button, st.label)) { statusMenu = true }
                    DropdownMenu(statusMenu, { statusMenu = false }) {
                        TasksState.MANUAL.forEach { v -> DropdownMenuItem({ Text(statusStyle(v, c).label) }, { statusMenu = false; s.setStatus(t, v) }, leadingIcon = { Icon(statusStyle(v, c).icon, null) }) }
                    }
                }
                Box {
                    SettingsButton(stringResource(R.string.tasksscreen_assigned, t.assignee?.let(::profileTitle) ?: stringResource(R.string.tasksscreen_nobody))) { assignMenu = true }
                    DropdownMenu(assignMenu, { assignMenu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.tasksscreen_unassigned)) }, { assignMenu = false; s.reassign(t, null) })
                        profiles.distinct().forEach { p -> DropdownMenuItem({ Text(profileTitle(p)) }, { assignMenu = false; s.reassign(t, p) }) }
                    }
                }
                SettingsButton(stringResource(R.string.tasksscreen_priority, t.priority)) { s.setPriority(t, (t.priority + 1) % 4) }
            }
            t.failure?.let { Text(stringResource(R.string.tasksscreen_last_failure, it), style = CType.footnote, color = c.red) }
            t.result?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.tasksscreen_result), style = CType.footnote.semibold(), color = c.secondary)
                Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(18.dp)).padding(14.dp)) { AfMarkdown(it, 0.9f) }
            }
            // Workers often finish with only a run summary and a file: newest run first.
            val summaries = d.runs.filter { it.summary != null || it.error != null }.asReversed()
            if (summaries.isNotEmpty()) {
                Text(stringResource(R.string.tasksscreen_runs), style = CType.footnote.semibold(), color = c.secondary)
                summaries.forEach { r ->
                    Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val head = listOfNotNull(r.profile, r.outcome, r.endedAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000)) })
                        if (head.isNotEmpty()) Text(head.joinToString(" · "), style = CType.caption.semibold(), color = c.secondary)
                        r.summary?.let { AfMarkdown(it, 0.85f) }
                        r.error?.let { Text(it, style = CType.footnote, color = c.red) }
                    }
                }
            }
            if (d.attachments.isNotEmpty()) {
                val ctx = LocalContext.current
                Text(stringResource(R.string.tasksscreen_files), style = CType.footnote.semibold(), color = c.secondary)
                d.attachments.forEach { a ->
                    SettingsButton(a.name + (a.size?.let { " · " + Formatter.formatShortFileSize(ctx, it) } ?: ""), AF.AttachFile) { s.openAttachment(a, ctx) }
                    s.preview?.takeIf { it.first == a.id }?.let { (_, text) ->
                        // Plain text keeps its line breaks (Markdown would join them); bare URLs stay tappable.
                        Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(16.dp)).padding(12.dp)) {
                            AfMarkdown(if (a.name.endsWith(".md", true)) text else text.replace("\n", "  \n"), 0.85f)
                        }
                    }
                }
            }
            // The instructions come after what the task produced, folded (they're often a page long).
            t.body?.takeIf { it.isNotBlank() }?.let { b ->
                var open by remember(t.id) { mutableStateOf(t.status !in setOf("done", "archived")) }
                Text(stringResource(R.string.tasksscreen_instructions), style = CType.footnote.semibold(), color = c.secondary)
                Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(18.dp)).pressable { open = !open }.padding(14.dp)) {
                    if (open) AfMarkdown(b, 0.9f)
                    else Text(stringResource(R.string.tasksscreen_see_all, b.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().take(140)), style = CType.subheadline, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (t.status == "triage") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(stringResource(R.string.tasksscreen_specify)) { s.triageAction(t, "specify") }
                SettingsButton(stringResource(R.string.tasksscreen_decompose)) { s.triageAction(t, "decompose") }
            }
            if (t.status == "running") SettingsButton(stringResource(R.string.tasksscreen_reclaim)) { s.reclaim(t) }
            Text(stringResource(R.string.tasksscreen_comments_title), style = CType.footnote.semibold(), color = c.secondary)
            d.comments.forEach { cm ->
                Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(16.dp)).padding(12.dp)) {
                    Text(cm.author + (cm.createdAt?.let { " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000)) } ?: ""), style = CType.caption.semibold(), color = c.secondary)
                    Text(cm.body, style = CType.subheadline, color = c.text)
                }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.text.copy(alpha = 0.05f)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (reply.isEmpty()) Text(if (t.status == "blocked") stringResource(R.string.tasksscreen_reply_unblock) else stringResource(R.string.tasksscreen_comment_hint), style = CType.subheadline, color = c.tertiary)
                    BasicTextField(reply, { reply = it }, textStyle = CType.subheadline.copy(color = c.text), cursorBrush = SolidColor(c.accent))
                }
                Box(Modifier.size(42.dp).pressable(enabled = reply.isNotBlank() || t.status == "blocked") {
                    if (t.status == "blocked") s.replyAndUnblock(t, reply) else s.comment(t, reply); reply = ""
                }, contentAlignment = Alignment.Center) { Icon(AF.Send, stringResource(R.string.tasksscreen_send), tint = c.accent, modifier = Modifier.size(18.dp)) }
            }
            if (d.runs.isNotEmpty() || t.workerPid != null) {
                SettingsButton(stringResource(R.string.tasksscreen_worker_log)) { s.loadLog(t.id) }
                s.log?.let { Text(it.takeLast(6000), style = CType.caption2.copy(fontFamily = CType.mono), color = c.text,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(12.dp)).background(c.text.copy(alpha = 0.06f)).verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp)) }
            }
            if (d.events.isNotEmpty()) Text(stringResource(R.string.tasksscreen_history, d.events.takeLast(6).joinToString(" → ") { it.kind }), style = CType.caption, color = c.secondary)
            SettingsButton(stringResource(R.string.tasksscreen_delete_task), AF.Delete) { confirmDelete = true }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.tasksscreen_delete_title, t.title)) },
        text = { Text(stringResource(R.string.tasksscreen_delete_task_text)) },
        confirmButton = { TextButton({ confirmDelete = false; s.delete(t) }) { Text(stringResource(R.string.tasksscreen_delete), color = c.red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.tasksscreen_cancel)) } },
    )
}

@Composable
private fun NewTaskDialog(s: TasksState, assignees: List<String>, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var assignee by remember { mutableStateOf<String?>(null) }
    var triage by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val c = C.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tasksscreen_new_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AfField(title, { title = it }, stringResource(R.string.tasksscreen_title), stringResource(R.string.tasksscreen_title_hint))
                AfField(body, { body = it }, stringResource(R.string.tasksscreen_details), stringResource(R.string.tasksscreen_optional))
                Box {
                    SettingsButton(stringResource(R.string.tasksscreen_assign_to, assignee?.let(::profileTitle) ?: stringResource(R.string.tasksscreen_dispatcher_decides_inline))) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.tasksscreen_dispatcher_decides)) }, { menu = false; assignee = null })
                        assignees.distinct().forEach { p -> DropdownMenuItem({ Text(profileTitle(p)) }, { menu = false; assignee = p }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tasksscreen_triage_first), style = CType.subheadline, color = c.text, modifier = Modifier.weight(1f))
                    Switch(triage, { triage = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                }
            }
        },
        confirmButton = { TextButton({ onDismiss(); s.create(title, body, assignee, 0, triage) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.tasksscreen_create)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.tasksscreen_cancel)) } },
    )
}

// ---- Scheduled ----

@Composable
private fun Scheduled(s: TasksState, bottomInset: Dp, onOpen: (CronJob) -> Unit) {
    val c = C.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 16.dp)) {
        s.jobsError?.let { item { Text(it, style = CType.footnote, color = c.red) } }
        if (s.jobs.isEmpty() && s.jobsError == null) item { Text(stringResource(R.string.tasksscreen_no_jobs), style = CType.subheadline, color = c.secondary, modifier = Modifier.padding(vertical = 16.dp)) }
        items(s.jobs, key = { it.id }) { j ->
            val next = j.nextRun?.let(::epochOf)
            val unread = stringResource(R.string.tasksscreen_unread_result)
            Column(Modifier.fillMaxWidth().pressable(haptic = Haptic.Light) { onOpen(j) }.padding(vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(j.name, style = CType.callout.medium(), color = if (j.enabled) c.text else c.secondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (j.id in s.unread) Box(Modifier.padding(end = 8.dp).size(6.dp).clip(CircleShape).background(c.accent).semantics { contentDescription = unread })
                    if (j.lastStatus == "error" || j.lastError != null) Icon(AF.Report, stringResource(R.string.tasksscreen_failed), tint = c.red, modifier = Modifier.padding(end = 8.dp).size(14.dp))
                    if (!j.enabled) Text(stringResource(R.string.tasksscreen_paused), style = CType.data, color = c.attention)
                    else next?.let { Text(clock(it), style = CType.data, color = c.text) }
                }
                Text(listOfNotNull(j.bot, j.schedule).joinToString(" · "), style = CType.footnote, color = c.secondary)
                if (j.enabled && next != null) DayScale(next, Modifier.padding(top = 4.dp))
            }
            Divider(inset = 0.dp)
        }
    }
}

/** The helper bot that owns a job, when it isn't the agent itself. */
private val CronJob.bot get() = profile?.takeIf { it != "default" }?.let(::profileTitle)

private fun epochOf(iso: String): Long? = runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

/** Time of day for a run in the next 24 h, else the date and time. */
private fun clock(t: Long): String =
    (if (t - System.currentTimeMillis() < 86_400_000) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)).format(Date(t))

/** The coming 24 hours as hairline ticks: a hollow mark for now (left edge), a bright one where the next run falls. */
@Composable
private fun DayScale(next: Long, modifier: Modifier = Modifier) {
    val c = C.colors
    val f = ((next - System.currentTimeMillis()) / 86_400_000f).coerceIn(0f, 1f)
    androidx.compose.foundation.Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val mid = size.height / 2
        for (h in 0..24) {
            val x = size.width * h / 24f
            val tall = if (h % 6 == 0) 4.dp.toPx() else 2.dp.toPx()
            drawLine(c.text.copy(alpha = 0.18f), androidx.compose.ui.geometry.Offset(x, mid - tall), androidx.compose.ui.geometry.Offset(x, mid + tall), 1.dp.toPx())
        }
        drawCircle(c.secondary, 3.dp.toPx(), androidx.compose.ui.geometry.Offset(3.dp.toPx(), mid), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        val x = (size.width * f).coerceIn(1.dp.toPx(), size.width - 1.dp.toPx())
        drawLine(c.text, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 2.dp.toPx())
    }
}

/** ISO timestamps from jobs.json → local short date-time. */
private fun shortTime(iso: String): String =
    epochOf(iso)?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: iso

@Composable
private fun JobSheet(s: TasksState, j: CronJob, onOpenChat: (String, String, String?) -> Unit, onTalk: (String) -> Unit, onEdit: (CronJob) -> Unit, onDismiss: () -> Unit) {
    val c = C.colors
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(j.id) { s.loadRuns(j); s.markRead(j.id) }
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(j.name, style = CType.title3.semibold(), color = c.text)
            j.bot?.let { Text(stringResource(R.string.tasksscreen_run_by, it), style = CType.subheadline.medium(), color = c.text) }
            Text(j.schedule + (j.expr?.takeIf { it != j.schedule }?.let { "  ($it)" } ?: ""), style = CType.subheadline, color = c.secondary)
            j.nextRun?.let { Text(stringResource(R.string.tasksscreen_next_run, shortTime(it)), style = CType.footnote, color = c.scheduled) }
            j.lastRun?.let { Text(stringResource(R.string.tasksscreen_last_run, shortTime(it), j.lastStatus ?: ""), style = CType.footnote, color = c.secondary) }
            j.lastError?.let { Text(it, style = CType.footnote, color = c.red) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(stringResource(R.string.tasksscreen_run_now), AF.PlayArrow, prominent = true) { s.jobAction(j, "trigger") }
                SettingsButton(if (j.enabled) stringResource(R.string.tasksscreen_pause) else stringResource(R.string.tasksscreen_resume), if (j.enabled) AF.Pause else AF.PlayArrow) { s.jobAction(j, if (j.enabled) "pause" else "resume"); onDismiss() }
                SettingsButton(stringResource(R.string.tasksscreen_edit), AF.Edit) { onEdit(j) }
                SettingsButton(stringResource(R.string.tasksscreen_delete), AF.Delete) { confirmDelete = true }
            }
            // What the job produced comes first; its (often long) instructions stay folded.
            Text(stringResource(R.string.tasksscreen_recent_runs), style = CType.footnote.semibold(), color = c.secondary)
            if (s.runs.isEmpty()) Text(stringResource(R.string.tasksscreen_no_runs), style = CType.footnote, color = c.tertiary)
            // The newest run opens with its result; the others expand on tap.
            var expanded by remember(j.id) { mutableStateOf<String?>(null) }
            LaunchedEffect(s.runs.firstOrNull()?.id) { expanded = s.runs.firstOrNull()?.id }
            var all by remember(j.id) { mutableStateOf(false) }
            (if (all) s.runs else s.runs.take(5)).forEach { r ->
                val open = expanded == r.id
                if (open) LaunchedEffect(r.id) { s.loadResult(j, r) }
                Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(16.dp)).pressable { expanded = if (open) null else r.id }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.startedAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)) } ?: r.title ?: j.name,
                        style = CType.subheadline.semibold(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (open) {
                        if (r.id !in s.results) Text(stringResource(R.string.tasksscreen_loading), style = CType.footnote, color = c.tertiary)
                        else s.results[r.id]?.let { res ->
                            res.title?.let { Text(it, style = CType.headline, color = c.text) }
                            AfMarkdown(res.body, 0.85f)
                            // Like a Pulse card's: a new chat with the result quoted in the composer; nothing is sent.
                            SettingsButton(stringResource(R.string.tasksscreen_talk_about), AF.ChatBubble) { onDismiss(); onTalk("${res.title ?: j.name}\n\n${res.body}") }
                        } ?: Text(stringResource(R.string.tasksscreen_no_result), style = CType.footnote, color = c.tertiary)
                        Text(stringResource(R.string.tasksscreen_full_chat), style = CType.footnote.semibold(), color = c.accent,
                            modifier = Modifier.pressable { onDismiss(); onOpenChat(r.id, r.title ?: j.name, j.profile) }.padding(vertical = 4.dp))
                    }
                }
            }
            if (!all && s.runs.size > 5) SettingsButton(stringResource(R.string.tasksscreen_all_runs, s.runs.size)) { all = true }
            var showPrompt by remember(j.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(18.dp)).pressable { showPrompt = !showPrompt }.padding(14.dp)) {
                Text(stringResource(R.string.tasksscreen_instructions), style = CType.footnote.semibold(), color = c.secondary)
                Text(j.prompt, style = CType.subheadline, color = c.text, maxLines = if (showPrompt) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.tasksscreen_delete_title, j.name)) },
        text = { Text(stringResource(R.string.tasksscreen_delete_job_text)) },
        confirmButton = { TextButton({ confirmDelete = false; onDismiss(); s.deleteJob(j) }) { Text(stringResource(R.string.tasksscreen_delete), color = c.red) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.tasksscreen_cancel)) } },
    )
}

/** CronJobEditorSheet: name, what to do, when (Hermes' schedule text or a cron expression), where to deliver. */
@Composable
private fun JobEditor(s: TasksState, original: CronJob?, agent: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(original?.prompt.orEmpty()) }
    var schedule by remember { mutableStateOf(original?.expr ?: original?.schedule.orEmpty()) }
    var deliver by remember { mutableStateOf(original?.deliver ?: "origin") }
    val c = C.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original == null) stringResource(R.string.tasksscreen_new_job) else stringResource(R.string.tasksscreen_edit_title, original.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AfField(name, { name = it }, stringResource(R.string.tasksscreen_name), stringResource(R.string.tasksscreen_name_hint))
                AfField(prompt, { prompt = it }, stringResource(R.string.tasksscreen_job_prompt, agent), stringResource(R.string.tasksscreen_job_prompt_hint))
                AfField(schedule, { schedule = it }, stringResource(R.string.tasksscreen_when), "every day 9am · 30m · 0 9 * * 1")
                Text(stringResource(R.string.tasksscreen_schedule_help), style = CType.caption, color = c.secondary)
                AfField(deliver, { deliver = it }, stringResource(R.string.tasksscreen_deliver), "origin · telegram · discord")
            }
        },
        confirmButton = { TextButton({ onDismiss(); s.saveJob(original, name, prompt, schedule, deliver) }, enabled = name.isNotBlank() && prompt.isNotBlank() && schedule.isNotBlank()) { Text(stringResource(R.string.tasksscreen_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.tasksscreen_cancel)) } },
    )
}
