package app.hermesandroid.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hermesandroid.R
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.tr
import app.hermesandroid.ui.components.AfField
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.AfColors
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import kotlinx.coroutines.launch
import app.hermesandroid.ui.icons.AF

// Capabilities > Tools (CapabilityToolsTab, CapabilityToolsetDetailPage).

private fun Toolset.matches(q: String) = q.isEmpty() || name.contains(q, true) || label.contains(q, true) || description.contains(q, true) || tools.any { it.contains(q, true) }

@Composable
fun ToolsTab(s: CapabilitiesState, q: String) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var mostUsedFirst by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.profile) {
        if (!s.toolsLoaded) s.loadToolsets()
        if (s.toolCalls == null) s.loadToolCalls()
    }
    if (!s.toolsLoaded) { Loading(); return }
    s.toolsError?.let { Text(it, style = CType.footnote, color = c.red); SettingsButton(stringResource(R.string.capabilities_retry)) { scope.launch { s.loadToolsets() } }; return }
    error?.let { Text(it, style = CType.footnote, color = c.red) }
    val list = s.toolsets.filter { it.matches(q) }.sortedWith(compareBy<Toolset> { if (mostUsedFirst) -s.calls(it) else s.calls(it) }.thenBy { it.label.lowercase() })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.toolstab_active, s.toolsets.count { it.enabled }, s.toolsets.size), style = CType.footnote, color = c.secondary, modifier = Modifier.weight(1f).padding(start = 16.dp))
        Text(stringResource(if (mostUsedFirst) R.string.toolstab_most_used_first else R.string.toolstab_least_used_first), style = CType.footnote.semibold(), color = c.accent, modifier = Modifier.pressable { mostUsedFirst = !mostUsedFirst }.padding(8.dp))
    }
    CapSection(stringResource(R.string.toolstab_toolsets), stringResource(R.string.capabilities_new_sessions)) {
        if (list.isEmpty()) Empty(if (q.isEmpty()) stringResource(R.string.toolstab_no_tools) else stringResource(R.string.capabilities_no_match, q))
        list.forEachIndexed { i, t ->
            val pills = buildList {
                if (t.tools.isNotEmpty()) add(pluralStringResource(R.plurals.capabilities_tools, t.tools.size, t.tools.size) to null)
                s.calls(t).takeIf { it > 0 }?.let { add(pluralStringResource(R.plurals.toolstab_calls, it, it) to null) }
                if (t.configured == false) add(stringResource(R.string.toolstab_needs_keys) to c.orange)
            }
            CapRow(t.label, t.description, i == 0, pills, onClick = { open = t.name }) {
                CapSwitch(t.enabled) { on -> scope.launch { error = s.toggleToolset(t.name, on) } }
            }
        }
    }
    open?.let { name -> ToolsetSheet(s, name) { open = null } }
}

@Composable
private fun statusPill(status: String, c: AfColors): Pair<String, Color>? = when (status) {
    "ready" -> stringResource(R.string.toolstab_status_ready) to c.success
    "needs_setup" -> stringResource(R.string.toolstab_status_needs_setup) to c.orange
    "needs_auth" -> stringResource(R.string.capabilities_needs_sign_in) to c.orange
    "unavailable" -> stringResource(R.string.toolstab_status_unavailable) to c.secondary
    else -> null
}

@Composable
private fun ToolsetSheet(s: CapabilitiesState, name: String, onDismiss: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<ToolsetConfig?>(null) }
    var backends by remember { mutableStateOf<List<TerminalBackend>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var keyFor by remember { mutableStateOf<EnvVar?>(null) }
    var confirmBackend by remember { mutableStateOf<TerminalBackend?>(null) }
    LaunchedEffect(name) {
        runCatching { s.toolsetConfig(name) }.onSuccess { config = it }.onFailure { notice = tr(R.string.toolstab_backends_read_failed) }
        if (name == "terminal") runCatching { s.terminalBackends() }.onSuccess { backends = it }.onFailure { notice = tr(R.string.toolstab_terminal_read_failed) }
    }
    val t = s.toolsets.firstOrNull { it.name == name } ?: return
    fun pick(p: ToolProvider, cap: String?) = scope.launch {
        notice = runCatching { if (s.selectProvider(name, p.name, cap)) tr(R.string.toolstab_nous_sign_in) else null }.getOrElse { tr(R.string.capabilities_change_failed, it.message.orEmpty()) }
        runCatching { s.toolsetConfig(name) }.onSuccess { config = it }
    }
    fun useBackend(b: TerminalBackend) = scope.launch {
        runCatching { s.selectTerminalBackend(b.name); backends = s.terminalBackends() }.onFailure { notice = tr(R.string.capabilities_change_failed, it.message.orEmpty()) }
    }
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(t.label, style = CType.title3.semibold(), color = c.text)
            if (t.description.isNotBlank()) Text(t.description, style = CType.subheadline, color = c.secondary)
            Card(null, AF.Build) {
                SwitchRow(stringResource(R.string.capabilities_enabled), stringResource(R.string.capabilities_new_sessions), t.enabled) { on -> scope.launch { notice = s.toggleToolset(name, on) } }
            }
            notice?.let { Text(it, style = CType.footnote, color = c.orange) }
            if (t.tools.isNotEmpty()) Card(stringResource(R.string.capabilities_tools_title), AF.Build) {
                t.tools.forEach { tool ->
                    Row {
                        Text(tool, style = CType.footnote.copy(fontFamily = CType.mono), color = c.text, modifier = Modifier.weight(1f))
                        (s.toolCalls?.get(tool) ?: 0).takeIf { it > 0 }?.let { Text("$it", style = CType.footnote, color = c.secondary) }
                    }
                }
            }
            backends?.let { list ->
                Card(stringResource(R.string.toolstab_runs_commands_on), AF.Terminal) {
                    list.forEachIndexed { i, b ->
                        if (i > 0) Divider(inset = 0.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(b.label, style = CType.callout.semibold(), color = c.text)
                                    statusPill(b.status, c)?.let { (txt, tint) -> Pill(txt, tint) }
                                }
                                if (b.description.isNotBlank()) Help(b.description)
                            }
                            if (b.active) Text(stringResource(R.string.toolstab_in_use), style = CType.footnote.semibold(), color = c.success)
                            else SettingsButton(stringResource(R.string.toolstab_use)) { if (b.status == "ready") useBackend(b) else confirmBackend = b }
                        }
                    }
                }
            }
            val cfg = config
            if (cfg != null && cfg.providers.isNotEmpty()) Card("Backends", AF.Dns) {
                cfg.providers.forEachIndexed { i, p ->
                    if (i > 0) Divider(inset = 0.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = CType.callout.semibold(), color = c.text)
                        if (p.badge.isNotBlank()) Pill(p.badge)
                        statusPill(p.status, c)?.let { (txt, tint) -> Pill(txt, tint) }
                    }
                    if (p.tag.isNotBlank()) Help(p.tag)
                    p.env.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.key, style = CType.footnote.copy(fontFamily = CType.mono), color = c.text, modifier = Modifier.weight(1f))
                            Text(stringResource(if (e.isSet) R.string.toolstab_key_set else R.string.toolstab_key_missing) + "  ", style = CType.footnote, color = if (e.isSet) c.success else c.orange)
                            SettingsButton(stringResource(if (e.isSet) R.string.toolstab_change else R.string.toolstab_add)) { keyFor = e }
                        }
                    }
                    if (p.webBackend != null && p.capabilities.isNotEmpty()) {
                        p.capabilities.forEach { cap ->
                            val extract = cap == "extract"
                            if (cfg.serves(cap, p)) Text(stringResource(if (extract) R.string.toolstab_used_for_extract else R.string.toolstab_used_for_search), style = CType.footnote.semibold(), color = c.success)
                            else SettingsButton(stringResource(if (extract) R.string.toolstab_use_for_extract else R.string.toolstab_use_for_search)) { pick(p, cap) }
                        }
                    } else if (p.active) Text(stringResource(R.string.toolstab_in_use), style = CType.footnote.semibold(), color = c.success)
                    else SettingsButton(stringResource(R.string.toolstab_use_backend)) { pick(p, null) }
                }
            }
        }
    }
    keyFor?.let { e ->
        var value by remember(e.key) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyFor = null },
            title = { Text(e.key) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (e.prompt == e.key) stringResource(R.string.toolstab_key_saved_note) else e.prompt, style = CType.footnote)
                    AfField(value, { value = it }, null, stringResource(R.string.toolstab_api_key), visual = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton({
                    val v = value.trim()
                    keyFor = null
                    if (v.isNotEmpty()) scope.launch { runCatching { s.saveKey(e.key, v); config = s.toolsetConfig(name) }.onFailure { notice = tr(R.string.toolstab_key_save_failed, it.message.orEmpty()) } }
                }) { Text(stringResource(R.string.toolstab_save)) }
            },
            dismissButton = { TextButton({ keyFor = null }) { Text(stringResource(R.string.capabilities_cancel)) } },
        )
    }
    confirmBackend?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmBackend = null },
            title = { Text(stringResource(R.string.toolstab_use_anyway_title, b.label)) },
            text = { Text(b.detail ?: stringResource(R.string.toolstab_needs_host_setup)) },
            confirmButton = { TextButton({ confirmBackend = null; useBackend(b) }) { Text(stringResource(R.string.toolstab_use)) } },
            dismissButton = { TextButton({ confirmBackend = null }) { Text(stringResource(R.string.capabilities_cancel)) } },
        )
    }
}
