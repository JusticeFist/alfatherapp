package app.hermesandroid.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hermesandroid.R
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.tr
import app.hermesandroid.ui.components.AfField
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.hermesandroid.ui.icons.AF

// Capabilities > Connectors (CapabilityConnectorsTab, ConnectorDetailPage, ConnectorInstallSheet).

@Composable
private fun stateLabel(st: McpState): Pair<String, Color> {
    val c = C.colors
    return when (st) {
        McpState.Off -> stringResource(R.string.connectorstab_off) to c.secondary
        McpState.Checking -> stringResource(R.string.connectorstab_testing) to c.secondary
        McpState.Unknown -> stringResource(R.string.connectorstab_untested) to c.secondary
        is McpState.On -> (if (st.tools > 0) pluralStringResource(R.plurals.capabilities_tools, st.tools, st.tools) else stringResource(R.string.connectorstab_active)) to c.success
        McpState.NeedsSignIn -> stringResource(R.string.capabilities_needs_sign_in) to c.orange
        is McpState.Failed -> stringResource(R.string.capabilities_error) to c.red
    }
}

@Composable
private fun McpServer.source(catalog: List<McpCatalogEntry>) = when {
    plugin != null -> if (plugin.isEmpty()) stringResource(R.string.connectorstab_source_plugin) else stringResource(R.string.connectorstab_source_plugin_named, plugin)
    catalog.any { it.name == name } -> stringResource(R.string.connectorstab_source_catalog)
    else -> stringResource(R.string.connectorstab_source_custom)
}

@Composable
fun ConnectorsTab(s: CapabilitiesState, q: String) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<McpCatalogEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.profile) {
        if (!s.mcpLoaded) s.loadConnectors()
        s.probeAll()
    }
    if (!s.mcpLoaded) { Loading(); return }
    s.mcpError?.let { Text(it, style = CType.footnote, color = c.red); SettingsButton(stringResource(R.string.capabilities_retry)) { scope.launch { s.loadConnectors(); s.probeAll() } }; return }
    error?.let { Text(it, style = CType.footnote, color = c.red) }
    fun matches(vararg t: String?) = q.isEmpty() || t.any { it?.contains(q, true) == true }
    val visible = s.servers.filter { matches(it.name, it.url, it.command, it.plugin) }
    // Problems first (CapabilityConnectorsTab rank), then by name.
    val rank = { sv: McpServer -> when (mcpState(sv, s.probes[sv.name], s.probing[sv.name] == true)) { is McpState.Failed -> 0; McpState.NeedsSignIn -> 1; McpState.Unknown -> 2; McpState.Checking -> 3; is McpState.On -> 4; McpState.Off -> 5 } }
    val connected = visible.filter { it.enabled }.sortedWith(compareBy(rank).thenBy { it.name.lowercase() })
    val off = visible.filter { !it.enabled }.sortedBy { it.name.lowercase() }
    val available = s.mcpCatalog.filter { !it.installed && s.servers.none { sv -> sv.name == it.name } && matches(it.name, it.description) }.sortedBy { it.name.lowercase() }
    if (connected.isEmpty() && off.isEmpty() && available.isEmpty()) {
        Empty(if (q.isEmpty()) stringResource(R.string.connectorstab_empty, profileTitle(s.profile)) else stringResource(R.string.capabilities_no_match, q))
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(pluralStringResource(R.plurals.connectorstab_servers, s.servers.size, s.servers.size), style = CType.footnote, color = c.secondary, modifier = Modifier.weight(1f).padding(start = 16.dp))
        Text(stringResource(R.string.connectorstab_test_all), style = CType.footnote.semibold(), color = c.accent, modifier = Modifier.pressable { scope.launch { s.probeAll(force = true) } }.padding(8.dp))
    }
    if (connected.isNotEmpty()) CapSection(stringResource(R.string.connectorstab_connected), stringResource(R.string.connectorstab_connected_footer)) {
        connected.forEachIndexed { i, sv -> ServerRow(s, sv, i == 0, { open = sv.name }) { error = it } }
    }
    if (available.isNotEmpty()) CapSection(stringResource(R.string.connectorstab_available), stringResource(R.string.connectorstab_catalog_footer)) {
        available.forEachIndexed { i, e ->
            val busy = s.installs.containsKey(e.name) && s.installs[e.name] == null
            CapRow(e.name, s.installs[e.name] ?: e.description, i == 0, listOfNotNull(stringResource(R.string.connectorstab_source_catalog) to null, if (e.authType == "oauth") stringResource(R.string.connectorstab_sign_in_pill) to null else null), onClick = null) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                else SettingsButton(stringResource(R.string.capabilities_install)) { s.installs.remove(e.name); installing = e }
            }
        }
    }
    if (off.isNotEmpty()) CapSection(stringResource(R.string.connectorstab_off_section), stringResource(R.string.connectorstab_off_footer)) {
        off.forEachIndexed { i, sv -> ServerRow(s, sv, i == 0, { open = sv.name }) { error = it } }
    }
    open?.let { name -> ConnectorSheet(s, name) { open = null } }
    installing?.let { e -> InstallConnectorDialog(e, onDismiss = { installing = null }) { env -> installing = null; scope.launch { s.installConnector(e, env) } } }
}

@Composable
private fun ServerRow(s: CapabilitiesState, sv: McpServer, first: Boolean, onOpen: () -> Unit, onError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val st = mcpState(sv, s.probes[sv.name], s.probing[sv.name] == true)
    val (label, tint) = stateLabel(st)
    val detail = when (st) {
        McpState.NeedsSignIn -> stringResource(R.string.connectorstab_sign_in_to_answer)
        is McpState.Failed -> st.error
        else -> sv.url ?: (listOfNotNull(sv.command) + sv.args).joinToString(" ")
    }
    CapRow(sv.name, detail, first, listOf(sv.source(s.mcpCatalog) to null, label to tint), onClick = onOpen) {
        CapSwitch(sv.enabled, enabled = sv.plugin == null) { on -> scope.launch { onError(s.setServerEnabled(sv.name, on)) } }
    }
}

@Composable
private fun ConnectorSheet(s: CapabilitiesState, name: String, onDismiss: () -> Unit) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var flow by remember { mutableStateOf<String?>(null) }
    var signIn by remember { mutableStateOf<Job?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val sv = s.servers.firstOrNull { it.name == name }
    fun close() { signIn?.cancel(); flow?.let { f -> scope.launch { s.cancelSignIn(f) } }; onDismiss() }
    AfSheet(::close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(name, style = CType.title3.semibold(), color = c.text)
            if (sv == null) { Help(stringResource(R.string.connectorstab_gone)); return@Column }
            val st = mcpState(sv, s.probes[name], s.probing[name] == true)
            val (label, tint) = stateLabel(st)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Pill(sv.source(s.mcpCatalog)); Pill(label, tint) }
            Card(stringResource(R.string.connectorstab_connection), AF.Link) {
                sv.url?.let { Row { Text(stringResource(R.string.connectorstab_address), style = CType.footnote.semibold(), color = c.secondary, modifier = Modifier.weight(0.3f)); Text(it, style = CType.footnote, color = c.text, modifier = Modifier.weight(0.7f)) } }
                sv.command?.let { cmd -> Row { Text(stringResource(R.string.connectorstab_command), style = CType.footnote.semibold(), color = c.secondary, modifier = Modifier.weight(0.3f)); Text((listOf(cmd) + sv.args).joinToString(" "), style = CType.footnote.copy(fontFamily = CType.mono), color = c.text, modifier = Modifier.weight(0.7f)) } }
                if (sv.plugin != null) Help(if (sv.plugin.isEmpty()) stringResource(R.string.connectorstab_from_plugin) else stringResource(R.string.connectorstab_from_plugin_named, sv.plugin))
                else SwitchRow(stringResource(R.string.capabilities_enabled), stringResource(R.string.capabilities_new_sessions), sv.enabled) { on -> scope.launch { message = s.setServerEnabled(name, on) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sv.enabled) SettingsButton(stringResource(if (st == McpState.Checking) R.string.connectorstab_testing else R.string.connectorstab_test_connection)) { scope.launch { s.probe(name) } }
                    if (sv.canSignIn) {
                        if (flow != null) SettingsButton(stringResource(R.string.connectorstab_stop_waiting)) { signIn?.cancel(); flow?.let { f -> scope.launch { s.cancelSignIn(f) } }; flow = null; message = null }
                        else SettingsButton(stringResource(R.string.connectorstab_sign_in)) {
                            signIn = scope.launch {
                                runCatching {
                                    val (id, url) = s.startSignIn(name)
                                    flow = id
                                    message = tr(R.string.connectorstab_finish_in_browser)
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    repeat(150) {
                                        delay(2000)
                                        val (status, err) = s.signInStatus(id)
                                        if (status == "approved") { flow = null; message = tr(R.string.connectorstab_signed_in); s.probe(name); return@launch }
                                        if (status == "error") { flow = null; message = err ?: tr(R.string.connectorstab_sign_in_unfinished); return@launch }
                                    }
                                    flow = null; message = tr(R.string.connectorstab_sign_in_expired)
                                }.onFailure { if (it !is kotlinx.coroutines.CancellationException) { flow = null; message = it.message } }
                            }
                        }
                    }
                }
                message?.let { Help(it) }
            }
            Card(stringResource(R.string.capabilities_tools_title), AF.Build) {
                val probe = s.probes[name]
                when {
                    !sv.enabled -> Help(stringResource(R.string.connectorstab_tools_off, name))
                    st == McpState.Checking -> Help(stringResource(R.string.connectorstab_reading_tools))
                    st == McpState.NeedsSignIn -> Help(stringResource(R.string.connectorstab_sign_in_for_tools, name))
                    probe == null -> Help(stringResource(R.string.connectorstab_list_unavailable))
                    !probe.ok -> Text(probe.error ?: stringResource(R.string.capabilities_error), style = CType.footnote, color = c.red)
                    probe.tools.isEmpty() -> Help(stringResource(R.string.connectorstab_no_tools))
                    else -> probe.tools.forEach { (n, d) ->
                        Column { Text(n, style = CType.footnote.copy(fontFamily = CType.mono), color = c.text); if (d.isNotBlank()) Text(d, style = CType.caption, color = c.secondary, maxLines = 3) }
                    }
                }
            }
            if (sv.plugin == null) SettingsButton(stringResource(R.string.connectorstab_remove_server)) { confirmRemove = true }
            Help(stringResource(R.string.capabilities_new_sessions))
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(stringResource(R.string.connectorstab_remove_title, name)) },
        text = { Text(stringResource(R.string.connectorstab_remove_text)) },
        confirmButton = { TextButton({ confirmRemove = false; scope.launch { runCatching { s.removeServer(name) }.onSuccess { onDismiss() }.onFailure { message = tr(R.string.connectorstab_remove_failed, it.message.orEmpty()) } } }) { Text(stringResource(R.string.connectorstab_remove), color = c.red) } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.capabilities_cancel)) } },
    )
}

@Composable
private fun InstallConnectorDialog(e: McpCatalogEntry, onDismiss: () -> Unit, onInstall: (Map<String, String>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    val ready = e.env.filter { it.required }.all { !values[it.name].isNullOrBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connectorstab_install_title, e.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (e.description.isNotBlank()) Text(e.description, style = CType.footnote)
                (e.url ?: e.command?.let { (listOf(it) + e.args).joinToString(" ") })?.let {
                    Text(stringResource(R.string.connectorstab_runs), style = CType.footnote.semibold())
                    Text(it, style = CType.footnote.copy(fontFamily = CType.mono))
                    Text(stringResource(R.string.connectorstab_review_command), style = CType.caption)
                }
                if (e.env.isNotEmpty()) {
                    Text(stringResource(R.string.connectorstab_keys), style = CType.footnote.semibold())
                    e.env.forEach { f -> AfField(values[f.name].orEmpty(), { values[f.name] = it }, if (f.required) f.prompt else stringResource(R.string.connectorstab_optional, f.prompt), f.name, visual = PasswordVisualTransformation()) }
                    Text(stringResource(R.string.connectorstab_keys_saved), style = CType.caption)
                }
            }
        },
        confirmButton = { TextButton({ onInstall(values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }) }, enabled = ready) { Text(stringResource(R.string.capabilities_install)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.capabilities_cancel)) } },
    )
}
