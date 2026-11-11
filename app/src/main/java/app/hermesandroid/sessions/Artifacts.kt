package app.hermesandroid.sessions

import android.content.Intent
import app.hermesandroid.ui.shell.decodeImage
import app.hermesandroid.ui.shell.imageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.chat.HtmlBlock
import app.hermesandroid.chat.openDataUrl
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.settings.enc
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import app.hermesandroid.ui.icons.AF
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.ui.shell.profileTitle

// Artifacts: files, pages and links Hermes made or touched in this profile's
// chats, indexed by the host plugin (`/api/plugins/conduit_push/artifacts`).

data class Artifact(
    val kind: String, val source: String, val name: String, val path: String?, val url: String?, val exists: Boolean, val size: Long?,
    val sessionId: String, val sessionTitle: String, val messageId: Int, val at: Long,
) {
    val id get() = path ?: url ?: "$messageId|$name"
}

private val KINDS = setOf("image", "video", "audio", "page", "document", "file", "link")
private val SOURCES = setOf("delivered", "preview", "written", "attached", "link")

/** One page of `/artifacts`: malformed rows are dropped, links need an http(s) URL, the rest a path. */
fun parseArtifacts(r: JsonObject?): Pair<List<Artifact>, Int?>? {
    if (r?.bool("ok") != true) return null
    val items = (r["items"] as? JsonArray ?: return null).mapNotNull { it.obj() }.mapNotNull { o ->
        val kind = o.str("kind")?.takeIf { it in KINDS } ?: return@mapNotNull null
        val source = o.str("source")?.takeIf { it in SOURCES } ?: return@mapNotNull null
        val session = o.str("session_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val message = o.num("message_id")?.toInt() ?: return@mapNotNull null
        val ts = o.num("timestamp") ?: return@mapNotNull null
        val path = o.str("path")?.takeIf { it.isNotEmpty() }
        val url = o.str("url")?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        if (source == "link" && url == null || source != "link" && path == null) return@mapNotNull null
        val name = o.str("name")?.trim().takeUnless { it.isNullOrEmpty() } ?: path?.substringAfterLast('/') ?: runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        Artifact(kind, source, name, path, url, source == "link" || o.bool("exists") != false, o.num("size")?.toLong(), session, o.str("session_title").orEmpty(), message, (ts * 1000).toLong())
    }
    return items to r.num("next_before")?.toInt()
}

private enum class ArtFilter(@StringRes val title: Int, val kind: String?) {
    All(R.string.artifacts_all, null), Images(R.string.artifacts_images, "images"), Pages(R.string.artifacts_pages, "pages"),
    Files(R.string.artifacts_files, "files"), Links(R.string.artifacts_links, "links"),
}

private fun icon(kind: String): ImageVector = when (kind) {
    "image" -> AF.Image; "video" -> AF.Movie; "audio" -> AF.Audiotrack
    "page" -> AF.Web; "document" -> AF.Description; "link" -> AF.Link
    else -> AF.InsertDriveFile
}

private fun sizeText(b: Long) = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", b / 1048576.0)
}

private fun dayTitle(ms: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(day, today)) return tr(R.string.artifacts_today)
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(day, today)) return tr(R.string.artifacts_yesterday)
    val locale = Locale.getDefault()
    return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(ms)).replaceFirstChar { it.uppercase() }
}

@Composable
fun ArtifactsPage(graph: AppGraph, bottomInset: Dp, onOpenChat: (String, String) -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val plugin = graph.hostPlugin
    var filter by remember { mutableStateOf(ArtFilter.All) }
    var query by remember { mutableStateOf("") }
    var showMissing by remember { mutableStateOf(false) }
    val items = remember { mutableStateListOf<Artifact>() }
    var next by remember { mutableStateOf<Int?>(null) }
    // Loading from the first frame (the fetch starts a frame later): no "no artifacts yet" flash on every open.
    var loading by remember { mutableStateOf(plugin.reports("artifacts")) }
    var failure by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<Pair<String, String>?>(null) }
    var missing by remember { mutableStateOf<Artifact?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }

    suspend fun fetch(before: Int?): Boolean {
        val params = buildList {
            add("limit=60")
            filter.kind?.let { add("kind=$it") }
            query.trim().takeIf { it.isNotEmpty() }?.let { add("q=${enc(it)}") }
            if (!showMissing) add("missing=hide")
            before?.let { add("before=$it") }
        }
        loading = true
        val r = runCatching { parseArtifacts(graph.client.rest("GET", graph.client.withProfile("/api/plugins/conduit_push/artifacts?" + params.joinToString("&")), timeoutSec = 30).obj()) }
        loading = false
        val (list, nb) = r.getOrNull() ?: run { failure = tr(R.string.artifacts_read_failed, r.exceptionOrNull()?.message ?: tr(R.string.artifacts_unexpected)); return false }
        failure = null
        if (before == null) items.clear()
        list.filter { (showMissing || it.exists) && items.none { o -> o.id == it.id } }.forEach { items += it }
        next = nb
        return true
    }

    LaunchedEffect(graph.profile, filter, query, showMissing, plugin.state) {
        if (!plugin.reports("artifacts")) return@LaunchedEffect
        if (query.isNotEmpty()) delay(350)
        fetch(null)
    }

    fun open(a: Artifact) {
        when {
            a.url != null && a.kind == "link" -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)))
            !a.exists -> missing = a
            a.kind == "page" -> scope.launch {
                opening = a.id
                val html = runCatching { graph.client.rest("GET", graph.client.withProfile("/api/fs/read-text?path=" + enc(a.path!!))).obj()?.str("text") }.getOrNull()
                opening = null
                if (html != null) page = a.name to html else missing = a
            }
            else -> scope.launch {
                opening = a.id
                val du = runCatching { graph.client.rest("GET", graph.client.withProfile("/api/fs/read-data-url?path=" + enc(a.path!!)), timeoutSec = 60).obj()?.str("dataUrl") }.getOrNull()
                opening = null
                if (du != null) openDataUrl(ctx, du, a.name) else missing = a
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(c.text.copy(alpha = 0.06f)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AF.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (query.isEmpty()) Text(stringResource(R.string.artifacts_search_hint), style = CType.subheadline, color = c.tertiary)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = CType.subheadline.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            if (query.isNotEmpty()) Icon(AF.Close, stringResource(R.string.artifacts_clear), tint = c.secondary, modifier = Modifier.size(18.dp).pressable { query = "" })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtFilter.entries.forEach { f -> Chip(stringResource(f.title), f == filter) { filter = f } }
            Chip(stringResource(R.string.artifacts_show_missing), showMissing) { showMissing = !showMissing }
        }
        when {
            plugin.state == app.hermesandroid.settings.HostPlugin.State.Unknown -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            !plugin.reports("artifacts") -> Note(stringResource(R.string.artifacts_needs_plugin))
            failure != null && items.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(failure!!, style = CType.footnote, color = c.red)
                SettingsButton(stringResource(R.string.artifacts_retry)) { scope.launch { fetch(null) } }
            }
            items.isEmpty() && loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            items.isEmpty() && filter != ArtFilter.All && query.isBlank() -> Note(stringResource(R.string.artifacts_none_of_kind, stringResource(filter.title).lowercase()) + if (showMissing) "" else " " + stringResource(R.string.artifacts_missing_hint))
            items.isEmpty() -> Note(if (query.isBlank()) stringResource(R.string.artifacts_empty, profileTitle(graph.profile)) else stringResource(R.string.artifacts_no_match, query.trim()))
            else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset + 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.groupBy { dayTitle(it.at) }.forEach { (day, list) ->
                    item(key = "day:$day") { Text(day, style = CType.footnote.semibold(), color = c.secondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }
                    items(list, key = { it.id }) { a -> ArtifactRow(graph, a, opening == a.id, { open(a) }) { onOpenChat(a.sessionId, a.sessionTitle) } }
                }
                if (next != null) item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                        else SettingsButton(stringResource(R.string.artifacts_load_more)) { scope.launch { fetch(next) } }
                    }
                }
            }
        }
    }
    page?.let { (name, html) ->
        AfSheet({ page = null }) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = CType.headline, color = c.text)
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) { HtmlBlock(html, minHeight = 480, baseUrl = "https://localhost/") }
            }
        }
    }
    missing?.let { a ->
        AlertDialog(
            onDismissRequest = { missing = null },
            title = { Text(stringResource(R.string.artifacts_missing_title)) },
            text = { Text(a.path.orEmpty()) },
            confirmButton = { TextButton({ missing = null }) { Text(stringResource(R.string.artifacts_ok)) } },
        )
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = C.colors
    Text(
        text, style = CType.subheadline.semibold(), color = if (on) c.accent else c.text,
        modifier = Modifier.clip(CircleShape).background(if (on) c.accent.copy(alpha = 0.18f) else c.text.copy(alpha = 0.06f))
            .border(1.dp, if (on) c.accent.copy(alpha = 0.45f) else c.text.copy(alpha = 0.08f), CircleShape)
            .pressable(haptic = Haptic.Selection, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun Note(text: String) = Text(text, style = CType.subheadline, color = C.colors.secondary, modifier = Modifier.padding(24.dp))

@Composable
private fun ArtifactRow(graph: AppGraph, a: Artifact, opening: Boolean, onOpen: () -> Unit, onChat: () -> Unit) {
    val c = C.colors
    // Thumbnails for images only, fetched as each row comes into view.
    val key = "thumb:" + a.path
    var thumb by remember(a.id) { mutableStateOf(imageCache[key]) }
    if (thumb == null && a.kind == "image" && a.exists && (a.size ?: 0) < 4_000_000) LaunchedEffect(a.id) {
        val du = runCatching { graph.client.rest("GET", graph.client.withProfile("/api/fs/read-data-url?path=" + enc(a.path!!))).obj()?.str("dataUrl") }.getOrNull() ?: return@LaunchedEffect
        thumb = withContext(Dispatchers.Default) { decodeImage(Base64.decode(du.substringAfter(","), Base64.DEFAULT), 160)?.asImageBitmap() }
            ?.also { imageCache.put(key, it) }
    }
    Row(
        Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(18.dp)).pressable(haptic = Haptic.Light, onClick = onOpen).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            val t = thumb
            when {
                opening -> CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                t != null -> Image(t, a.name, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
                else -> Icon(icon(a.kind), null, tint = if (a.exists) c.accent else c.tertiary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(a.name, style = CType.subheadline.semibold(), color = if (a.exists) c.text else c.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(a.sessionTitle.ifBlank { null }, a.size?.let(::sizeText), if (!a.exists) stringResource(R.string.artifacts_gone) else null).joinToString(" · "),
                style = CType.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Chat", style = CType.footnote.semibold(), color = c.accent, modifier = Modifier.pressable(onClick = onChat).padding(8.dp))
    }
}
