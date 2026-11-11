package app.hermesandroid.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.tr
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.semibold
import kotlinx.coroutines.launch
import app.hermesandroid.ui.icons.AF

// Capabilities > Plugins (CapabilityPluginsTab, AgentPluginDetailPage, PluginRestartCard).

@Composable
fun PluginsTab(graph: AppGraph, s: CapabilitiesState, q: String) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<PluginEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.profile) { if (!s.pluginsLoaded) s.loadPlugins() }
    if (!s.pluginsLoaded) { Loading(); return }
    s.pluginsError?.let { Text(it, style = CType.footnote, color = c.red); SettingsButton(stringResource(R.string.capabilities_retry)) { scope.launch { s.loadPlugins() } }; return }
    if (s.pluginsNeedRestart) RestartCard(graph)
    if (!s.canManagePlugins) Help(stringResource(R.string.pluginstab_default_profile_only))
    message?.let { Text(it, style = CType.footnote, color = c.secondary) }
    val installed = s.plugins.filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) }
    CapSection(stringResource(R.string.pluginstab_installed), stringResource(R.string.pluginstab_installed_footer)) {
        if (installed.isEmpty()) Empty(if (q.isEmpty()) stringResource(R.string.pluginstab_none_installed) else stringResource(R.string.capabilities_no_match, q))
        installed.forEachIndexed { i, p ->
            val pills = buildList {
                when (p.source) { "bundled" -> add(stringResource(R.string.pluginstab_bundled) to null); "git" -> add("Git" to null); "user" -> add("Local" to null) }
                p.version?.let { add(it to null) }
                if (s.pluginCatalog.any { it.installed && it.updateAvailable && (it.name == p.name || it.name == p.folder) }) add(stringResource(R.string.pluginstab_update_available) to c.accent)
                if (p.authRequired) add(stringResource(R.string.capabilities_needs_sign_in) to c.orange)
            }
            CapRow(p.name, p.description, i == 0, pills, onClick = { open = p.name }) {
                if (s.pluginBusy[p.name] == true) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                else CapSwitch(p.enabled, enabled = s.canManagePlugins) { on -> scope.launch { runCatching { s.setPluginEnabled(p, on) }.onFailure { message = tr(R.string.capabilities_change_failed, it.message.orEmpty()) } } }
            }
        }
    }
    val catalog = s.pluginCatalog.filter { !it.installed && (q.isEmpty() || it.displayName.contains(q, true) || it.description.contains(q, true)) }
        .sortedWith(compareBy<PluginEntry> { it.tier != "official" }.thenBy { it.displayName.lowercase() })
    if (catalog.isNotEmpty()) CapSection(stringResource(R.string.pluginstab_catalog), stringResource(R.string.pluginstab_catalog_footer)) {
        catalog.forEachIndexed { i, e ->
            val pills = listOf(if (e.tier == "official") stringResource(R.string.capabilities_official) to c.aura else stringResource(R.string.pluginstab_community) to null)
            CapRow(e.displayName, e.description, i == 0, pills, onClick = null) {
                if (s.pluginBusy[e.name] == true) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                else if (s.canManagePlugins) SettingsButton(stringResource(R.string.capabilities_install)) { pendingInstall = e }
            }
        }
    }
    open?.let { name -> PluginSheet(graph, s, name) { open = null } }
    pendingInstall?.let { e ->
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            title = { Text(stringResource(R.string.pluginstab_install_title, e.displayName)) },
            text = { Text(listOf(e.description, e.capabilities).filter { it.isNotBlank() }.joinToString("\n\n")) },
            confirmButton = { TextButton({ pendingInstall = null; scope.launch { message = runCatching { s.installPlugin(e) }.getOrElse { tr(R.string.pluginstab_install_failed, it.message.orEmpty()) } } }) { Text(stringResource(R.string.capabilities_install)) } },
            dismissButton = { TextButton({ pendingInstall = null }) { Text(stringResource(R.string.capabilities_cancel)) } },
        )
    }
}

@Composable
private fun RestartCard(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    Card(null, AF.RestartAlt) {
        Text(stringResource(R.string.pluginstab_restart_needed), style = CType.subheadline.semibold(), color = C.colors.text)
        progress?.let { Text(it, style = CType.footnote.copy(fontFamily = CType.mono), color = C.colors.secondary) }
        SettingsButton(stringResource(R.string.pluginstab_restart_gateway)) { confirm = true }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.pluginstab_restart_title)) },
        text = { Text(stringResource(R.string.pluginstab_restart_text)) },
        confirmButton = { TextButton({ confirm = false; scope.launch { if (restartGateway(graph.client) { progress = it }) graph.capabilities.pluginsNeedRestart = false } }) { Text(stringResource(R.string.pluginstab_restart)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.capabilities_cancel)) } },
    )
}

@Composable
private fun PluginSheet(graph: AppGraph, s: CapabilitiesState, name: String, onDismiss: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var consent by remember { mutableStateOf<List<String>?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val p = s.plugins.firstOrNull { it.name == name }
    fun update(accept: Boolean) = scope.launch {
        runCatching { s.updatePlugin(p!!, accept) }.onSuccess { (msg, delta) -> if (delta != null) consent = delta else message = msg }.onFailure { message = tr(R.string.capabilities_update_failed, it.message.orEmpty()) }
    }
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(name, style = CType.title3.semibold(), color = c.text)
            if (p == null) { Help(stringResource(R.string.pluginstab_gone)); return@Column }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { p.version?.let { Pill(it) }; Pill(p.source.ifEmpty { "?" }) }
            Text(p.description.ifBlank { stringResource(R.string.capabilities_no_description) }, style = CType.subheadline, color = c.secondary)
            if (p.authRequired) Text(p.authCommand?.let { stringResource(R.string.pluginstab_needs_host_sign_in_cmd, it) } ?: stringResource(R.string.pluginstab_needs_host_sign_in), style = CType.footnote, color = c.orange)
            val busy = s.pluginBusy[p.name] == true
            if (s.canManagePlugins) {
                Card(null, AF.Extension) {
                    SwitchRow(stringResource(R.string.capabilities_enabled), stringResource(R.string.pluginstab_enabled_help), p.enabled, enabled = !busy) { on ->
                        scope.launch { runCatching { s.setPluginEnabled(p, on) }.onFailure { message = tr(R.string.capabilities_change_failed, it.message.orEmpty()) } }
                    }
                }
                val entry = s.pluginCatalog.firstOrNull { it.installed && (it.name == p.name || it.name == p.folder) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.canUpdate) SettingsButton(stringResource(if (busy) R.string.capabilities_updating else if (entry?.updateAvailable == true) R.string.pluginstab_update else R.string.pluginstab_check_update)) { if (!busy) update(false) }
                    if (p.canRemove) SettingsButton(stringResource(R.string.pluginstab_uninstall)) { confirmRemove = true }
                }
            } else Help(stringResource(R.string.pluginstab_default_profile_only))
            message?.let { Text(it, style = CType.footnote, color = c.secondary) }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.pluginstab_uninstall_title, name)) },
        text = { Text(stringResource(R.string.pluginstab_uninstall_text)) },
        confirmButton = { TextButton({ confirmRemove = false; scope.launch { runCatching { s.removePlugin(p!!) }.onSuccess { onDismiss() }.onFailure { message = tr(R.string.pluginstab_uninstall_failed, it.message.orEmpty()) } } }) { Text(stringResource(R.string.pluginstab_uninstall), color = c.red) } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.capabilities_cancel)) } },
    )
    consent?.let { lines ->
        AlertDialog(
            onDismissRequest = { consent = null },
            title = { Text(stringResource(R.string.pluginstab_consent_title, name)) },
            text = { Text(stringResource(R.string.pluginstab_consent_text) + if (lines.isNotEmpty()) "\n\n" + lines.joinToString("\n") else "") },
            confirmButton = { TextButton({ consent = null; update(true) }) { Text(stringResource(R.string.pluginstab_apply_update)) } },
            dismissButton = { TextButton({ consent = null }) { Text(stringResource(R.string.capabilities_cancel)) } },
        )
    }
}
