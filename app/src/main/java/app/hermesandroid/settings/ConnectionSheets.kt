package app.hermesandroid.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.tr
import app.hermesandroid.ui.components.Divider
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.components.SettingsGroup
import app.hermesandroid.ui.components.SettingsRow
import app.hermesandroid.ui.shell.Avatar
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.hermesandroid.ui.icons.AF

/** Saved Dashboards (SavedDashboardsView): switch, sign out, remove, add. */
@Composable
fun DashboardsSheet(graph: AppGraph, onDismiss: () -> Unit) {
    val c = C.colors
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val inUse = stringResource(R.string.connectionsheets_in_use)
    val remove = stringResource(R.string.connectionsheets_remove)
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.connectionsheets_dashboards_title), style = CType.headline, color = c.text)
            Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(20.dp))) {
                graph.dashboards.forEachIndexed { i, d ->
                    if (i > 0) Divider(inset = 56.dp)
                    val active = d.id == graph.activeDashboard
                    Row(Modifier.fillMaxWidth().pressable(enabled = !active) { onDismiss(); graph.switchDashboard(d.id) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(AF.Dns, null, tint = c.accent, modifier = Modifier.width(26.dp).size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.url.substringAfter("://"), style = CType.callout.semibold(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(d.username.ifBlank { null }, if (d.oauth) "OAuth" else null, if (active) inUse else null).joinToString(" · "), style = CType.subheadline, color = c.text.copy(alpha = 0.58f))
                        }
                        if (active) Icon(AF.CheckCircle, null, tint = c.accent)
                        else Box(Modifier.size(40.dp).pressable(label = remove) { confirmRemove = d.id }, contentAlignment = Alignment.Center) { Icon(AF.Delete, remove, tint = c.red, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(stringResource(R.string.connectionsheets_add_dashboard), AF.Add, prominent = true) { onDismiss(); graph.beginAddDashboard() }
                SettingsButton(stringResource(R.string.connectionsheets_sign_out), AF.Logout) { onDismiss(); graph.signOut() }
            }
            Text(stringResource(R.string.connectionsheets_sign_in_stays), style = CType.caption, color = c.secondary)
        }
    }
    confirmRemove?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.connectionsheets_remove_title)) },
            text = { Text(stringResource(R.string.connectionsheets_remove_text)) },
            confirmButton = { TextButton({ confirmRemove = null; graph.removeDashboard(id) }) { Text(remove, color = c.red) } },
            dismissButton = { TextButton({ confirmRemove = null }) { Text(stringResource(R.string.connectionsheets_cancel)) } },
        )
    }
}

/** Gateway diagnostics (GatewayDiagnosticsSheet) + Restart Gateway. */
@Composable
fun GatewaySheet(graph: AppGraph, onDismiss: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<JsonObject?>(null) }
    val connectors = remember { mutableStateListOf<JsonObject>() }
    val logs = remember { mutableStateListOf<String>() }
    var loading by remember { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }
    var restart by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        val client = graph.client
        status = runCatching { client.rest("GET", "/api/status").obj() }.getOrNull()
        runCatching { (client.rest("GET", "/api/messaging/platforms").obj()?.get("platforms") as? JsonArray).orEmpty().mapNotNull { it.obj() } }.getOrNull()?.let { connectors.clear(); connectors.addAll(it) }
        runCatching { (client.rest("GET", "/api/logs?lines=80&component=gateway").obj()?.get("lines") as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() } }.getOrNull()?.let { logs.clear(); logs.addAll(it) }
        loading = false
    }

    fun doRestart() = scope.launch {
        restartGateway(graph.client) { restart = it }
        load()
    }

    LaunchedEffect(Unit) { load() }
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AF.CellTower, null, tint = c.accent)
                Spacer(Modifier.width(8.dp))
                Text("Gateway", style = CType.headline, color = c.text, modifier = Modifier.weight(1f))
                if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
            }
            val st = status
            Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val running = st?.bool("gateway_running") ?: graph.client.connected.value
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (running) c.success else c.red))
                    Spacer(Modifier.width(8.dp))
                    Text(if (!running) stringResource(R.string.connectionsheets_stopped) else st?.str("gateway_state")?.let { stringResource(R.string.connectionsheets_running_state, it) } ?: stringResource(R.string.connectionsheets_running), style = CType.callout.semibold(), color = c.text)
                }
                st?.str("version")?.let { Text(stringResource(R.string.connectionsheets_version, it), style = CType.subheadline, color = c.secondary) }
                st?.num("gateway_pid")?.let { Text("PID ${it.toLong()}", style = CType.subheadline, color = c.secondary) }
                if (st == null && !loading) Text(stringResource(R.string.connectionsheets_status_error), style = CType.footnote, color = c.red)
            }
            if (connectors.isNotEmpty()) SettingsGroup(stringResource(R.string.connectionsheets_connectors), AF.CellTower) {
                connectors.forEachIndexed { i, p ->
                    val state = p.str("state") ?: p.str("status") ?: stringResource(R.string.connectionsheets_unknown)
                    val ok = state in setOf("connected", "running", "ok", "ready")
                    SettingsRow(AF.CellTower, p.str("name") ?: p.str("platform") ?: stringResource(R.string.connectionsheets_connector), detail = p.str("error") ?: state, dot = when { ok -> c.success; state == "disabled" -> c.tertiary; else -> c.orange }, first = i == 0, chevron = false)
                }
            }
            if (logs.isNotEmpty()) {
                Text(stringResource(R.string.connectionsheets_recent_logs), style = CType.footnote.semibold(), color = c.secondary)
                Text(logs.takeLast(80).joinToString("\n"), style = CType.caption2.copy(fontFamily = FontFamily.Monospace), color = c.text,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(c.text.copy(alpha = 0.06f)).verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp))
            }
            restart?.let { Text(it, style = CType.footnote.copy(fontFamily = FontFamily.Monospace), color = c.text) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(stringResource(R.string.connectionsheets_refresh)) { scope.launch { load() } }
                SettingsButton(stringResource(R.string.connectionsheets_restart_gateway), AF.RestartAlt) { confirm = true }
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.connectionsheets_restart_title)) },
        text = { Text(stringResource(R.string.connectionsheets_restart_text)) },
        confirmButton = { TextButton({ confirm = false; doRestart() }) { Text(stringResource(R.string.connectionsheets_restart)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.connectionsheets_cancel)) } },
    )
}

/** `POST /api/gateway/restart` and its action log until it ends. True when it restarted. */
suspend fun restartGateway(client: app.hermesandroid.net.HermesClient, progress: (String) -> Unit): Boolean {
    progress(tr(R.string.connectionsheets_restart_requesting))
    try {
        client.rest("POST", "/api/gateway/restart")
        // The restart child runs `hermes gateway restart`; exit 0 is success.
        repeat(60) {
            delay(2000)
            val st = runCatching { client.rest("GET", "/api/actions/gateway-restart/status?lines=20").obj() }.getOrNull()
            val lines = (st?.get("lines") as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
            progress(lines.takeLast(4).joinToString("\n").ifBlank { tr(R.string.connectionsheets_restarting) })
            if (st?.bool("running") == false) {
                val exit = st.num("exit_code")?.toInt()
                progress(if (exit == 0 || exit == null) tr(R.string.connectionsheets_restarted) else tr(R.string.connectionsheets_restart_exit, "$exit"))
                return exit == 0 || exit == null
            }
        }
        progress(tr(R.string.connectionsheets_restart_pending))
    } catch (e: Exception) {
        progress(tr(R.string.connectionsheets_restart_failed, e.message.toString()))
    }
    return false
}

/** Profiles (ProfilePickerSheet): switch the active profile. */
@Composable
fun ProfilesSheet(graph: AppGraph, onDismiss: () -> Unit) {
    val c = C.colors
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.connectionsheets_profiles), style = CType.headline, color = c.text)
            graph.profiles.forEach { name ->
                val active = name == graph.profile
                Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(20.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().pressable(enabled = !active, haptic = Haptic.Medium) { graph.switchProfile(name) }, verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, size = 40.dp, image = graph.avatars[name])
                        Spacer(Modifier.width(12.dp))
                        Text(profileTitle(name), style = CType.callout.semibold(), color = c.text, modifier = Modifier.weight(1f))
                        if (active) Icon(AF.CheckCircle, null, tint = c.accent)
                    }
                }
            }
        }
    }
}
