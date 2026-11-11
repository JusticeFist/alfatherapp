package app.hermesandroid.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.net.HttpError
import app.hermesandroid.net.bool
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.tr
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.hermesandroid.ui.icons.AF

// The host plugin (`conduit_push`, AllFather's notifier) on the dashboard's host: what it reports, and installing
// or updating it from the phone.

/** Dotted versions, numerically; anything unparsable counts as older. */
fun versionOlder(v: String?, than: String): Boolean {
    fun parts(s: String) = s.substringBefore('-').split('.').map { it.toIntOrNull() }.takeIf { p -> p.isNotEmpty() && p.none { it == null } }?.map { it!! }
    val a = v?.let(::parts) ?: return true
    val b = parts(than) ?: return true
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x < y
    }
    return false
}

class HostPlugin(private val graph: AppGraph) {
    enum class State { Unknown, Missing, Reported }
    /** Missing = no capabilities route: no plugin, one older than 0.4, or one the dashboard doesn't serve. */
    var state by mutableStateOf(State.Unknown)
        private set
    var version by mutableStateOf<String?>(null)
        private set
    var capabilities by mutableStateOf<Set<String>>(emptySet())
        private set
    var loadedVersion by mutableStateOf<String?>(null)
        private set
    var restartNeeded by mutableStateOf(false)
        private set
    /** On the host but not served by this dashboard (installed, not enabled or dashboard not restarted). */
    var notServed by mutableStateOf(false)
        private set
    var latest by mutableStateOf(RECOMMENDED)
        private set
    /** The update in progress (step text) or its outcome. */
    var progress by mutableStateOf<String?>(null)
    var updating by mutableStateOf(false)
        private set

    fun reports(cap: String) = state == State.Reported && cap in capabilities
    val updateAvailable get() = state == State.Missing && !notServed || state == State.Reported && versionOlder(version, latest)
    val needsAttention get() = updateAvailable || restartNeeded || notServed

    private val client get() = graph.client

    suspend fun refresh() {
        try {
            val r = client.rest("GET", PATH).obj()
            if (r?.bool("ok") != true) return
            state = State.Reported
            version = r.str("version")
            capabilities = r.strings("capabilities").toSet()
            loadedVersion = r.str("loaded_version")
            restartNeeded = r.bool("restart_needed") == true
            notServed = false
        } catch (e: HttpError) {
            if (e.code != 404 && e.code != 405) return
            state = State.Missing
            // Hermes' plugin list tells "not installed" from "installed but not served".
            notServed = runCatching { parsePlugins(client.rest("GET", "/api/dashboard/plugins/hub").obj()) }.getOrNull()
                ?.any { (it.name == "conduit_push" || it.folder == "conduit_push" || it.name == "hermes-conduit-notifier") && !versionOlder(it.version, "0.4.0") } == true
        } catch (_: Exception) {
            return
        }
        if (client.demo) return // demo: no "update available" from the real repo
        runCatching {
            // URL.readText() has no timeout: a server that never answers kept the IO thread (and this check) forever.
            withContext(Dispatchers.IO) {
                java.net.URL(MANIFEST).openConnection().apply { connectTimeout = 10_000; readTimeout = 10_000 }.getInputStream().bufferedReader().use { it.readText() }
            }.lineSequence().firstOrNull { it.startsWith("version:") }
                ?.substringAfter(':')?.trim()?.takeIf { !versionOlder(it, RECOMMENDED) }?.let { latest = it }
        }
    }

    /**
     * Update (or install when missing), restart the gateway, then have the plugin restart the dashboard so it
     * serves the new routes. Each step reports through [progress].
     */
    suspend fun update(install: Boolean) {
        if (updating) return
        updating = true
        try {
            progress = tr(if (install) R.string.hostplugin_installing else R.string.hostplugin_downloading)
            var installs = install
            var force = false
            if (!installs) {
                try {
                    check(client.rest("POST", UPDATE, timeoutSec = 180).obj())
                } catch (e: HttpError) {
                    if (e.code != 400) throw e
                    if (e.message?.contains("not found", true) == true) installs = true
                    else if (e.message?.contains("no owned Git checkout", true) == true) { installs = true; force = true }
                    else throw e
                }
            }
            if (installs) {
                progress = tr(R.string.hostplugin_installing)
                check(client.rest("POST", INSTALL, buildJsonObject { put("identifier", REPO); put("enable", true); if (force) put("force", true) }, timeoutSec = 180).obj())
            }
            if (!restartGateway(client) { progress = tr(R.string.hostplugin_restarting_gateway, it) }) {
                progress = tr(R.string.hostplugin_gateway_down)
                return
            }
            var before: JsonObject? = null
            repeat(3) { if (before == null) { before = runCatching { client.rest("GET", PATH).obj() }.getOrNull(); if (before == null) delay(2000) } }
            val b = before
            if (b == null || !b.containsKey("loaded_version")) { progress = tr(R.string.hostplugin_restart_dashboard); return }
            if (b.bool("restart_needed") != true) { progress = tr(R.string.hostplugin_ready, b.str("loaded_version").orEmpty()); return }
            if ("self-restart" !in b.strings("capabilities")) { progress = tr(R.string.hostplugin_restart_dashboard); return }
            val boot = b.str("boot_id")
            val waitUntil = System.currentTimeMillis() + 600_000
            while (true) {
                try { client.rest("POST", SELF_RESTART); break } catch (e: HttpError) {
                    when {
                        e.code == 409 && System.currentTimeMillis() < waitUntil -> { progress = tr(R.string.hostplugin_waiting_chats); delay(2000) }
                        e.code in setOf(404, 405, 501, 409) -> { progress = tr(R.string.hostplugin_restart_dashboard); return }
                        else -> throw e
                    }
                }
            }
            progress = tr(R.string.hostplugin_restarting_dashboard)
            val until = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < until) {
                delay(2000)
                val r = runCatching { client.rest("GET", PATH).obj() }.getOrNull() ?: continue
                if (r.str("boot_id") == boot) continue
                progress = if (r.bool("restart_needed") == true) tr(R.string.hostplugin_restart_dashboard) else tr(R.string.hostplugin_ready, r.str("loaded_version").orEmpty())
                return
            }
            progress = tr(R.string.hostplugin_dashboard_down)
        } catch (e: HttpError) {
            progress = if (e.code in setOf(404, 405, 501)) tr(R.string.hostplugin_cant_update) else tr(R.string.capabilities_update_failed, e.message.orEmpty())
        } catch (e: Exception) {
            progress = tr(R.string.capabilities_update_failed, e.message.orEmpty())
        } finally {
            updating = false
            refresh()
        }
    }

    private fun check(r: JsonObject?) {
        if (r?.bool("ok") == false) throw HttpError(400, r.str("error") ?: tr(R.string.hostplugin_not_applied))
    }

    companion object {
        const val PATH = "/api/plugins/conduit_push/capabilities"
        const val UPDATE = "/api/dashboard/agent-plugins/conduit_push/update"
        const val INSTALL = "/api/dashboard/agent-plugins/install"
        const val SELF_RESTART = "/api/plugins/conduit_push/self/restart"
        const val REPO = "kaishi00/hermes-conduit-notifier"
        const val RECOMMENDED = "0.48.0"
        const val MANIFEST = "https://raw.githubusercontent.com/kaishi00/hermes-conduit-notifier/main/plugin.yaml"
        /** What the app uses the plugin for (string ids, `%1$s` = agent name), by the capabilities each needs (NotifierPluginFeature). */
        val FEATURES = listOf(
            R.string.hostplugin_feature_artifacts to listOf("artifacts"),
            R.string.hostplugin_feature_takeover to listOf("session-takeover", "desktop-views"),
            R.string.hostplugin_feature_voice_sessions to listOf("voice-sessions", "voice-tags", "voice-summary"),
            R.string.hostplugin_feature_live_voice to listOf("memory", "personality", "web-search"),
            R.string.hostplugin_feature_gemini to listOf("gemini-live"),
            R.string.hostplugin_feature_e2e to listOf("e2e-notifications"),
            R.string.hostplugin_feature_pairing to listOf("pairing"),
            R.string.hostplugin_feature_calls to listOf("hermes-calls"),
            R.string.hostplugin_feature_health to listOf("health-sync"),
            R.string.hostplugin_feature_updates to listOf("self-restart"),
        )
    }
}

fun HostPlugin.summary(): String = when {
    updating -> tr(R.string.capabilities_updating)
    state == HostPlugin.State.Unknown -> tr(R.string.hostplugin_summary_unchecked)
    notServed -> tr(R.string.hostplugin_summary_not_served)
    state == HostPlugin.State.Missing -> tr(R.string.hostplugin_summary_missing)
    updateAvailable -> tr(R.string.hostplugin_summary_update, version.orEmpty())
    restartNeeded -> tr(R.string.hostplugin_summary_restart, version.orEmpty())
    else -> tr(R.string.hostplugin_summary_up_to_date, version.orEmpty())
}

@Composable
fun HostPluginPage(graph: AppGraph) {
    val c = C.colors
    val p = graph.hostPlugin
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { p.refresh() }
    val install = p.state == HostPlugin.State.Missing && !p.notServed
    val agent = profileTitle("default")
    Card(stringResource(R.string.hostplugin_title), AF.Extension) {
        Help(stringResource(R.string.hostplugin_help, agent))
        Row { Text(stringResource(R.string.hostplugin_version), style = CType.subheadline, color = c.secondary, modifier = Modifier.weight(1f)); Text(p.loadedVersion ?: p.version ?: "—", style = CType.subheadline.semibold(), color = c.text) }
        val status = when {
            p.state == HostPlugin.State.Unknown -> stringResource(R.string.hostplugin_status_checking)
            p.notServed -> stringResource(R.string.hostplugin_status_not_served)
            install -> stringResource(R.string.hostplugin_status_missing)
            p.updateAvailable -> stringResource(R.string.hostplugin_status_new_version, p.latest)
            p.restartNeeded -> stringResource(R.string.hostplugin_status_restart)
            else -> stringResource(R.string.hostplugin_status_ok)
        }
        Text(status, style = CType.footnote, color = if (p.needsAttention) c.orange else c.secondary)
        p.progress?.let { Text(it, style = CType.footnote.copy(fontFamily = CType.mono), color = c.text) }
        if (!p.updating && (p.updateAvailable || p.restartNeeded)) SettingsButton(
            when { install -> stringResource(R.string.capabilities_install); p.restartNeeded && !p.updateAvailable -> stringResource(R.string.hostplugin_finish_update); else -> stringResource(R.string.hostplugin_update_to, p.latest) }, prominent = true,
        ) { confirm = true }
    }
    if (p.state == HostPlugin.State.Reported) Card(stringResource(R.string.hostplugin_what_it_does), AF.Checklist) {
        HostPlugin.FEATURES.forEach { (title, caps) ->
            val served = caps.all(p::reports)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (served) AF.CheckCircle else AF.Error, null, tint = if (served) c.success else c.orange, modifier = Modifier.size(16.dp))
                Text("  " + stringResource(title, agent), style = CType.subheadline, color = c.text, modifier = Modifier.weight(1f))
                if (!served) Text(stringResource(R.string.hostplugin_needs_update), style = CType.caption, color = c.orange)
            }
        }
    }
    Card(stringResource(R.string.hostplugin_by_hand), AF.Terminal) {
        Help(stringResource(R.string.hostplugin_by_hand_help))
        Command(1, stringResource(when { p.notServed -> R.string.hostplugin_cmd_enable; install -> R.string.hostplugin_cmd_install; else -> R.string.hostplugin_cmd_update }),
            when { p.notServed -> "hermes plugins enable conduit_push"; install -> "hermes plugins install ${HostPlugin.REPO} --enable"; else -> "hermes plugins update conduit_push" })
        Command(2, stringResource(R.string.hostplugin_cmd_restart_gateway), "hermes gateway restart")
        Help(stringResource(R.string.hostplugin_then_restart_dashboard))
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(if (install) R.string.hostplugin_install_title else R.string.hostplugin_update_title)) },
        text = { Text(stringResource(R.string.hostplugin_confirm_text)) },
        confirmButton = { TextButton({ confirm = false; scope.launch { p.update(install) } }) { Text(stringResource(if (install) R.string.hostplugin_install_restart else R.string.hostplugin_update_restart)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.capabilities_cancel)) } },
    )
}

@Composable
private fun Command(step: Int, title: String, command: String) {
    val c = C.colors
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$step. $title", style = CType.footnote.semibold(), color = c.text)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.text.copy(alpha = 0.06f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(command, style = CType.footnote.copy(fontFamily = CType.mono), color = c.text, modifier = Modifier.weight(1f))
            Icon(AF.ContentCopy, stringResource(R.string.hostplugin_copy), tint = c.accent, modifier = Modifier.size(16.dp).pressable {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("command", command))
            })
        }
    }
}
