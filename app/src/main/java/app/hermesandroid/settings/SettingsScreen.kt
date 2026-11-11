package app.hermesandroid.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.annotation.StringRes
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.BuildConfig
import app.hermesandroid.R
import app.hermesandroid.ui.components.SettingsGroup
import app.hermesandroid.ui.components.SettingsRow
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.shell.PageBar
import app.hermesandroid.ui.shell.PredictiveBackLayer
import app.hermesandroid.ui.theme.Backdrop
import app.hermesandroid.ui.theme.Motion

// Settings home and its pushed pages. Voice, notifications and
// phone data arrive with their phases.

private enum class SettingsSheet { Gateway, Dashboards, Profiles }

enum class SettingsPageId(@StringRes val title: Int) {
    Workspace(R.string.settingsscreen_page_workspace), Model(R.string.settingsscreen_page_model), Chat(R.string.settingsscreen_page_chat),
    Memory(R.string.settingsscreen_page_memory), Advanced(R.string.settingsscreen_page_advanced), Capabilities(R.string.settingsscreen_page_capabilities),
    HostPlugin(R.string.settingsscreen_page_host_plugin), Files(R.string.settingsscreen_page_files), Notifications(R.string.settingsscreen_page_notifications),
    Voice(R.string.settingsscreen_page_voice), Phone(R.string.settingsscreen_page_phone), Location(R.string.settingsscreen_page_location),
    Health(R.string.settingsscreen_page_health), Alerts(R.string.settingsscreen_page_alerts),
}

@Composable
fun SettingsScreen(graph: AppGraph, bottomInset: Dp, onBack: () -> Unit) {
    var page by remember { mutableStateOf<SettingsPageId?>(null) }
    // Pushed like the app's pages: the home stays underneath (its scroll too), the page slides over it and follows the
    // predictive back gesture. The last page stays on screen while it slides away.
    var shown by remember { mutableStateOf(page) }
    if (page != null) shown = page
    Box(Modifier.fillMaxSize()) {
        SettingsHome(graph, bottomInset, onBack) { page = it }
        AnimatedVisibility(page != null, enter = slideInHorizontally(Motion.page()) { it }, exit = slideOutHorizontally(Motion.page()) { it }) {
            PredictiveBackLayer(page != null, onBack = { page = null }) {
                Backdrop(Modifier.fillMaxSize())
                shown?.let { p -> SettingsSubPage(graph, p, bottomInset) { page = null } }
            }
        }
    }
}

@Composable
private fun SettingsSubPage(graph: AppGraph, p: SettingsPageId, bottomInset: Dp, onBack: () -> Unit) {
    SettingsPage(stringResource(p.title), onBack = onBack, bottomInset) {
        when (p) {
            SettingsPageId.Workspace -> {
                app.hermesandroid.settings.Help(stringResource(R.string.settingsscreen_workspace_help))
                ConfigFields(graph, WORKSPACE_SECTIONS)
            }
            SettingsPageId.Model -> ModelPage(graph)
            SettingsPageId.Chat -> ChatSettingsPage(graph)
            SettingsPageId.Memory -> MemoryPage(graph)
            SettingsPageId.Advanced -> {
                app.hermesandroid.settings.Help(stringResource(R.string.settingsscreen_advanced_help))
                ConfigFields(graph, ADVANCED_SECTIONS)
            }
            SettingsPageId.Capabilities -> CapabilitiesPage(graph)
            SettingsPageId.HostPlugin -> HostPluginPage(graph)
            SettingsPageId.Files -> FilesPage(graph)
            SettingsPageId.Notifications -> NotificationsPage(graph)
            SettingsPageId.Voice -> VoicePage(graph)
            SettingsPageId.Phone -> PhonePage(graph)
            SettingsPageId.Location -> LocationPage(graph)
            SettingsPageId.Health -> HealthPage(graph)
            SettingsPageId.Alerts -> AlertsPage(graph)
        }
    }
}

@Composable
private fun SettingsHome(graph: AppGraph, bottomInset: Dp, onBack: () -> Unit, open: (SettingsPageId) -> Unit) {
    val c = C.colors
    val connected by graph.client.connected.collectAsStateWithLifecycle()
    val host = graph.store.baseUrl.substringAfter("://")
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    var biometric by remember { mutableStateOf(graph.store.biometricLock) }
    Column(Modifier.fillMaxSize()) {
        PageBar(onBack)
        app.hermesandroid.ui.components.ScreenHeader(stringResource(R.string.settingsscreen_eyebrow), stringResource(R.string.settingsscreen_title))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val plugin = graph.hostPlugin
            if (plugin.needsAttention) Card(stringResource(if (plugin.state == HostPlugin.State.Missing) R.string.settingsscreen_page_host_plugin else R.string.settingsscreen_plugin_update_title), AF.Extension) {
                Help(stringResource(when {
                    plugin.notServed -> R.string.settingsscreen_plugin_not_served
                    plugin.state == HostPlugin.State.Missing -> R.string.settingsscreen_plugin_missing
                    plugin.restartNeeded && !plugin.updateAvailable -> R.string.settingsscreen_plugin_restart
                    else -> R.string.settingsscreen_plugin_update
                }))
                app.hermesandroid.ui.components.SettingsButton(stringResource(R.string.settingsscreen_plugin_open), AF.Extension) { open(SettingsPageId.HostPlugin) }
            }
            SettingsGroup(profileTitle(graph.profile), AF.AccountCircle) {
                SettingsRow(
                    AF.Sensors, stringResource(R.string.settingsscreen_connection),
                    detail = stringResource(if (connected) R.string.settingsscreen_connected else R.string.settingsscreen_reconnecting, host),
                    dot = if (connected) c.success else c.orange, first = true,
                ) { sheet = SettingsSheet.Gateway }
                SettingsRow(AF.VerifiedUser, stringResource(R.string.settingsscreen_approvals), detail = stringResource(R.string.settingsscreen_approvals_detail)) { open(SettingsPageId.Workspace) }
            }
            SettingsGroup(stringResource(R.string.settingsscreen_page_voice), AF.GraphicEq) {
                SettingsRow(AF.Mic, stringResource(R.string.settingsscreen_page_voice), detail = stringResource(R.string.settingsscreen_voice_detail), first = true) { open(SettingsPageId.Voice) }
            }
            SettingsGroup(stringResource(R.string.settingsscreen_knows_you, profileTitle("default")), AF.PhoneAndroid) {
                SettingsRow(AF.PhoneAndroid, stringResource(R.string.settingsscreen_page_phone), detail = graph.phone.summary(), first = true) { open(SettingsPageId.Phone) }
                SettingsRow(AF.LocationOn, stringResource(R.string.settingsscreen_page_location), detail = graph.location.summary()) { open(SettingsPageId.Location) }
                SettingsRow(AF.FavoriteBorder, stringResource(R.string.settingsscreen_page_health), detail = graph.health.summary()) { open(SettingsPageId.Health) }
                SettingsRow(AF.NotificationsActive, stringResource(R.string.settingsscreen_page_alerts), detail = graph.alerts.summary()) { open(SettingsPageId.Alerts) }
            }
            SettingsGroup("Hermes", AF.Settings) {
                SettingsRow(AF.Memory, stringResource(R.string.settingsscreen_page_model), detail = stringResource(R.string.settingsscreen_model_detail), first = true) { open(SettingsPageId.Model) }
                SettingsRow(AF.Chat, stringResource(R.string.settingsscreen_page_chat), detail = stringResource(R.string.settingsscreen_chat_detail)) { open(SettingsPageId.Chat) }
                SettingsRow(AF.Psychology, stringResource(R.string.settingsscreen_page_memory), detail = stringResource(R.string.settingsscreen_memory_detail)) { open(SettingsPageId.Memory) }
                SettingsRow(AF.Build, stringResource(R.string.settingsscreen_page_advanced), detail = stringResource(R.string.settingsscreen_advanced_detail)) { open(SettingsPageId.Advanced) }
                SettingsRow(AF.Extension, stringResource(R.string.settingsscreen_page_capabilities), detail = stringResource(R.string.settingsscreen_capabilities_detail)) { open(SettingsPageId.Capabilities) }
                SettingsRow(AF.FolderOpen, stringResource(R.string.settingsscreen_page_files), detail = stringResource(R.string.settingsscreen_files_detail)) { open(SettingsPageId.Files) }
            }
            SettingsGroup("App", AF.Tune) {
                SettingsRow(AF.Person, stringResource(R.string.settingsscreen_profiles), detail = stringResource(R.string.settingsscreen_profiles_detail, graph.profiles.size), first = true) { sheet = SettingsSheet.Profiles }
                SettingsRow(
                    AF.Notifications, stringResource(R.string.settingsscreen_page_notifications),
                    detail = if (graph.push.registered) stringResource(R.string.settingsscreen_notifications_on, graph.push.relayUrl.substringAfter("://")) else stringResource(R.string.settingsscreen_notifications_off),
                ) { open(SettingsPageId.Notifications) }
                app.hermesandroid.ui.components.Divider(inset = 56.dp)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(AF.Fingerprint, null, tint = c.accent, modifier = Modifier.size(20.dp))
                    Box(Modifier.weight(1f).padding(start = 20.dp)) {
                        SwitchRow(stringResource(R.string.settingsscreen_biometric), stringResource(R.string.settingsscreen_biometric_help), biometric) { biometric = it; graph.setBiometric(it) }
                    }
                }
            }
            SettingsGroup(stringResource(R.string.settingsscreen_server), AF.Dns) {
                SettingsRow(AF.Dns, stringResource(R.string.settingsscreen_dashboards), detail = stringResource(R.string.settingsscreen_dashboards_detail, graph.dashboards.size), first = true) { sheet = SettingsSheet.Dashboards }
                SettingsRow(AF.Extension, stringResource(R.string.settingsscreen_page_host_plugin), detail = graph.hostPlugin.summary()) { open(SettingsPageId.HostPlugin) }
                SettingsRow(AF.Logout, stringResource(R.string.settingsscreen_sign_out), detail = host, onClick = graph::signOut)
            }
            SettingsGroup(stringResource(R.string.settingsscreen_about), AF.Info) {
                SettingsRow(AF.Info, stringResource(R.string.settingsscreen_app_name), detail = stringResource(R.string.settingsscreen_version, BuildConfig.VERSION_NAME), first = true, chevron = false)
            }
            Text(
                stringResource(R.string.settingsscreen_footer),
                style = CType.footnote, color = c.secondary, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
    when (sheet) {
        SettingsSheet.Gateway -> GatewaySheet(graph) { sheet = null }
        SettingsSheet.Dashboards -> DashboardsSheet(graph) { sheet = null }
        SettingsSheet.Profiles -> ProfilesSheet(graph) { sheet = null }
        null -> {}
    }
}
