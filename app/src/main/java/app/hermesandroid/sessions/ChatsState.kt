package app.hermesandroid.sessions

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.annotation.StringRes
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.HttpError
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SessionRow(
    val id: String,
    val title: String,
    val preview: String?,
    val updated: Double,
    val source: String,
    val pinned: Boolean,
    val archived: Boolean = false,
    val messageCount: Int = 0,
    /** Owning profile: the default profile's list merges every profile's chats (a helper bot's Discord channel…). */
    val profile: String? = null,
)

/** One hit of `/api/sessions/search`: the chat (its live tip) and a snippet with `>>>match<<<` marks. */
data class SearchHit(val id: String, val title: String?, val snippet: String)

data class Project(val id: String, val title: String, val path: String?, val count: Int, val isHome: Boolean, val previews: List<SessionRow>)
data class Lane(val id: String, val title: String, val path: String?, val sessions: List<SessionRow>)

/** The chat list's filters. Sources filter by `source`; Archived and Scheduled load their own rows. */
sealed interface Filter {
    data object All : Filter
    data object Starred : Filter
    data object Unread : Filter
    data object Approval : Filter
    data object Running : Filter
    data object Voice : Filter
    data object Scheduled : Filter
    data object Archived : Filter
    data class Source(val name: String) : Filter
}

/** The Chats list for the active profile. */
/** Sources kept out of Chats: cron runs live in Tareas > Programadas; board workers, tool runs and one-shots are internal
 *  (Hermes' INTERNAL_LISTING_SOURCES, which a custom exclude list would otherwise replace). */
private const val HIDDEN = "cron,kanban,tool,oneshot"

class ChatsState(private val graph: AppGraph, private val context: Context) {
    private val client = graph.client
    private val prefs = context.getSharedPreferences("chats", Context.MODE_PRIVATE)

    val sessions = mutableStateListOf<SessionRow>()
    var error by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var filter by mutableStateOf<Filter>(Filter.All)
        private set
    val collapsed = mutableStateListOf<String>()

    /** Live runtime state from session.active_list, keyed by stored id. */
    val running = mutableStateMapOf<String, String>()
    val starred = mutableStateListOf<String>()
    private val seen = mutableStateMapOf<String, Double>()

    val searchHits = mutableStateListOf<SearchHit>()
    var searching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    var showProjects by mutableStateOf(false)
    val projects = mutableStateListOf<Project>()
    var projectsError by mutableStateOf<String?>(null)
        private set
    var openProject by mutableStateOf<Project?>(null)
    val lanes = mutableStateListOf<Lane>()

    init {
        loadLocal()
        graph.listen { if (it.type == "sessions.changed") refresh() }
        graph.scope.launch {
            snapshotFlow { graph.connectedEpoch }.collect { if (it > 0) refresh() }
        }
    }

    fun clear() {
        sessions.clear()
        running.clear()
        searchHits.clear()
        projects.clear()
        openProject = null
    }

    // ---- loading ----

    fun applyFilter(f: Filter) {
        val reload = (f == Filter.Archived || f == Filter.Scheduled || filter == Filter.Archived || filter == Filter.Scheduled) && f != filter
        filter = f
        if (reload) refresh()
    }

    fun refresh() {
        if (!graph.signedIn) return
        refreshing = true
        graph.scope.launch {
            try {
                val rows = page(0)
                sessions.clear()
                sessions.addAll(rows)
                if (filter == Filter.All) app.hermesandroid.sharesheet.publishChatShortcuts(context, listed, graph.profile, graph.store.activeId)
                hasMore = rows.size >= PAGE
                seedSeen(rows)
                error = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                refreshing = false
            }
            loadActive()
        }
    }

    fun loadMore() {
        if (!hasMore || refreshing) return
        refreshing = true
        graph.scope.launch {
            runCatching {
                val rows = page(sessions.size)
                val known = sessions.map { it.id }.toSet()
                sessions.addAll(rows.filter { it.id !in known })
                hasMore = rows.size >= PAGE
                seedSeen(rows)
            }
            refreshing = false
        }
    }

    private suspend fun page(offset: Int): List<SessionRow> {
        val scope = when (filter) {
            Filter.Archived -> "archived=only&exclude_sources=$HIDDEN"
            Filter.Scheduled -> "archived=exclude&source=cron"
            else -> "archived=exclude&exclude_sources=$HIDDEN"
        }
        val r = client.rest("GET", client.withProfile("/api/profiles/sessions?limit=$PAGE&offset=$offset&min_messages=1&order=recent&$scope")).obj()
        return (r?.get("sessions") as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::rowOf) }
    }

    /** Runtime states (running / waiting) for the list's status glyphs; older gateways lack the method. */
    private suspend fun loadActive() {
        runCatching {
            val rows = (client.call("session.active_list", JsonObject(emptyMap())).obj()?.get("sessions") as? JsonArray).orEmpty().mapNotNull { it.obj() }
            running.clear()
            rows.forEach { r -> r.str("session_key")?.takeIf { it.isNotEmpty() }?.let { running[it] = r.str("status") ?: "idle" } }
        }
    }

    // ---- local state: stars and read marks (ChatReadState) ----

    private fun loadLocal() {
        starred.clear()
        starred.addAll(prefs.getStringSet("starred:${graph.profile}", emptySet()).orEmpty())
        seen.clear()
        prefs.getString("seen:${graph.profile}", null)?.split('\n')?.forEach { line ->
            val (id, t) = line.split('\t').takeIf { it.size == 2 } ?: return@forEach
            t.toDoubleOrNull()?.let { seen[id] = it }
        }
    }

    fun profileChanged() {
        loadLocal()
        openProject = null
        refresh()
        if (showProjects) loadProjects()
    }

    private fun saveSeen() = prefs.edit().putString("seen:${graph.profile}", seen.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()

    /**
     * First sight of a row counts as read, so a fresh install doesn't light up the whole list. The chat on screen is
     * read up to the list's own time for it, replies that came while it was open included: the host's clock (about a
     * second ahead of the phone's) is the one `updated` uses, so `markSeen`'s "now" alone can fall short.
     */
    private fun seedSeen(rows: List<SessionRow>) {
        val onScreen = graph.chat.storedId?.takeIf { graph.chat.isOpen }
        var changed = false
        rows.forEach {
            if (it.id !in seen || (it.id == onScreen && it.updated > (seen[it.id] ?: 0.0))) { seen[it.id] = it.updated; changed = true }
        }
        if (changed) saveSeen()
    }

    fun isUnread(row: SessionRow) = row.updated > (seen[row.id] ?: row.updated)

    fun markSeen(id: String) {
        seen[id] = sessions.firstOrNull { it.id == id }?.updated?.coerceAtLeast(System.currentTimeMillis() / 1000.0) ?: (System.currentTimeMillis() / 1000.0)
        saveSeen()
    }

    fun markUnread(row: SessionRow) {
        seen[row.id] = row.updated - 1
        saveSeen()
    }

    fun markAllRead() {
        sessions.forEach { seen[it.id] = it.updated }
        saveSeen()
    }

    fun toggleStar(row: SessionRow) {
        if (row.id in starred) starred.remove(row.id) else starred.add(row.id)
        prefs.edit().putStringSet("starred:${graph.profile}", starred.toSet()).apply()
    }

    /** The chats the list shows: the Pulse chat lives behind the Pulse page instead. */
    val listed get() = graph.pulse.homeChatId.let { pulse -> sessions.filter { it.id != pulse } }

    fun visible(): List<SessionRow> {
        val q = query.trim()
        val waiting = graph.requests.mapNotNull { it.params.str("session_id") }.toSet()
        return listed.filter { row ->
            (q.isEmpty() || row.title.contains(q, true) || row.preview?.contains(q, true) == true) && when (val f = filter) {
                Filter.All, Filter.Archived, Filter.Scheduled -> true
                Filter.Starred -> row.id in starred
                Filter.Unread -> isUnread(row)
                Filter.Approval -> row.id in waiting || running[row.id] == "waiting"
                Filter.Running -> running[row.id].let { it != null && it != "idle" }
                Filter.Voice -> row.source.startsWith("voice")
                is Filter.Source -> row.source == f.name
            }
        }
    }

    fun sources(): List<String> = listed.map { it.source }.filter { it != "voice" && it != "cron" }.distinct().sorted()

    // ---- mutations (PATCH / DELETE /api/sessions/{id} with the mutation identity) ----

    fun setPinned(row: SessionRow, pinned: Boolean) = act(R.string.chatsstate_pin_failed) {
        mutate(row, buildJsonObject { put("pinned", pinned) })
        replace(row.id) { it.copy(pinned = pinned) }
    }

    fun setArchived(row: SessionRow, archived: Boolean) = act(R.string.chatsstate_archive_failed) {
        mutate(row, buildJsonObject { put("archived", archived) })
        // Leaves the list only if it no longer belongs to what's shown (the filter may have changed meanwhile).
        if (archived != (filter == Filter.Archived)) sessions.removeAll { it.id == row.id } else replace(row.id) { it.copy(archived = archived) }
        if (!archived && filter != Filter.Archived && sessions.none { it.id == row.id }) refresh()
    }

    /** session.title on the live runtime when this chat is open, else the stored-session PATCH. */
    fun rename(row: SessionRow, title: String) = act(R.string.chatsstate_rename_failed) {
        val t = title.trim().takeIf { it.isNotEmpty() && it != row.title } ?: return@act
        val runtime = graph.chat.runtimeId?.takeIf { graph.chat.storedId == row.id }
        val viaRuntime = runtime != null && runCatching { client.call("session.title", buildJsonObject { put("session_id", runtime); put("title", t) }) }.isSuccess
        if (!viaRuntime) mutate(row, buildJsonObject { put("title", t) })
        replace(row.id) { it.copy(title = t) }
    }

    fun delete(row: SessionRow) = act(R.string.chatsstate_delete_failed) {
        mutate(row, null)
        sessions.removeAll { it.id == row.id }
        if (graph.chat.storedId == row.id) graph.chat.close()
    }

    /** Project membership follows the session's folder: moving the folder moves the chat. */
    fun moveTo(row: SessionRow, project: Project) = act(R.string.chatsstate_move_failed) {
        val cwd = project.path ?: throw IllegalStateException(tr(R.string.chatsstate_no_folder))
        client.call("session.workspace.move", buildJsonObject { put("session_key", row.id); put("cwd", cwd) })
        if (openProject != null) openProject?.let(::openProjectPage)
    }

    /** [failure]: a "Couldn't …: %1$s" text, given the error's message. */
    private fun act(@StringRes failure: Int, block: suspend () -> Unit) {
        graph.scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = tr(failure, e.message.toString())
            }
        }
    }

    private fun replace(id: String, f: (SessionRow) -> SessionRow) {
        val i = sessions.indexOfFirst { it.id == id }
        if (i >= 0) sessions[i] = f(sessions[i])
    }

    /** The helper bot a chat belongs to, when it isn't the active profile (its row says so). */
    fun botOf(row: SessionRow): String? = row.profile?.takeIf { it != graph.profile }?.let { app.hermesandroid.ui.shell.profileTitle(it) }

    /** In the chat's own profile: a bot's chat lives in that bot's state.db. */
    private suspend fun mutate(row: SessionRow, body: JsonObject?) {
        val id = row.id
        val owner = row.profile ?: graph.profile
        try {
            mutateOnce(id, body, owner)
        } catch (e: HttpError) {
            // A concurrent edit bumped the revision: read a fresh snapshot once and retry.
            if (e.code == 409 && RETRYABLE.any { e.message?.contains(it, true) == true }) mutateOnce(id, body, owner) else throw e
        }
    }

    private suspend fun mutateOnce(id: String, body: JsonObject?, owner: String) {
        val enc = URLEncoder.encode(id, "UTF-8")
        val snapshot = runCatching { client.rest("GET", client.withProfile("/api/sessions/$enc/mutation-snapshot", owner)).obj() }.getOrNull()
        val identity = snapshot?.num("runtime_revision")?.takeIf { it >= 0 }?.let { rev ->
            buildMap {
                put("request_id", UUID.randomUUID().toString())
                put("expected_revision", rev.toLong().toString())
                snapshot.num("runtime_generation")?.takeIf { it >= 0 }?.let { put("expected_generation", it.toLong().toString()) }
            }
        }
        val path = client.withProfile("/api/sessions/$enc", owner)
        if (body != null) {
            // PATCH reads the owning profile from the body, not the query (DashboardPath.bodyWithProfile).
            val full = buildJsonObject {
                body.forEach { (k, v) -> put(k, v) }
                identity?.forEach { (k, v) -> if (k == "request_id") put(k, v) else put(k, v.toLong()) }
                if (owner != "default") put("profile", owner)
            }
            client.rest("PATCH", path, full)
        } else {
            val q = identity?.entries?.sortedBy { it.key }?.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            client.rest("DELETE", if (q == null) path else path + (if ('?' in path) "&" else "?") + q)
        }
    }

    // ---- full-text search (/api/sessions/search) ----

    fun onQueryChange(q: String) {
        query = q
        searchJob?.cancel()
        searchHits.clear()
        if (q.trim().length < 2) return
        searchJob = graph.scope.launch {
            delay(350)
            searching = true
            runCatching {
                val r = client.rest("GET", client.withProfile("/api/sessions/search?q=${URLEncoder.encode(q.trim(), "UTF-8")}&limit=20")).obj()
                val shown = visible().map { it.id }.toSet()
                searchHits.addAll((r?.get("results") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { row ->
                    val id = row.str("session_id") ?: row.str("id") ?: return@mapNotNull null
                    if (id in shown) null else SearchHit(id, row.str("title"), row.str("snippet").orEmpty())
                }.distinctBy { it.id })
            }
            searching = false
        }
    }

    // ---- projects (projects.*) ----

    fun toggleProjects() {
        showProjects = !showProjects
        openProject = null
        if (showProjects) loadProjects()
    }

    fun loadProjects() {
        graph.scope.launch {
            try {
                val r = client.call("projects.tree", buildJsonObject { put("preview_limit", 3) })
                val list = (r.obj()?.get("projects") as? JsonArray ?: r as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::projectOf) }
                projects.clear()
                projects.addAll(list)
                projectsError = null
            } catch (e: Exception) {
                projectsError = if (e.message?.contains("not found", true) == true || e.message?.contains("method", true) == true) tr(R.string.chatsstate_no_projects) else e.message
            }
        }
    }

    fun openProjectPage(p: Project) {
        openProject = p
        lanes.clear()
        graph.scope.launch {
            runCatching {
                val proj = client.call("projects.project_sessions", buildJsonObject { put("project_id", p.id) }).obj()?.get("project").obj() ?: return@runCatching
                val repos = (proj["repos"] as? JsonArray).orEmpty().mapNotNull { it.obj() }
                val out = mutableListOf<Lane>()
                repos.forEach { repo ->
                    val repoLabel = repo.str("label") ?: repo.str("path") ?: tr(R.string.chatsstate_workspace)
                    (repo["groups"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { g ->
                        val rows = (g["sessions"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::rowOf) }
                        if (rows.isNotEmpty()) {
                            val label = g.str("label") ?: g.str("path") ?: tr(R.string.chatsstate_sessions)
                            out += Lane(g.str("id") ?: label, if (repos.size > 1) "$repoLabel · $label" else label, g.str("path") ?: g.str("cwd"), rows)
                        }
                    }
                }
                if (out.isEmpty() && p.previews.isNotEmpty()) out += Lane("${p.id}-recent", tr(R.string.chatsstate_recent), p.path, p.previews)
                lanes.clear()
                lanes.addAll(out)
            }
        }
    }

    fun createProject(name: String, folder: String) = act(R.string.chatsstate_create_project_failed) {
        client.call("projects.create", buildJsonObject {
            put("name", name.trim())
            put("folders", JsonArray(listOf(JsonPrimitive(folder.trim()))))
            put("primary_path", folder.trim())
            put("use", true)
        })
        loadProjects()
    }

    fun renameProject(p: Project, name: String) = act(R.string.chatsstate_rename_project_failed) {
        client.call("projects.update", buildJsonObject { put("id", p.id); put("name", name.trim()) })
        loadProjects()
    }

    /** Removes the project record only; folders, files and conversations stay. */
    fun deleteProject(p: Project) = act(R.string.chatsstate_delete_project_failed) {
        client.call("projects.delete", buildJsonObject { put("id", p.id) })
        if (openProject?.id == p.id) openProject = null
        loadProjects()
    }

    companion object {
        const val PAGE = 200
        private val RETRYABLE = listOf("revision_conflict", "stale_generation", "mutation_identity_required")

        /** The watch, the assistant and phone control send their instructions before the user's words: name the source instead. */
        fun readablePreview(p: String): String = when {
            p.startsWith("[From the watch") -> tr(R.string.chatsstate_from_watch)
            p.startsWith("[Phone assistant") -> tr(R.string.chatsstate_from_assistant)
            p.startsWith("<system-reminder>\n[Helper council on") -> tr(R.string.chatsstate_with_helpers)
            p.startsWith("<system-reminder>") -> tr(R.string.chatsstate_with_phone_control)
            else -> p
        }

        fun rowOf(s: JsonObject): SessionRow? {
            val id = s.str("session_id") ?: s.str("id") ?: return null
            val title = s.str("title")?.takeIf { it.isNotBlank() } ?: s.str("preview")?.takeIf { it.isNotBlank() } ?: tr(R.string.chatsstate_untitled)
            val updated = listOf("last_active", "updated_at", "started_at", "created_at").firstNotNullOfOrNull { s.num(it) } ?: 0.0
            return SessionRow(
                id, title, s.str("preview")?.let(::readablePreview)?.takeIf { it != title },
                if (updated > 4e10) updated / 1000 else updated,
                s.str("source") ?: "chat", s.bool("pinned") == true, s.bool("archived") == true,
                s.num("message_count")?.toInt() ?: 0, s.str("profile"),
            )
        }

        private fun projectOf(o: JsonObject): Project? {
            val id = o.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val home = o.bool("isNoProject") == true || o.bool("is_no_project") == true
            fun firstPath(key: String) = (o[key] as? JsonArray).orEmpty().firstNotNullOfOrNull { it.obj()?.str("path") }
            val previews = ((o["preview_sessions"] ?: o["previewSessions"]) as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::rowOf) }
            return Project(
                id, o.str("label") ?: o.str("name") ?: o.str("title") ?: tr(R.string.chatsstate_unnamed_project),
                o.str("path") ?: o.str("primary_path") ?: firstPath("folders") ?: if (home) null else firstPath("repos"),
                o.num("session_count")?.toInt() ?: previews.size, home, previews,
            )
        }
    }
}
