package app.hermesandroid.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.phone.PhonePrefs
import app.hermesandroid.tr
import app.hermesandroid.trPlural
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.shell.defaultProfileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import app.hermesandroid.ui.icons.AF

// Settings > Phone (PhoneSettingsView): what of this phone Allfather may see. Sealed to the default profile's
// host with a key only this phone and the host have; turning it off deletes the host's copy.

@Composable
fun PhonePage(graph: AppGraph) {
    val s = graph.phone
    val p = s.prefs
    val name = defaultProfileTitle
    fun set(next: PhonePrefs) = s.choose(next)
    var granted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(s.canReadCalendar()) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) { s.watchCalendar(); s.sync("permission", force = true) } else s.error = tr(R.string.phonepage_no_calendar_permission, name)
    }
    LaunchedEffect(Unit) { s.sync("opened") }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let(s::picked)?.let { (key, label) -> set(s.prefs.copy(contacts = s.prefs.contacts + (key to label))) }
    }
    var canWrite by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(s.canWriteCalendar()) }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        canWrite = ok
        if (ok) s.sync("permission", force = true) else s.error = tr(R.string.phonepage_no_write_permission, name)
    }
    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickContact.launch(null) else s.error = tr(R.string.phonepage_no_contacts_permission, name)
    }

    Card(stringResource(R.string.phonepage_this_phone), AF.PhoneAndroid) {
        Help(stringResource(R.string.phonepage_help, name))
        SwitchRow(stringResource(R.string.phonepage_share_with, name), null, p.enabled) { on ->
            set(p.copy(enabled = on))
            if (on && p.calendar != "off" && !s.canReadCalendar()) calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }
        if (p.enabled) {
            Text(statusLine(graph), style = CType.footnote, color = if (s.error != null) C.colors.red else C.colors.secondary)
            SettingsButton(stringResource(if (s.busy) R.string.phonepage_syncing else R.string.phonepage_sync_now)) { s.sync("manual", force = true) }
        }
    }
    if (!p.enabled) return
    Card(stringResource(R.string.phonepage_calendar), AF.CalendarMonth) {
        Help(stringResource(R.string.phonepage_calendar_help, p.futureDays))
        Picker(stringResource(R.string.phonepage_calendar), p.calendar, listOf(
            "off" to stringResource(R.string.phonepage_calendar_off), "read" to stringResource(R.string.phonepage_calendar_read),
            "full" to stringResource(R.string.phonepage_calendar_full),
        )) { mode ->
            set(p.copy(calendar = mode))
            when {
                mode == "off" -> {}
                !s.canReadCalendar() -> calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                mode == "full" && !s.canWriteCalendar() -> writePermission.launch(Manifest.permission.WRITE_CALENDAR)
            }
        }
        if (p.calendar == "full") {
            if (granted && !canWrite) SettingsButton(stringResource(R.string.phonepage_allow_adding)) { writePermission.launch(Manifest.permission.WRITE_CALENDAR) }
            Help(stringResource(R.string.phonepage_cards_help, name))
        }
        if (p.calendar != "off") {
            if (!granted) SettingsButton(stringResource(R.string.phonepage_allow_calendar)) { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }
            Picker(stringResource(R.string.phonepage_ahead), p.futureDays.toString(), listOf(30, 60, 180).map { it.toString() to pluralStringResource(R.plurals.phonepage_days, it, it) }) { set(p.copy(futureDays = it.toInt())) }
            SwitchRow(stringResource(R.string.phonepage_details), stringResource(R.string.phonepage_details_help), p.details) { set(p.copy(details = it)) }
        }
    }
    Card(stringResource(R.string.phonepage_contacts), AF.Contacts) {
        Help(stringResource(R.string.phonepage_contacts_help, name))
        p.contacts.forEach { (key, label) ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(label, style = CType.subheadline, color = C.colors.text, modifier = androidx.compose.ui.Modifier.weight(1f))
                androidx.compose.material3.TextButton({ set(p.copy(contacts = p.contacts - key)) }) { Text(stringResource(R.string.phonepage_remove), color = C.colors.red) }
            }
        }
        if (p.contacts.size < 200) SettingsButton(stringResource(R.string.phonepage_add_contact)) {
            if (s.canReadContacts()) pickContact.launch(null) else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    Card(stringResource(R.string.phonepage_alarms_card), AF.Alarm) {
        SwitchRow(stringResource(R.string.phonepage_alarms), stringResource(R.string.phonepage_alarms_help, name), p.alarms) { set(p.copy(alarms = it)) }
        SwitchRow(stringResource(R.string.phonepage_auto_add), stringResource(R.string.phonepage_auto_add_help), p.autoAdd) { set(p.copy(autoAdd = it)) }
    }
    Card(stringResource(R.string.phonepage_status), AF.BatteryChargingFull) {
        SwitchRow(stringResource(R.string.phonepage_battery), stringResource(R.string.phonepage_battery_help), p.status) { set(p.copy(status = it)) }
        SwitchRow(stringResource(R.string.phonepage_dnd), stringResource(R.string.phonepage_dnd_help), p.focus) { set(p.copy(focus = it)) }
    }
}

private fun statusLine(graph: AppGraph): String {
    val s = graph.phone
    s.error?.let { return tr(R.string.phonepage_sync_failed, it) }
    val last = s.lastSync ?: return tr(R.string.phonepage_never_synced)
    val n = s.counts?.get("events")?.let { (it as? JsonPrimitive)?.intOrNull }
    return tr(R.string.phonepage_last_sync, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last))) +
        (n?.let { " · " + trPlural(R.plurals.phonepage_events_on_host, it, it) } ?: "")
}
