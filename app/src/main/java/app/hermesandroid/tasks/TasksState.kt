package app.hermesandroid.tasks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.trPlural
import app.hermesandroid.ui.shell.defaultProfileTitle
import app.hermesandroid.chat.openDataUrl
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import java.net.URLEncoder
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// Kanban (`/api/plugins/kanban/*`) and scheduled jobs (`/api/cron/*`).

data class KTask(
    val id: String,
    val title: String,
    val body: String?,
    val status: String,
    val assignee: String?,
    val priority: Int,
    val summary: String?,
    val comments: Int,
    val result: String?,
    val failure: String?,
    val workerPid: Int?,
    val createdAt: Long?,
)

data class KColumn(val name: String, val tasks: List<KTask>)
data class KBoard(val slug: String, val name: String, val total: Int?)
data class KComment(val author: String, val body: String, val createdAt: Long?)
data class KEvent(val kind: String, val createdAt: Long?)
data class KRun(val profile: String?, val outcome: String?, val summary: String?, val error: String?, val endedAt: Long?)
data class KAttachment(val id: Long, val name: String, val path: String?, val size: Long?)
data class KDetail(val task: KTask, val comments: List<KComment>, val events: List<KEvent>, val runs: List<KRun>, val attachments: List<KAttachment>)

data class CronJob(
    val id: String,
    val name: String,
    val prompt: String,
    val schedule: String,
    val expr: String?,
    val enabled: Boolean,
    val state: String?,
    val nextRun: String?,
    val lastRun: String?,
    val lastStatus: String?,
    val lastError: String?,
    val deliver: String?,
    /** Owning profile: the cross-profile list (default asks for `all`) mixes the helper bots' jobs in. */
    val profile: String? = null,
)

data class CronRun(val id: String, val title: String?, val preview: String?, val startedAt: Long?)

private val CRON_RUN = Regex("^cron_([0-9a-f]+)_\\d{8}_\\d{6}$")

/** The job a scheduled run's session belongs to (cron_<job>_<date>_<time>), else null. */
fun cronJobOf(session: String?): String? = session?.let { CRON_RUN.find(it)?.groupValues?.get(1) }

enum class TasksPage { Board, Scheduled }

class TasksState(private val graph: AppGraph) {
    private val client = graph.client
    var page by mutableStateOf(TasksPage.Board)

    // ---- Kanban ----
    val boards = mutableStateListOf<KBoard>()
    var board by mutableStateOf<String?>(null)
        private set
    val columns = mutableStateListOf<KColumn>()
    val assignees = mutableStateListOf<String>()
    var boardError by mutableStateOf<String?>(null)
        private set
    /** True until the first board answer: a spinner then, not "no tasks". */
    var loadingBoard by mutableStateOf(true)
        private set
    var detail by mutableStateOf<KDetail?>(null)
        private set
    var log by mutableStateOf<String?>(null)
    /** attachment id → its text, shown inline in the task sheet. */
    var preview by mutableStateOf<Pair<Long, String>?>(null)
        private set
    var notice by mutableStateOf<String?>(null)

    // ---- Scheduled ----
    val jobs = mutableStateListOf<CronJob>()
    var jobsError by mutableStateOf<String?>(null)
        private set
    val runs = mutableStateListOf<CronRun>()
    /** What each run produced, by session id: missing = not loaded yet, null = nothing to show. */
    val results = mutableStateMapOf<String, RunResult?>()
    /** A job whose sheet opens once the list has it (a tapped run notification). */
    var showJob by mutableStateOf<String?>(null)
    /** Jobs with a run result not read yet (its push came, notified or not): the sidebar's count and the menu's dot. */
    var unread by mutableStateOf(graph.store.unreadRuns)
        private set

    fun markUnread(job: String) { unread = unread + job; graph.store.unreadRuns = unread }
    fun markRead(job: String) { if (job in unread) { unread = unread - job; graph.store.unreadRuns = unread } }

    private fun k(path: String, params: Map<String, String> = emptyMap()): String {
        val all = params + (board?.let { mapOf("board" to it) } ?: emptyMap())
        val q = all.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return "/api/plugins/kanban$path" + if (q.isEmpty()) "" else "?$q"
    }

    private fun enc(id: String) = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    fun loadBoards() {
        graph.scope.launch {
            runCatching {
                val r = client.rest("GET", "/api/plugins/kanban/boards").obj()
                val list = (r?.get("boards") as? JsonArray).orEmpty().mapNotNull { it.obj() }.filter { it.bool("archived") != true }.mapNotNull { b ->
                    val slug = b.str("slug") ?: return@mapNotNull null
                    KBoard(slug, b.str("name") ?: slug, b.num("total")?.toInt())
                }
                boards.clear()
                boards.addAll(list)
                if (board == null) board = r?.str("current")?.takeIf { it.isNotEmpty() } ?: list.firstOrNull()?.slug
            }.onFailure { boardError = kanbanError(it) }
            loadBoard()
        }
    }

    fun selectBoard(slug: String) {
        board = slug
        columns.clear()
        loadBoard()
        // The stream is pinned to its board at the handshake: a new board needs a new one.
        if (watching != null) watchBoard()
    }

    // ---- live updates ----

    /** The board's event stream is open: polling slows to a safety net. */
    var live by mutableStateOf(false)
        private set
    private var watching: Job? = null
    private var reload: Job? = null

    /** Follows the board's `/events` (task_events rows): each burst reloads the board, and the open task if it changed. */
    fun watchBoard() {
        stopWatching()
        watching = graph.scope.launch {
            var attempt = 0
            while (isActive) {
                val closed = CompletableDeferred<Unit>()
                val socket = runCatching {
                    client.pluginSocket(k("/events"), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { android.util.Log.i("Kanban", "live events on"); graph.scope.launch { live = true; attempt = 0 } }
                        override fun onMessage(webSocket: WebSocket, text: String) { graph.scope.launch { onEvents(text) } }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); closed.complete(Unit) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                    })
                }.getOrNull()
                try { if (socket != null) closed.await() } finally { socket?.cancel(); live = false }
                // A board open since the drop may have changed: catch up, then reconnect (2 s doubling to 1 min).
                loadBoard()
                delay(minOf(60_000L, 2_000L shl minOf(attempt++, 5)))
            }
        }
    }

    fun stopWatching() { watching?.cancel(); watching = null; live = false }

    private fun onEvents(text: String) {
        val ids = (runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).obj() }.getOrNull()?.get("events") as? JsonArray)
            .orEmpty().mapNotNull { it.obj()?.str("task_id") }.toSet()
        android.util.Log.i("Kanban", "events for ${ids.size} task(s)")
        // A burst of events (a worker's progress) becomes one reload, 400 ms after the first.
        if (reload?.isActive == true) return
        reload = graph.scope.launch {
            delay(400)
            loadBoard()
            detail?.task?.id?.takeIf { it in ids }?.let { openTask(it) }
        }
    }

    fun loadBoard() {
        graph.scope.launch {
            loadingBoard = true
            try {
                val r = client.rest("GET", k("/board")).obj()
                val cols = (r?.get("columns") as? JsonArray).orEmpty().mapNotNull { it.obj() }.map { c ->
                    KColumn(c.str("name") ?: "unknown", (c["tasks"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::taskOf) })
                }.filter { it.name != "archived" }
                    .sortedBy { STATUS_ORDER.indexOf(it.name).let { i -> if (i < 0) 100 else i } }
                columns.clear()
                columns.addAll(cols)
                assignees.clear()
                assignees.addAll((r?.get("assignees") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                boardError = null
            } catch (e: Exception) {
                boardError = kanbanError(e)
            } finally {
                loadingBoard = false
            }
        }
    }

    private fun kanbanError(e: Throwable) = when {
        e.message?.contains("404") == true || e.message?.contains("Not Found", true) == true -> tr(R.string.tasksstate_no_kanban)
        else -> e.message ?: tr(R.string.tasksstate_board_failed)
    }

    fun openTask(id: String) {
        log = null
        graph.scope.launch {
            runCatching {
                val r = client.rest("GET", k("/tasks/${enc(id)}")).obj() ?: return@runCatching
                val task = r["task"].obj()?.let(::taskOf) ?: return@runCatching
                detail = KDetail(
                    task,
                    (r["comments"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.map { KComment(it.str("author") ?: defaultProfileTitle, it.str("body").orEmpty(), it.num("created_at")?.toLong()) },
                    (r["events"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.map { KEvent(it.str("kind").orEmpty(), it.num("created_at")?.toLong()) },
                    (r["runs"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.map(::runOf),
                    (r["attachments"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::attachmentOf) },
                )
            }.onFailure { notice = tr(R.string.tasksstate_open_task_failed, it.message.orEmpty()) }
        }
    }

    fun closeTask() { detail = null; log = null; preview = null }

    /** A worker's output often lands in an attachment (e.g. `kanban_complete` with a file) and not in `result`.
     *  Text shows in the sheet; anything else opens in the system viewer (same `/api/fs` reads as Artifacts). */
    fun openAttachment(a: KAttachment, ctx: Context) = graph.scope.launch {
        val path = a.path ?: return@launch
        if (preview?.first == a.id) { preview = null; return@launch }
        val text = runCatching { client.rest("GET", client.withProfile("/api/fs/read-text?path=${enc(path)}")).obj() }.getOrNull()
        if (text != null && text.bool("binary") != true) { preview = a.id to text.str("text").orEmpty(); return@launch }
        runCatching { client.rest("GET", client.withProfile("/api/fs/read-data-url?path=${enc(path)}"), timeoutSec = 60).obj()?.str("dataUrl") }
            .getOrNull()?.let { openDataUrl(ctx, it, a.name) } ?: run { notice = tr(R.string.tasksstate_open_file_failed, a.name) }
    }

    fun loadLog(id: String) = graph.scope.launch {
        log = runCatching { client.rest("GET", k("/tasks/${enc(id)}/log", mapOf("tail" to "16384"))).obj()?.let { if (it.bool("exists") == false) tr(R.string.tasksstate_no_log) else it.str("content") } }
            .getOrElse { tr(R.string.tasksstate_log_failed, it.message.orEmpty()) }
    }

    private fun mutate(label: String, refreshTask: String? = null, block: suspend () -> Unit) {
        graph.scope.launch {
            try {
                block()
                loadBoard()
                refreshTask?.let(::openTask)
            } catch (e: Exception) {
                notice = "$label: ${e.message}"
            }
        }
    }

    fun create(title: String, body: String, assignee: String?, priority: Int, triage: Boolean) = mutate(tr(R.string.tasksstate_create_failed)) {
        client.rest("POST", k("/tasks"), buildJsonObject {
            put("title", title.trim())
            if (body.isNotBlank()) put("body", body.trim())
            assignee?.takeIf { it.isNotBlank() }?.let { put("assignee", it) }
            put("priority", priority)
            put("workspace_kind", "scratch")
            put("parents", buildJsonArray { })
            put("triage", triage)
            put("goal_mode", false)
        })
    }

    fun setStatus(t: KTask, status: String) = mutate(tr(R.string.tasksstate_move_failed), t.id) { patch(t, buildJsonObject { put("status", status) }) }
    fun setPriority(t: KTask, p: Int) = mutate(tr(R.string.tasksstate_priority_failed), t.id) { patch(t, buildJsonObject { put("priority", p) }) }
    fun edit(t: KTask, title: String, body: String) = mutate(tr(R.string.tasksstate_edit_failed), t.id) { patch(t, buildJsonObject { put("title", title); put("body", body) }) }

    private suspend fun patch(t: KTask, body: JsonObject) { client.rest("PATCH", k("/tasks/${enc(t.id)}"), body) }

    fun reassign(t: KTask, profile: String?) = mutate(tr(R.string.tasksstate_reassign_failed), t.id) {
        client.rest("POST", k("/tasks/${enc(t.id)}/reassign"), buildJsonObject { profile?.let { put("profile", it) }; put("reclaim_first", true) })
    }

    fun comment(t: KTask, body: String) = mutate(tr(R.string.tasksstate_comment_failed), t.id) {
        client.rest("POST", k("/tasks/${enc(t.id)}/comments"), buildJsonObject { put("author", "allfather"); put("body", body.trim()) })
    }

    /** "Reply & unblock": a comment, then back to ready. */
    fun replyAndUnblock(t: KTask, body: String) = mutate(tr(R.string.tasksstate_unblock_failed), t.id) {
        if (body.isNotBlank()) client.rest("POST", k("/tasks/${enc(t.id)}/comments"), buildJsonObject { put("author", "allfather"); put("body", body.trim()) })
        patch(t, buildJsonObject { put("status", "ready") })
    }

    fun reclaim(t: KTask) = mutate(tr(R.string.tasksstate_reclaim_failed), t.id) { client.rest("POST", k("/tasks/${enc(t.id)}/reclaim"), buildJsonObject { }) }

    /** Specify / decompose: the triage helpers; `ok:false` carries Hermes' reason. */
    fun triageAction(t: KTask, action: String) = mutate(tr(R.string.tasksstate_triage_failed, defaultProfileTitle), t.id) {
        val r = client.rest("POST", k("/tasks/${enc(t.id)}/$action"), buildJsonObject { put("author", "allfather") }).obj()
        if (r?.bool("ok") == false) throw IllegalStateException(r.str("reason") ?: tr(R.string.tasksstate_rejected))
        notice = if (action == "specify") tr(R.string.tasksstate_specified)
        else ((r?.get("child_ids") as? JsonArray)?.size ?: 0).let { trPlural(R.plurals.tasksstate_decomposed, it, it) }
    }

    fun delete(t: KTask) = mutate(tr(R.string.tasksstate_delete_failed)) {
        client.rest("DELETE", k("/tasks/${enc(t.id)}"))
        detail = null
    }

    fun nudge() = mutate(tr(R.string.tasksstate_nudge_failed)) { client.rest("POST", k("/dispatch"), buildJsonObject { }); notice = tr(R.string.tasksstate_nudged) }

    // ---- Scheduled jobs ----

    fun loadJobs() {
        graph.scope.launch {
            try {
                val r = client.rest("GET", client.withProfile("/api/cron/jobs"))
                val list = (r as? JsonArray ?: r.obj()?.get("jobs") as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::jobOf) }
                jobs.clear()
                jobs.addAll(list.sortedWith(compareBy({ !it.enabled }, { it.name.lowercase() })))
                jobsError = null
            } catch (e: Exception) {
                jobsError = e.message
            }
        }
    }

    fun loadRuns(job: CronJob) {
        runs.clear()
        graph.scope.launch {
            runCatching {
                val r = client.rest("GET", client.withProfile("/api/cron/jobs/${enc(job.id)}/runs?limit=20", job.owner))
                val arr = r as? JsonArray ?: r.obj()?.let { o -> (o["runs"] ?: o["items"] ?: o["data"]) as? JsonArray }
                runs.addAll(arr.orEmpty().mapNotNull { it.obj() }.mapNotNull { raw ->
                    val o = raw["session"].obj() ?: raw
                    val id = o.str("id") ?: o.str("session_id") ?: o.str("run_id") ?: return@mapNotNull null
                    CronRun(id, o.str("title") ?: o.str("name"), o.str("preview") ?: o.str("summary"), (o.num("started_at") ?: o.num("last_active"))?.toLong())
                })
            }
        }
    }

    fun loadResult(job: CronJob, run: CronRun) {
        if (run.id in results) return
        graph.scope.launch {
            val r = runCatching {
                client.rest("GET", client.withProfile("/api/sessions/${enc(run.id)}/messages?limit=12&order=latest&include_compacted=true&inline_images=false", job.owner)).obj()
            }.getOrNull()
            val rows = (r?.get("messages") as? JsonArray).orEmpty().mapNotNull { it.obj() }
            results[run.id] = runResult(rows.sortedBy { it.num("id") ?: it.num("timestamp") ?: 0.0 })
        }
    }

    /** The profile that holds [this] job: its runs live in that profile's state.db. */
    private val CronJob.owner get() = profile ?: graph.profile

    private fun jobMutation(label: String, block: suspend () -> Unit) {
        graph.scope.launch {
            try { block(); loadJobs() } catch (e: Exception) { notice = "$label: ${e.message}" }
        }
    }

    /** pause / resume / trigger (run now). */
    fun jobAction(job: CronJob, action: String) = jobMutation(tr(R.string.tasksstate_job_failed)) {
        client.rest("POST", client.withProfile("/api/cron/jobs/${enc(job.id)}/$action", job.owner), buildJsonObject { put("profile", job.owner) })
        if (action == "trigger") notice = tr(R.string.tasksstate_job_running, job.name)
    }

    fun deleteJob(job: CronJob) = jobMutation(tr(R.string.tasksstate_delete_failed)) { client.rest("DELETE", client.withProfile("/api/cron/jobs/${enc(job.id)}", job.owner)); markRead(job.id) }

    /** Schedule is Hermes' text form ("every monday 9am", "30m", or a cron expression). */
    fun saveJob(original: CronJob?, name: String, prompt: String, schedule: String, deliver: String) = jobMutation(tr(R.string.tasksstate_save_failed)) {
        if (original == null) {
            client.rest("POST", client.withProfile("/api/cron/jobs"), buildJsonObject {
                put("name", name.trim()); put("prompt", prompt.trim()); put("schedule", schedule.trim()); put("deliver", deliver.ifBlank { "origin" })
            })
        } else {
            val updates = buildJsonObject {
                if (name.trim() != original.name) put("name", name.trim())
                if (prompt.trim() != original.prompt) put("prompt", prompt.trim())
                if (schedule.trim() != original.schedule && schedule.trim() != original.expr) put("schedule", schedule.trim())
                if (deliver != original.deliver.orEmpty()) put("deliver", deliver)
            }
            if (updates.isNotEmpty()) client.rest("PUT", client.withProfile("/api/cron/jobs/${enc(original.id)}", original.owner), buildJsonObject { put("updates", updates) })
        }
    }

    companion object {
        val STATUS_ORDER = listOf("triage", "todo", "scheduled", "ready", "running", "blocked", "review", "done", "archived")
        /** Statuses a person may move a task to (KanbanStatusPresentation.isManuallySelectable). */
        val MANUAL = listOf("triage", "todo", "ready", "blocked", "done", "archived")

        fun taskOf(o: JsonObject): KTask? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            return KTask(
                id, o.str("title") ?: tr(R.string.tasksstate_untitled), o.str("body"), o.str("status") ?: "todo", o.str("assignee"),
                o.num("priority")?.toInt() ?: 0, o.str("latest_summary"), o.num("comment_count")?.toInt() ?: 0,
                o.str("result"), o.str("last_failure_error"), o.num("worker_pid")?.toInt(), o.num("created_at")?.toLong(),
            )
        }

        fun runOf(o: JsonObject) = KRun(o.str("profile"), o.str("outcome") ?: o.str("status"), o.str("summary")?.ifBlank { null }, o.str("error")?.ifBlank { null }, o.num("ended_at")?.toLong())

        fun attachmentOf(o: JsonObject): KAttachment? {
            val id = o.num("id")?.toLong() ?: return null
            return KAttachment(id, o.str("filename") ?: tr(R.string.tasksstate_file), o.str("stored_path"), o.num("size")?.toLong())
        }

        fun jobOf(o: JsonObject): CronJob? {
            val id = o.str("id") ?: return null
            val sched: JsonElement? = o["schedule"]
            val expr = (sched as? JsonObject)?.str("expr") ?: (sched as? JsonPrimitive)?.contentOrNull
            return CronJob(
                id, o.str("name")?.ifBlank { null } ?: id, o.str("prompt").orEmpty(),
                (o.str("schedule_display") ?: (sched as? JsonObject)?.str("display") ?: expr.orEmpty()).let { cronToWords(it) ?: it }, expr,
                o.bool("enabled") != false && o.str("state") != "paused", o.str("state"),
                o.str("next_run_at"), o.str("last_run_at"), o.str("last_status"), o.str("last_error"), o.str("deliver"), o.str("profile"),
            )
        }
    }
}
