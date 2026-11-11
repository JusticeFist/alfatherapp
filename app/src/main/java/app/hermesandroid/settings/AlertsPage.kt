package app.hermesandroid.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.alerts.LEAD_MIN
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.shell.defaultProfileTitle
import app.hermesandroid.ui.icons.AF

// Settings > Alerts: the proactive notices the phone works out by itself (alerts/Alerts.kt).

@Composable
fun AlertsPage(graph: AppGraph) {
    val s = graph.alerts
    val p = s.prefs
    val name = defaultProfileTitle
    var calendar by remember { mutableStateOf(s.canReadCalendar()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        calendar = ok
        if (ok) s.check("permission")
    }
    Card(stringResource(R.string.alertspage_notices), AF.NotificationsActive) {
        Help(stringResource(R.string.alertspage_help, name))
        SwitchRow(stringResource(R.string.alertspage_notices_from, name), null, p.enabled) { on ->
            s.choose(p.copy(enabled = on))
            if (on && !calendar) ask.launch(Manifest.permission.READ_CALENDAR)
        }
        if (p.enabled && !calendar) SettingsButton(stringResource(R.string.alertspage_allow_calendar)) { ask.launch(Manifest.permission.READ_CALENDAR) }
    }
    if (!p.enabled) return
    Card(stringResource(R.string.alertspage_what), AF.NotificationsActive) {
        SwitchRow(stringResource(R.string.alertspage_leave), stringResource(R.string.alertspage_leave_help, LEAD_MIN), p.leave) { s.choose(p.copy(leave = it)) }
        SwitchRow(stringResource(R.string.alertspage_home), stringResource(R.string.alertspage_home_help), p.home) { s.choose(p.copy(home = it)) }
        SwitchRow(stringResource(R.string.alertspage_battery), stringResource(R.string.alertspage_battery_help), p.battery) { s.choose(p.copy(battery = it)) }
        SwitchRow(stringResource(R.string.alertspage_conflicts), stringResource(R.string.alertspage_conflicts_help), p.conflicts) { s.choose(p.copy(conflicts = it)) }
    }
}
