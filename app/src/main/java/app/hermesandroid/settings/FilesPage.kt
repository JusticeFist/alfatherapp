package app.hermesandroid.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.chat.openDataUrl
import app.hermesandroid.net.bool
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.tr
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import app.hermesandroid.ui.icons.AF

// Workspace browser over the dashboard's `/api/fs/*` routes (the same ones Hermes Desktop's file panel uses):
// read-only — folders, text previews, and other files handed to the phone's viewer.

data class FsEntry(val name: String, val path: String, val dir: Boolean)

/** The parent folder, or null at the root ("/" or "C:\"). */
fun parentPath(p: String): String? {
    val t = p.trimEnd('/', '\\')
    val cut = maxOf(t.lastIndexOf('/'), t.lastIndexOf('\\'))
    if (cut < 0 || t.isEmpty()) return null
    return if (cut == 0) "/" else t.substring(0, cut).let { if (it.endsWith(":")) "$it\\" else it }
}

private val TEXT_EXT = setOf("txt", "md", "markdown", "json", "yaml", "yml", "toml", "ini", "cfg", "conf", "py", "kt", "kts", "java", "js", "ts", "tsx", "jsx",
    "sh", "bash", "zsh", "rs", "go", "c", "h", "cpp", "hpp", "swift", "rb", "php", "sql", "csv", "log", "xml", "html", "htm", "css", "env", "gradle", "lua", "")

@Composable
fun FilesPage(graph: AppGraph) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = graph.client
    var home by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph.profile) {
        val cwd = runCatching { client.rest("GET", client.withProfile("/api/fs/default-cwd")).obj()?.str("cwd") }.getOrNull()
        home = cwd; path = cwd ?: "/"
    }
    LaunchedEffect(path) {
        val p = path ?: return@LaunchedEffect
        entries = null
        runCatching { client.rest("GET", client.withProfile("/api/fs/list?path=" + enc(p))).obj() }
            .onSuccess { r ->
                entries = (r?.get("entries") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { e -> FsEntry(e.str("name") ?: return@mapNotNull null, e.str("path") ?: return@mapNotNull null, e.bool("isDirectory") == true) }
                error = null
            }
            .onFailure { error = tr(R.string.filespage_folder_error, it.message.toString()); entries = emptyList() }
    }
    fun open(e: FsEntry) {
        if (e.dir) { path = e.path; return }
        val ext = e.name.substringAfterLast('.', "").lowercase().takeIf { '.' in e.name } ?: ""
        scope.launch {
            opening = e.path
            if (ext in TEXT_EXT) {
                val r = runCatching { client.rest("GET", client.withProfile("/api/fs/read-text?path=" + enc(e.path))).obj() }.getOrNull()
                r?.str("text")?.let { preview = Triple(e.name, it, r.bool("truncated") == true) } ?: run { error = tr(R.string.filespage_read_error, e.name) }
            } else {
                val du = runCatching { client.rest("GET", client.withProfile("/api/fs/read-data-url?path=" + enc(e.path)), timeoutSec = 60).obj()?.str("dataUrl") }.getOrNull()
                if (du != null) openDataUrl(ctx, du, e.name) else error = tr(R.string.filespage_open_error, e.name)
            }
            opening = null
        }
    }

    Help(stringResource(R.string.filespage_help))
    val p = path ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parentPath(p)?.let { up -> SettingsButton(stringResource(R.string.filespage_up), AF.ArrowUpward) { path = up } }
        home?.takeIf { it != p }?.let { h -> SettingsButton(stringResource(R.string.filespage_home), AF.Home) { path = h } }
    }
    Text(p, style = CType.footnote.copy(fontFamily = CType.mono), color = c.secondary, modifier = Modifier.horizontalScroll(rememberScrollState()))
    error?.let { Text(it, style = CType.footnote, color = c.red) }
    val list = entries
    Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(20.dp))) {
        when {
            list == null -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = c.accent) }
            list.isEmpty() -> Text(stringResource(R.string.filespage_empty), style = CType.subheadline, color = c.secondary, modifier = Modifier.padding(16.dp))
            else -> list.take(500).forEachIndexed { i, e ->
                if (i > 0) Divider(inset = 52.dp)
                Row(Modifier.fillMaxWidth().pressable(haptic = Haptic.Light) { open(e) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (opening == e.path) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    else Icon(if (e.dir) AF.Folder else AF.Description, null, tint = if (e.dir) c.accent else c.secondary, modifier = Modifier.size(20.dp))
                    Text(e.name, style = CType.callout, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
    if ((list?.size ?: 0) > 500) Help(stringResource(R.string.filespage_first_500))
    preview?.let { (name, text, truncated) ->
        AfSheet({ preview = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = CType.headline, color = c.text)
                if (truncated) Help(stringResource(R.string.filespage_truncated))
                Text(text, style = CType.caption.copy(fontFamily = CType.mono), color = c.text,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.text.copy(alpha = 0.06f)).horizontalScroll(rememberScrollState()).padding(10.dp))
            }
        }
    }
}
