package app.hermesandroid.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.chat.AfMarkdown
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.net.HttpError
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.tasks.Segmented
import app.hermesandroid.tr
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import java.net.URLEncoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import app.hermesandroid.ui.icons.AF

// Settings > Capabilities (CapabilitiesView, CapabilitiesStore): skills, toolsets, MCP connectors and agent plugins
// of the active profile, with one search over all four.

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** DashboardPath.bodyWithProfile: the profile goes in the body only when it isn't default. */
fun JsonObject.withProfile(profile: String) = if (profile == "default" || profile.isEmpty()) this else JsonObject(this + ("profile" to JsonPrimitive(profile)))

fun JsonObject.strings(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

data class Toolset(val name: String, val label: String, val description: String, val enabled: Boolean, val configured: Boolean?, val tools: List<String>)
data class EnvVar(val key: String, val prompt: String, val url: String?, val isSet: Boolean)
data class ToolProvider(val name: String, val badge: String, val tag: String, val env: List<EnvVar>, val nous: String?, val active: Boolean, val status: String, val webBackend: String?, val capabilities: List<String>)
data class ToolsetConfig(val providers: List<ToolProvider>, val searchBackend: String?, val extractBackend: String?, val searchViaNous: Boolean, val extractViaNous: Boolean) {
    /** Web toolsets pick a backend per capability (search / extract). */
    fun serves(cap: String, p: ToolProvider): Boolean {
        val b = p.webBackend ?: return false
        return when (cap) {
            "search" -> b == searchBackend && (p.nous != null) == searchViaNous
            "extract" -> b == extractBackend && (p.nous != null) == extractViaNous
            else -> false
        }
    }
}
data class TerminalBackend(val name: String, val label: String, val description: String, val active: Boolean, val status: String, val detail: String?)

val HIDDEN_TOOLSETS = setOf("discord", "discord_admin", "yuanbao", "context_engine", "moa")

/** A toolset's label without its leading emoji (CapabilityToolsetOrdering.label). */
fun toolsetLabel(label: String?, name: String): String {
    val raw = label?.trim().orEmpty()
    if (raw.isEmpty()) return name.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    return raw.dropWhile { !it.isLetterOrDigit() }.trim().ifEmpty { raw }
}

data class McpServer(val name: String, val transport: String, val url: String?, val command: String?, val args: List<String>, val auth: String?, val enabled: Boolean, val plugin: String?) {
    val canSignIn get() = transport == "http" && url != null && auth != "header" && plugin == null
}
data class McpEnvField(val name: String, val prompt: String, val required: Boolean)
data class McpCatalogEntry(val name: String, val description: String, val transport: String, val authType: String, val env: List<McpEnvField>, val url: String?, val command: String?, val args: List<String>, val installed: Boolean)
/** A `/test` result; `at` decides when it's stale (5 min). */
data class McpProbe(val ok: Boolean, val error: String?, val tools: List<Pair<String, String>>, val at: Long = System.currentTimeMillis())

sealed interface McpState {
    data object Off : McpState
    data object Checking : McpState
    data object Unknown : McpState
    data class On(val tools: Int) : McpState
    data object NeedsSignIn : McpState
    data class Failed(val error: String) : McpState
}

fun mcpState(s: McpServer, probe: McpProbe?, probing: Boolean): McpState = when {
    !s.enabled -> McpState.Off
    probing -> McpState.Checking
    probe == null -> McpState.Unknown
    probe.ok -> McpState.On(probe.tools.size)
    Regex("401|unauthori[sz]ed|forbidden|invalid token|authentication|oauth", RegexOption.IGNORE_CASE).containsMatchIn(probe.error.orEmpty()) -> McpState.NeedsSignIn
    else -> McpState.Failed(probe.error.orEmpty())
}

data class AgentPlugin(
    val name: String, val version: String?, val description: String, val source: String, val status: String,
    val canRemove: Boolean, val canUpdateGit: Boolean, val path: String?, val authRequired: Boolean, val authCommand: String?,
) {
    val enabled get() = status == "enabled"
    /** The install folder, which update/remove address (the name can differ). */
    val folder get() = path?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: name
    val canUpdate get() = canUpdateGit || canRemove
}
data class PluginEntry(val name: String, val title: String?, val description: String, val tier: String, val capabilities: String, val installed: Boolean, val updateAvailable: Boolean) {
    val displayName get() = title ?: name
}

private val LISTED_BUNDLED = setOf("disk-cleanup", "security-guidance")
private val HIDDEN_PLUGIN_PREFIXES = listOf("dashboard_auth/", "model-providers/", "platforms/")

/** `/api/dashboard/plugins/hub` plugins the user manages (AgentPluginRow.list): no bundled internals. */
fun parsePlugins(r: JsonObject?): List<AgentPlugin> = (r?.get("plugins") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
    AgentPlugin(
        o.str("name") ?: return@mapNotNull null, o.str("version")?.takeIf { it.isNotEmpty() }, o.str("description").orEmpty(), o.str("source").orEmpty(),
        o.str("runtime_status") ?: "inactive", o.bool("can_remove") == true, o.bool("can_update_git") == true, o.str("path"),
        o.bool("auth_required") == true, o.str("auth_command")?.takeIf { it.isNotEmpty() },
    )
}.filter { p -> HIDDEN_PLUGIN_PREFIXES.none { p.name.startsWith(it) } && (p.source != "bundled" || p.name in LISTED_BUNDLED) }.sortedBy { it.name.lowercase() }

fun pluginPath(name: String) = name.split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

data class Skill(val name: String, val description: String, val category: String, val enabled: Boolean, val provenance: String?, val usage: Int)
data class OfficialSkill(val name: String, val identifier: String, val description: String, val category: String, val tags: List<String>, val installed: Boolean)

/** SKILL.md split into its frontmatter `key: value` lines and the body (SkillFrontmatter). */
fun parseFrontmatter(text: String): Pair<List<Pair<String, String>>, String> {
    val lines = text.replace("\r\n", "\n").split("\n")
    if (lines.firstOrNull()?.trim() != "---") return emptyList<Pair<String, String>>() to text.trim()
    val close = lines.drop(1).indexOfFirst { it.trim() == "---" }.takeIf { it >= 0 }?.plus(1) ?: return emptyList<Pair<String, String>>() to text.trim()
    val fields = lines.subList(1, close).mapNotNull { line ->
        val first = line.firstOrNull() ?: return@mapNotNull null
        if (first.isWhitespace() || first == '#' || first == '-' || ':' !in line) return@mapNotNull null
        val key = line.substringBefore(':').trim()
        var v = line.substringAfter(':').trim()
        if (v.length >= 2 && (v[0] == '"' || v[0] == '\'') && v.last() == v[0]) v = v.substring(1, v.length - 1)
        if (key.isEmpty() || v.isEmpty()) null else key to v
    }
    return fields to lines.drop(close + 1).joinToString("\n").trim()
}

fun prettyCategory(c: String?) = (c?.trim().takeUnless { it.isNullOrEmpty() } ?: "general").replace('_', ' ').replace('-', ' ')
    .split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/** The capabilities of the active profile; reset when the profile or dashboard changes. */
class CapabilitiesState(private val graph: AppGraph) {
    private val client get() = graph.client
    private fun scoped(path: String) = client.withProfile(path)
    var profile by mutableStateOf("")
        private set

    val skills = mutableStateListOf<Skill>()
    var skillsLoaded by mutableStateOf(false)
    var skillsError by mutableStateOf<String?>(null)
    val official = mutableStateListOf<OfficialSkill>()
    /** Install in progress (null) or its failure, per skill identifier / connector / plugin name. */
    val installs = mutableStateMapOf<String, String?>()

    val toolsets = mutableStateListOf<Toolset>()
    var toolsLoaded by mutableStateOf(false)
    var toolsError by mutableStateOf<String?>(null)
    /** Calls per tool over the last year (`/api/analytics/usage`). */
    var toolCalls by mutableStateOf<Map<String, Int>?>(null)

    fun configure() {
        if (profile == graph.profile) return
        profile = graph.profile
        skills.clear(); skillsLoaded = false; skillsError = null; official.clear(); installs.clear()
        toolsets.clear(); toolsLoaded = false; toolsError = null; toolCalls = null
        servers.clear(); mcpCatalog.clear(); mcpLoaded = false; mcpError = null; probes.clear(); probing.clear()
        plugins.clear(); pluginCatalog.clear(); pluginsLoaded = false; pluginsError = null; pluginBusy.clear(); pluginsNeedRestart = false
    }

    // ---- agent plugins ----

    val plugins = mutableStateListOf<AgentPlugin>()
    val pluginCatalog = mutableStateListOf<PluginEntry>()
    var pluginsLoaded by mutableStateOf(false)
    var pluginsError by mutableStateOf<String?>(null)
    val pluginBusy = mutableStateMapOf<String, Boolean>()
    var pluginsNeedRestart by mutableStateOf(false)
    /** Hermes installs plugins for the host's default profile only. */
    val canManagePlugins get() = profile == "default"

    suspend fun loadPlugins() {
        runCatching { client.rest("GET", scoped("/api/dashboard/plugins/hub"), timeoutSec = 30).obj() }
            .onSuccess { plugins.clear(); plugins.addAll(parsePlugins(it)); pluginsError = null }
            .onFailure { pluginsError = tr(R.string.capabilities_plugins_load_failed, it.message.orEmpty()) }
        runCatching { client.rest("GET", "/api/dashboard/plugins/catalog", timeoutSec = 30).obj() }.getOrNull()?.let { r ->
            pluginCatalog.clear()
            pluginCatalog.addAll((r["entries"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
                PluginEntry(o.str("name") ?: return@mapNotNull null, o.str("title")?.takeIf { it.isNotEmpty() }, o.str("description").orEmpty(), o.str("tier") ?: "community",
                    o.str("capability_summary").orEmpty(), o.bool("installed") == true, o.bool("update_available") == true)
            })
        }
        pluginsLoaded = true
    }

    private suspend fun <T> busy(name: String, block: suspend () -> T): T {
        pluginBusy[name] = true
        try { return block() } finally { pluginBusy.remove(name) }
    }

    suspend fun setPluginEnabled(p: AgentPlugin, on: Boolean) = busy(p.name) {
        val r = client.rest("POST", "/api/dashboard/agent-plugins/${pluginPath(p.name)}/${if (on) "enable" else "disable"}", timeoutSec = 60).obj()
        plugins.indexOfFirst { it.name == p.name }.takeIf { it >= 0 }?.let { plugins[it] = plugins[it].copy(status = if (on) "enabled" else "disabled") }
        if (r?.bool("restart_required") == true) pluginsNeedRestart = true
    }

    /** Null when up to date; else the message, or the added capabilities that need consent first. */
    suspend fun updatePlugin(p: AgentPlugin, accept: Boolean = false): Pair<String?, List<String>?> = busy(p.name) {
        val r = client.rest("POST", "/api/dashboard/agent-plugins/${pluginPath(p.folder)}/update", if (accept) buildJsonObject { put("accept_capabilities", true) } else JsonObject(emptyMap()), timeoutSec = 180).obj()
        if (r?.bool("consent_required") == true) return@busy null to r.strings("delta_lines").ifEmpty { listOfNotNull(r.str("error")) }
        if (r?.bool("unchanged") == true) return@busy tr(R.string.capabilities_plugin_up_to_date, p.name) to null
        val restart = r?.bool("restart_required") != false
        if (restart) pluginsNeedRestart = true
        loadPlugins()
        tr(if (restart) R.string.capabilities_plugin_updated_restart else R.string.capabilities_plugin_updated, p.name) to null
    }

    suspend fun removePlugin(p: AgentPlugin) = busy(p.name) {
        client.rest("DELETE", "/api/dashboard/agent-plugins/${pluginPath(p.folder)}", timeoutSec = 60)
        plugins.removeAll { it.name == p.name }
        pluginsNeedRestart = true
        loadPlugins()
    }

    suspend fun installPlugin(e: PluginEntry): String = busy(e.name) {
        val r = client.rest("POST", "/api/dashboard/agent-plugins/install", buildJsonObject { put("identifier", ""); put("catalog_name", e.name); put("enable", true) }, timeoutSec = 180).obj()
        val name = r?.str("plugin_name") ?: e.displayName
        val missing = (r?.get("missing_env") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: it.obj()?.str("name") }
        val restart = r?.bool("restart_required") == true
        if (restart) pluginsNeedRestart = true
        loadPlugins()
        when {
            missing.isNotEmpty() -> tr(R.string.capabilities_plugin_needs_key, name, missing.joinToString(", "))
            restart -> tr(R.string.capabilities_plugin_installed_restart, name)
            else -> tr(R.string.capabilities_plugin_installed_loaded, name)
        }
    }

    // ---- MCP connectors ----

    val servers = mutableStateListOf<McpServer>()
    val mcpCatalog = mutableStateListOf<McpCatalogEntry>()
    var mcpLoaded by mutableStateOf(false)
    var mcpError by mutableStateOf<String?>(null)
    val probes = mutableStateMapOf<String, McpProbe>()
    val probing = mutableStateMapOf<String, Boolean>()

    suspend fun loadConnectors() {
        runCatching { client.rest("GET", scoped("/api/mcp/servers"), timeoutSec = 30).obj() }.onSuccess { r ->
            servers.clear()
            servers.addAll((r?.get("servers") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
                McpServer(
                    o.str("name") ?: return@mapNotNull null, o.str("transport") ?: "unknown", o.str("url")?.takeIf { it.isNotEmpty() }, o.str("command")?.takeIf { it.isNotEmpty() },
                    o.strings("args"), o.str("auth")?.takeIf { it.isNotEmpty() }, o.bool("enabled") != false, if (o.str("source") == "plugin") o.str("plugin").orEmpty() else null,
                )
            })
            mcpError = null
            probes.keys.retainAll(servers.map { it.name }.toSet())
        }.onFailure { mcpError = tr(R.string.capabilities_mcp_load_failed, it.message.orEmpty()) }
        runCatching { client.rest("GET", scoped("/api/mcp/catalog"), timeoutSec = 30).obj() }.getOrNull()?.let { r ->
            mcpCatalog.clear()
            mcpCatalog.addAll((r["entries"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
                McpCatalogEntry(
                    o.str("name") ?: return@mapNotNull null, o.str("description").orEmpty(), o.str("transport").orEmpty(), o.str("auth_type") ?: "none",
                    (o["required_env"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { f -> f.str("name")?.let { McpEnvField(it, f.str("prompt")?.takeIf { p -> p.isNotEmpty() } ?: it, f.bool("required") != false) } },
                    o.str("url")?.takeIf { it.isNotEmpty() }, o.str("command")?.takeIf { it.isNotEmpty() }, o.strings("args"), o.bool("installed") == true,
                )
            })
        }
        mcpLoaded = true
    }

    suspend fun probe(name: String) {
        if (probing[name] == true) return
        probing[name] = true
        probes[name] = runCatching {
            val o = client.rest("POST", scoped("/api/mcp/servers/${enc(name)}/test"), timeoutSec = 60).obj()
            McpProbe(o?.bool("ok") == true, o?.str("error")?.takeIf { it.isNotEmpty() }, (o?.get("tools") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { t -> t.str("name")?.let { it to t.str("description").orEmpty() } })
        }.getOrElse { McpProbe(false, it.message, emptyList()) }
        probing.remove(name)
    }

    /** Tests the enabled servers whose result is missing or older than 5 min, three at a time. */
    suspend fun probeAll(force: Boolean = false) = kotlinx.coroutines.coroutineScope {
        val due = servers.filter { s -> s.enabled && probing[s.name] != true && (force || probes[s.name]?.let { System.currentTimeMillis() - it.at > 300_000 } != false) }
        due.chunked(3).forEach { batch -> batch.map { launch { probe(it.name) } }.forEach { it.join() } }
    }

    suspend fun setServerEnabled(name: String, on: Boolean): String? {
        val i = servers.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
        servers[i] = servers[i].copy(enabled = on)
        return runCatching { client.rest("PUT", scoped("/api/mcp/servers/${enc(name)}/enabled"), buildJsonObject { put("enabled", on) }.withProfile(graph.profile)) }
            .fold({ if (on) probe(name) else probes.remove(name); null }) { e ->
                servers.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { servers[it] = servers[it].copy(enabled = !on) }
                tr(R.string.capabilities_change_failed, e.message.orEmpty())
            }
    }

    suspend fun removeServer(name: String) {
        client.rest("DELETE", scoped("/api/mcp/servers/${enc(name)}"))
        servers.removeAll { it.name == name }
        probes.remove(name)
        mcpCatalog.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { mcpCatalog[it] = mcpCatalog[it].copy(installed = false) }
    }

    suspend fun installConnector(e: McpCatalogEntry, env: Map<String, String>): Boolean {
        installs[e.name] = null
        val failure = runCatching {
            val r = client.rest("POST", scoped("/api/mcp/catalog/install"), buildJsonObject {
                put("name", e.name); put("env", JsonObject(env.mapValues { JsonPrimitive(it.value) })); put("enable", true)
            }.withProfile(graph.profile), timeoutSec = 60).obj()
            if (r?.bool("background") == true) r.str("action")?.let { waitForAction(it) } else null
        }.getOrElse { it.message ?: tr(R.string.capabilities_error) }
        if (failure != null) { installs[e.name] = failure; return false }
        installs.remove(e.name)
        loadConnectors()
        probe(e.name)
        return true
    }

    /** Starts an MCP OAuth sign-in on the host: the flow id and the page to open. */
    suspend fun startSignIn(name: String): Pair<String, String> {
        val r = client.rest("POST", scoped("/api/mcp/servers/${enc(name)}/auth"), timeoutSec = 45).obj()
        r?.str("error")?.takeIf { it.isNotEmpty() }?.let { throw HttpError(400, it) }
        val flow = r?.str("flow_id"); val url = r?.str("authorization_url")
        if (flow == null || url == null) throw HttpError(400, tr(R.string.capabilities_no_sign_in_page))
        return flow to url
    }

    suspend fun signInStatus(flow: String): Pair<String, String?> = client.rest("GET", "/api/mcp/oauth/flows/${enc(flow)}").obj().let { (it?.str("status").orEmpty()) to it?.str("error")?.takeIf { e -> e.isNotEmpty() } }
    suspend fun cancelSignIn(flow: String) { runCatching { client.rest("DELETE", "/api/mcp/oauth/flows/${enc(flow)}") } }

    // ---- toolsets ----

    suspend fun loadToolsets() {
        runCatching { client.rest("GET", scoped("/api/tools/toolsets")) as? JsonArray }.onSuccess { arr ->
            toolsets.clear()
            toolsets.addAll(arr.orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
                val name = o.str("name") ?: return@mapNotNull null
                Toolset(name, toolsetLabel(o.str("label"), name), o.str("description").orEmpty(), o.bool("enabled") == true, o.bool("configured"), o.strings("tools"))
            }.filter { it.name !in HIDDEN_TOOLSETS })
            toolsError = null
        }.onFailure { toolsError = tr(R.string.capabilities_tools_load_failed, it.message.orEmpty()) }
        toolsLoaded = true
    }

    suspend fun loadToolCalls() {
        val r = runCatching { client.rest("GET", scoped("/api/analytics/usage?days=365"), timeoutSec = 30).obj() }.getOrNull() ?: return
        toolCalls = (r["tools"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { o -> o.str("tool")?.let { it to (o.num("count")?.toInt() ?: 0) } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    }

    fun calls(t: Toolset) = t.tools.sumOf { toolCalls?.get(it) ?: 0 }

    suspend fun toggleToolset(name: String, on: Boolean): String? {
        val i = toolsets.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
        toolsets[i] = toolsets[i].copy(enabled = on)
        return runCatching { client.rest("PUT", "/api/tools/toolsets/${enc(name)}", buildJsonObject { put("enabled", on); put("profile", graph.profile) }) }
            .fold({ null }) { e -> toolsets.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { toolsets[it] = toolsets[it].copy(enabled = !on) }; tr(R.string.capabilities_change_failed, e.message.orEmpty()) }
    }

    suspend fun toolsetConfig(name: String): ToolsetConfig {
        val o = client.rest("GET", scoped("/api/tools/toolsets/${enc(name)}/config"), timeoutSec = 30).obj() ?: JsonObject(emptyMap())
        val providers = (o["providers"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { p ->
            ToolProvider(
                p.str("name") ?: return@mapNotNull null, p.str("badge").orEmpty(), p.str("tag").orEmpty(),
                (p["env_vars"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { e ->
                    val key = e.str("key") ?: return@mapNotNull null
                    EnvVar(key, e.str("prompt")?.takeIf { it.isNotEmpty() } ?: key, e.str("url"), e.bool("is_set") == true)
                },
                p.str("managed_nous_feature"), p.bool("is_active") == true, p.str("status").orEmpty(), p.str("web_backend"), p.strings("capabilities"),
            )
        }
        return ToolsetConfig(providers, o.str("active_search_backend"), o.str("active_extract_backend"), o.bool("search_via_nous") == true, o.bool("extract_via_nous") == true)
    }

    /** True when the backend needs a Nous Portal sign-in on the host. */
    suspend fun selectProvider(toolset: String, provider: String, capability: String?): Boolean =
        client.rest("PUT", scoped("/api/tools/toolsets/${enc(toolset)}/provider"), buildJsonObject { put("provider", provider); capability?.let { put("capability", it) } }.withProfile(graph.profile))
            .obj()?.bool("needs_nous_auth") == true

    suspend fun terminalBackends(): List<TerminalBackend> =
        (client.rest("GET", scoped("/api/tools/terminal/backends"), timeoutSec = 30).obj()?.get("backends") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { b ->
            val name = b.str("name") ?: return@mapNotNull null
            TerminalBackend(name, b.str("label")?.takeIf { it.isNotEmpty() } ?: name, b.str("description").orEmpty(), b.bool("active") == true, b.str("status").orEmpty(), b.str("detail")?.takeIf { it.isNotEmpty() })
        }

    suspend fun selectTerminalBackend(name: String) { client.rest("PUT", scoped("/api/tools/terminal/backend"), buildJsonObject { put("backend", name) }.withProfile(graph.profile)) }

    /** Saved to the profile's .env on the host; never read back. */
    suspend fun saveKey(key: String, value: String) { client.rest("PUT", scoped("/api/env"), buildJsonObject { put("key", key); put("value", value) }.withProfile(graph.profile)) }

    /** Polls a background Hermes action (`/api/actions/{name}/status`) until it stops; null on success, else why. */
    suspend fun waitForAction(name: String, limitMs: Long = 900_000): String? {
        val until = System.currentTimeMillis() + limitMs
        var failures = 0
        while (System.currentTimeMillis() < until) {
            delay(1200)
            val st = runCatching { client.rest("GET", "/api/actions/${enc(name)}/status?lines=200").obj() }.getOrElse { if (++failures >= 4) throw it; null } ?: continue
            failures = 0
            if (st.bool("running") == true) continue
            if (st.num("exit_code")?.toInt() == 0) return null
            return st.strings("lines").reversed().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("===") } ?: tr(R.string.capabilities_install_unfinished)
        }
        return tr(R.string.capabilities_still_working)
    }

    // ---- skills ----

    suspend fun loadSkills() {
        runCatching { client.rest("GET", scoped("/api/skills")) as? JsonArray }.onSuccess { arr ->
            skills.clear()
            skills.addAll(arr.orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
                Skill(o.str("name") ?: return@mapNotNull null, o.str("description").orEmpty(), o.str("category").orEmpty(), o.bool("enabled") == true, o.str("provenance"), o.num("usage")?.toInt() ?: 0)
            })
            skillsError = null
        }.onFailure { skillsError = tr(R.string.capabilities_skills_load_failed, it.message.orEmpty()) }
        skillsLoaded = true
    }

    suspend fun loadOfficial() {
        val r = runCatching { client.rest("GET", scoped("/api/skills/hub/official"), timeoutSec = 30).obj() }.getOrNull() ?: return
        official.clear()
        official.addAll((r["skills"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { o ->
            val name = o.str("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            OfficialSkill(name, o.str("identifier")?.takeIf { it.isNotEmpty() } ?: name, o.str("description").orEmpty(), o.str("category")?.takeIf { it.isNotEmpty() } ?: "general", o.strings("tags"), o.bool("installed") == true)
        })
    }

    /** Optimistic; a failure puts the switch back. Changes apply to new sessions. */
    suspend fun toggleSkill(name: String, on: Boolean): String? {
        val i = skills.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
        skills[i] = skills[i].copy(enabled = on)
        return runCatching { client.rest("PUT", "/api/skills/toggle", buildJsonObject { put("name", name); put("enabled", on); put("profile", graph.profile) }) }
            .fold({ null }) { e -> skills.indexOfFirst { it.name == name }.takeIf { it >= 0 }?.let { skills[it] = skills[it].copy(enabled = !on) }; tr(R.string.capabilities_change_failed, e.message.orEmpty()) }
    }

    suspend fun skillContent(name: String) = client.rest("GET", scoped("/api/skills/content?name=${enc(name)}")).obj()?.str("content").orEmpty()
    suspend fun skillPreview(identifier: String) = client.rest("GET", scoped("/api/skills/hub/preview?identifier=${enc(identifier)}"), timeoutSec = 45).obj()?.str("skill_md").orEmpty()

    suspend fun installSkill(s: OfficialSkill): Boolean {
        installs[s.identifier] = null
        val failure = runCatching {
            val started = client.rest("POST", scoped("/api/skills/hub/install"), buildJsonObject { put("identifier", s.identifier) }.withProfile(graph.profile), timeoutSec = 45).obj()
            val action = started?.str("name") ?: return@runCatching tr(R.string.capabilities_install_not_started)
            waitForAction(action)
        }.getOrElse { it.message ?: tr(R.string.capabilities_error) }
        if (failure != null) { installs[s.identifier] = failure; return false }
        installs.remove(s.identifier)
        official.indexOfFirst { it.identifier == s.identifier }.takeIf { it >= 0 }?.let { official[it] = official[it].copy(installed = true) }
        loadSkills()
        return true
    }
}

// ---- shared UI ----

@Composable
fun Pill(text: String, tint: Color = C.colors.secondary) {
    Text(text, style = CType.caption2.semibold(), color = tint, modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 2.dp))
}

@Composable
fun CapSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = C.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = CType.footnote.semibold(), color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(20.dp)), content = content)
        if (footer != null) Text(footer, style = CType.caption, color = c.secondary, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** One capability row: title, subtitle, pills, trailing control; tap opens its detail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapRow(title: String, subtitle: String?, first: Boolean, pills: List<Pair<String, Color?>> = emptyList(), onClick: (() -> Unit)?, trailing: @Composable () -> Unit = {}) {
    val c = C.colors
    if (!first) app.hermesandroid.ui.components.Divider(inset = 16.dp)
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(haptic = Haptic.Light, onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = CType.callout.semibold(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = CType.footnote, color = c.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pills.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) { pills.forEach { (t, tint) -> Pill(t, tint ?: c.secondary) } }
        }
        trailing()
    }
}

@Composable
fun CapSwitch(on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = C.colors
    Switch(on, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
}

@Composable
fun Empty(text: String) = Text(text, style = CType.subheadline, color = C.colors.secondary, modifier = Modifier.padding(16.dp))

@Composable
fun Loading() = Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = C.colors.accent) }

enum class CapTab(@StringRes val title: Int) { Skills(R.string.capabilities_tab_skills), Tools(R.string.capabilities_tools_title), Connectors(R.string.capabilities_tab_connectors), Plugins(R.string.capabilities_tab_plugins) }

@Composable
fun CapabilitiesPage(graph: AppGraph) {
    val c = C.colors
    val s = graph.capabilities
    LaunchedEffect(graph.profile) { s.configure() }
    if (s.profile != graph.profile) return
    var tab by remember { mutableStateOf(CapTab.Skills) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(c.text.copy(alpha = 0.06f)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AF.Search, null, tint = c.secondary, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.capabilities_search, stringResource(tab.title).lowercase()), style = CType.subheadline, color = c.tertiary)
            BasicTextField(query, { query = it }, singleLine = true, textStyle = CType.subheadline.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) Icon(AF.Close, stringResource(R.string.capabilities_clear), tint = c.secondary, modifier = Modifier.size(18.dp).pressable { query = "" })
    }
    Segmented(CapTab.entries.map { stringResource(it.title) }, tab.ordinal) { tab = CapTab.entries[it] }
    when (tab) {
        CapTab.Skills -> SkillsTab(s, q)
        CapTab.Tools -> ToolsTab(s, q)
        CapTab.Connectors -> ConnectorsTab(s, q)
        CapTab.Plugins -> PluginsTab(graph, s, q)
    }
}

// ---- skills tab (CapabilitySkillsTab) ----

private fun Skill.matches(q: String) = q.isEmpty() || name.contains(q, true) || description.contains(q, true) || category.contains(q, true)
private fun OfficialSkill.matches(q: String) = q.isEmpty() || name.contains(q, true) || description.contains(q, true) || category.contains(q, true) || tags.any { it.contains(q, true) }

@Composable
private fun SkillsTab(s: CapabilitiesState, q: String) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var mostUsedFirst by remember { mutableStateOf(true) }
    var openSkill by remember { mutableStateOf<String?>(null) }
    var openOfficial by remember { mutableStateOf<OfficialSkill?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.profile) {
        if (!s.skillsLoaded) s.loadSkills()
        if (s.official.isEmpty()) s.loadOfficial()
    }
    if (!s.skillsLoaded) { Loading(); return }
    s.skillsError?.let { Text(it, style = CType.footnote, color = c.red); SettingsButton(stringResource(R.string.capabilities_retry)) { scope.launch { s.loadSkills() } }; return }
    error?.let { Text(it, style = CType.footnote, color = c.red) }
    val installed = s.skills.filter { it.matches(q) }.sortedWith(compareBy<Skill> { if (mostUsedFirst) -it.usage else it.usage }.thenBy { it.name.lowercase() })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.capabilities_skills_active, s.skills.count { it.enabled }, s.skills.size), style = CType.footnote, color = c.secondary, modifier = Modifier.weight(1f).padding(start = 16.dp))
        Text(stringResource(if (mostUsedFirst) R.string.capabilities_most_used_first else R.string.capabilities_least_used_first), style = CType.footnote.semibold(), color = c.accent, modifier = Modifier.pressable { mostUsedFirst = !mostUsedFirst }.padding(8.dp))
    }
    CapSection(stringResource(R.string.capabilities_installed), stringResource(R.string.capabilities_new_sessions)) {
        if (installed.isEmpty()) Empty(if (q.isEmpty()) stringResource(R.string.capabilities_no_skills) else stringResource(R.string.capabilities_no_match, q))
        installed.forEachIndexed { i, sk ->
            val pills = buildList {
                add(prettyCategory(sk.category) to null)
                when (sk.provenance) { "agent" -> add(stringResource(R.string.capabilities_learned) to c.success); "hub" -> add("Hub" to null) }
                if (sk.usage > 0) add(pluralStringResource(R.plurals.capabilities_uses, sk.usage, sk.usage) to null)
            }
            CapRow(sk.name, sk.description, i == 0, pills, onClick = { openSkill = sk.name }) {
                CapSwitch(sk.enabled) { on -> scope.launch { error = s.toggleSkill(sk.name, on) } }
            }
        }
    }
    val available = s.official.filter { !it.installed && s.skills.none { sk -> sk.name == it.name } && it.matches(q) }.sortedBy { it.name.lowercase() }
    if (available.isNotEmpty()) CapSection(stringResource(R.string.capabilities_available_to_install), stringResource(R.string.capabilities_official_catalog_footer)) {
        available.forEachIndexed { i, o ->
            val busy = s.installs.containsKey(o.identifier) && s.installs[o.identifier] == null
            CapRow(o.name, s.installs[o.identifier] ?: o.description, i == 0, listOf(prettyCategory(o.category) to null), onClick = { openOfficial = o }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                else SettingsButton(stringResource(R.string.capabilities_install)) { scope.launch { s.installSkill(o) } }
            }
        }
    }
    openSkill?.let { name -> SkillSheet(s, name) { openSkill = null } }
    openOfficial?.let { o -> OfficialSkillSheet(s, o) { openOfficial = null } }
}

@Composable
private fun SkillDocument(text: String?, error: String?) {
    val c = C.colors
    when {
        error != null -> Text(error, style = CType.footnote, color = c.red)
        text == null -> CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
        else -> {
            val (fields, body) = parseFrontmatter(text)
            if (fields.isNotEmpty()) Card(stringResource(R.string.capabilities_details), AF.ListAlt) {
                fields.forEach { (k, v) -> Row { Text(k, style = CType.footnote.semibold(), color = c.secondary, modifier = Modifier.weight(0.35f)); Text(v, style = CType.footnote, color = c.text, modifier = Modifier.weight(0.65f)) } }
            }
            if (body.isNotEmpty()) Card(stringResource(R.string.capabilities_instructions), AF.Description) { AfMarkdown(body, 0.85f) }
        }
    }
}

@Composable
private fun SkillSheet(s: CapabilitiesState, name: String, onDismiss: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name) { runCatching { s.skillContent(name) }.onSuccess { text = it }.onFailure { error = tr(R.string.capabilities_skill_read_failed, it.message.orEmpty()) } }
    val sk = s.skills.firstOrNull { it.name == name }
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AF.AutoAwesome, null, tint = c.accent)
                Text(name, style = CType.title3.semibold(), color = c.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            sk?.let {
                Text(it.description.ifBlank { stringResource(R.string.capabilities_no_description) }, style = CType.subheadline, color = c.secondary)
                Card(null, AF.AutoAwesome) {
                    SwitchRow(stringResource(R.string.capabilities_skill_enabled), stringResource(R.string.capabilities_new_sessions), it.enabled) { on -> scope.launch { error = s.toggleSkill(name, on) } }
                }
            }
            SkillDocument(text, error)
        }
    }
}

@Composable
private fun OfficialSkillSheet(s: CapabilitiesState, o: OfficialSkill, onDismiss: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(o.identifier) { runCatching { s.skillPreview(o.identifier) }.onSuccess { text = it }.onFailure { error = tr(R.string.capabilities_preview_read_failed, it.message.orEmpty()) } }
    val installed = s.official.firstOrNull { it.identifier == o.identifier }?.installed == true
    val busy = s.installs.containsKey(o.identifier) && s.installs[o.identifier] == null
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AF.Download, null, tint = c.accent)
                Text(o.name, style = CType.title3.semibold(), color = c.text, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Pill(stringResource(R.string.capabilities_official), c.aura)
            }
            Text(o.description.ifBlank { stringResource(R.string.capabilities_no_description) }, style = CType.subheadline, color = c.secondary)
            when {
                installed -> Text(stringResource(R.string.capabilities_skill_installed), style = CType.subheadline.semibold(), color = c.success)
                busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp); Text("  " + stringResource(R.string.capabilities_installing), style = CType.subheadline, color = c.secondary) }
                else -> SettingsButton(stringResource(R.string.capabilities_install), prominent = true) { scope.launch { s.installSkill(o) } }
            }
            s.installs[o.identifier]?.let { Text(it, style = CType.footnote, color = c.red) }
            SkillDocument(text, error)
        }
    }
}
