package app.hermesandroid.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.push.PushPrefs
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import kotlinx.coroutines.launch
import app.hermesandroid.ui.icons.AF

// Settings > Notifications (NotificationsSettingsDetail): this phone's relay registration, the active profile's
// pairing and E2E key, and which pushes the relay sends.

@Composable
fun NotificationsPage(graph: AppGraph) {
    val c = C.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val push = graph.push
    var relay by remember { mutableStateOf(push.relayUrl) }
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        if (ok) scope.launch { push.enable() }
    }
    LaunchedEffect(graph.profile, push.registered) { push.sync() }

    fun enable() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else scope.launch { push.enable() }
    }

    Card(stringResource(R.string.notificationspage_this_phone), AF.Notifications) {
        if (!push.configured) { Help(stringResource(R.string.notificationspage_no_firebase)); return@Card }
        Help(stringResource(R.string.notificationspage_how))
        Text(stringResource(R.string.notificationspage_relay_address), style = CType.footnote, color = c.secondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(relay, { relay = it }, push.defaultRelay(), modifier = Modifier.weight(1f))
            SettingsButton(stringResource(R.string.notificationspage_save)) { push.setRelayUrl(relay) }
        }
        Text(
            when {
                push.registered && !allowed -> stringResource(R.string.notificationspage_blocked)
                push.registered -> stringResource(R.string.notificationspage_on)
                else -> stringResource(R.string.notificationspage_off)
            },
            style = CType.subheadline, color = if (push.registered && allowed) c.success else c.secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (push.busy) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
            else if (!push.registered || !allowed) SettingsButton(stringResource(R.string.notificationspage_enable), prominent = true) { enable() }
            else SettingsButton(stringResource(R.string.notificationspage_disable)) { scope.launch { push.disable() } }
        }
        push.message?.let { Text(it, style = CType.footnote, color = c.secondary) }
    }
    if (push.registered) Card(stringResource(R.string.notificationspage_this_profile), AF.Lock) {
        val plugin = graph.hostPlugin
        when {
            !plugin.reports("pairing") -> Help(stringResource(R.string.notificationspage_plugin_old))
            push.pairing == "this" -> Text(stringResource(if (push.e2e) R.string.notificationspage_paired_e2e else R.string.notificationspage_paired_plain), style = CType.subheadline, color = if (push.e2e) c.success else c.orange)
            push.pairing == "other" -> {
                Help(stringResource(R.string.notificationspage_paired_other))
                SettingsButton(stringResource(R.string.notificationspage_pair_here)) { scope.launch { push.pairNow() } }
            }
            else -> SettingsButton(stringResource(R.string.notificationspage_pair_profile)) { scope.launch { push.pairNow() } }
        }
        val (mine, all) = push.paired
        if (all > 1) Help(stringResource(R.string.notificationspage_paired_count, mine, all))
    }
    if (push.registered) Card(stringResource(R.string.notificationspage_notices), AF.Tune) {
        val p = push.data.prefs
        fun set(next: PushPrefs) { scope.launch { push.setPrefs(next) } }
        val agent = app.hermesandroid.ui.shell.defaultProfileTitle
        SwitchRow(stringResource(R.string.notificationspage_approvals), stringResource(R.string.notificationspage_approvals_help, agent), p.approvalNeeded, enabled = !push.busy) { set(p.copy(approvalNeeded = it)) }
        SwitchRow(stringResource(R.string.notificationspage_questions), stringResource(R.string.notificationspage_questions_help, agent), p.inputNeeded, enabled = !push.busy) { set(p.copy(inputNeeded = it)) }
        SwitchRow(stringResource(R.string.notificationspage_replies), stringResource(R.string.notificationspage_replies_help), p.responseReady, enabled = !push.busy) { set(p.copy(responseReady = it)) }
        SwitchRow(stringResource(R.string.notificationspage_failed_turns), null, p.turnFailed, enabled = !push.busy) { set(p.copy(turnFailed = it)) }
        SwitchRow(stringResource(R.string.notificationspage_background_tasks), null, p.backgroundTaskFinished, enabled = !push.busy) { set(p.copy(backgroundTaskFinished = it)) }
        SwitchRow(stringResource(R.string.notificationspage_answer_from_notice), stringResource(R.string.notificationspage_answer_from_notice_help), p.decisionCards, enabled = !push.busy) { set(p.copy(decisionCards = it)) }
        SwitchRow(stringResource(R.string.notificationspage_previews), stringResource(R.string.notificationspage_previews_help), p.showPreviews, enabled = !push.busy) { set(p.copy(showPreviews = it)) }
    }
}
