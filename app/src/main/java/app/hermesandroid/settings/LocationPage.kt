package app.hermesandroid.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.location.AREA
import app.hermesandroid.location.LocationPrefs
import app.hermesandroid.location.LocationSync
import app.hermesandroid.location.PLACES
import app.hermesandroid.location.PRECISE
import app.hermesandroid.net.str
import app.hermesandroid.tr
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.shell.defaultProfileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import app.hermesandroid.ui.icons.AF

// Settings > Location (LocationSettingsView): the two layers and their precision, saved places (whose coordinates
// stay here), how long the host keeps it, a pause, and what Allfather did with it (place rules, recent reads).

@Composable
fun LocationPage(graph: AppGraph) {
    val s = graph.location
    val p = s.prefs
    val name = defaultProfileTitle
    val scope = rememberCoroutineScope()
    val precisions = listOf(PLACES, AREA, PRECISE).map { it to stringResource(LocationSync.precisionLabel(it)) }
    val kinds = KINDS.map { (k, label) -> k to stringResource(label) }
    fun set(next: LocationPrefs) { s.choose(next) }
    var fine by remember { mutableStateOf(s.hasCoarse()) }
    var background by remember { mutableStateOf(s.hasBackground()) }
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        fine = r.values.any { it }
        if (fine) set(s.prefs) else s.error = tr(R.string.locationpage_no_permission, name)
    }
    // Android 11+ sends the user to the app's settings for "Allow all the time".
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        background = ok
        if (ok) set(s.prefs.copy(background = true)) else s.error = tr(R.string.locationpage_need_always)
    }
    // A permission granted in Android's own Settings comes back without a callback: re-read it on return.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        fine = s.hasCoarse(); background = s.hasBackground()
        onPauseOrDispose { }
    }
    fun askForeground() = foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

    Card(stringResource(R.string.locationpage_location), AF.LocationOn) {
        Help(stringResource(R.string.locationpage_help, name))
        SwitchRow(stringResource(R.string.locationpage_share_with, name), null, p.enabled) { on ->
            set(p.copy(enabled = on))
            if (on && !s.hasCoarse()) askForeground()
        }
        if (p.enabled) {
            val paused = p.pausedUntil?.let { it > System.currentTimeMillis() } == true
            Text(when {
                s.error != null -> stringResource(R.string.locationpage_error, s.error.orEmpty())
                paused -> stringResource(R.string.locationpage_paused_until, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.pausedUntil!!)))
                else -> s.lastSent?.let { stringResource(R.string.locationpage_last_sent, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) } ?: stringResource(R.string.locationpage_never_sent)
            }, style = CType.footnote, color = if (s.error != null) C.colors.red else C.colors.secondary)
            if (!fine) SettingsButton(stringResource(R.string.locationpage_allow)) { askForeground() }
            if (paused) SettingsButton(stringResource(R.string.locationpage_unpause)) { set(p.copy(pausedUntil = null)) }
            else SettingsButton(stringResource(R.string.locationpage_pause_hour)) { set(p.copy(pausedUntil = System.currentTimeMillis() + 3_600_000L)) }
        }
    }
    if (!p.enabled) return
    Card(stringResource(R.string.locationpage_talking), AF.RecordVoiceOver) {
        SwitchRow(stringResource(R.string.locationpage_talking_switch), stringResource(R.string.locationpage_talking_help), p.talking) { set(p.copy(talking = it)) }
        if (p.talking) Picker(stringResource(R.string.locationpage_precision), p.talkingPrecision, precisions) { set(p.copy(talkingPrecision = it)) }
    }
    Card(stringResource(R.string.locationpage_background), AF.Navigation) {
        SwitchRow(stringResource(R.string.locationpage_always), stringResource(R.string.locationpage_always_help), p.background && background) { on ->
            if (!on) set(p.copy(background = false))
            else if (!s.hasCoarse()) askForeground()
            else if (!background) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else set(p.copy(background = true))
        }
        if (p.background && background) Picker(stringResource(R.string.locationpage_precision), p.backgroundPrecision, precisions) { set(p.copy(backgroundPrecision = it)) }
    }
    Card(stringResource(R.string.locationpage_places), AF.Home) {
        Help(stringResource(R.string.locationpage_places_help, name))
        val privateTag = stringResource(R.string.locationpage_private_tag)
        p.places.forEach { place ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${place.name} · ${kinds.toMap()[place.kind] ?: place.kind}" + (if (place.private) " · $privateTag" else ""), style = CType.subheadline, color = C.colors.text, modifier = Modifier.weight(1f))
                TextButton({ s.removePlace(place.id) }) { Text(stringResource(R.string.locationpage_remove), color = C.colors.red) }
            }
        }
        var placeName by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("home") }
        var private by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf<String?>(null) }
        if (p.places.size < 20) {
            SmallField(placeName, { placeName = it.take(40) }, stringResource(R.string.locationpage_name_hint))
            Picker(stringResource(R.string.locationpage_kind), kind, kinds) { kind = it }
            SwitchRow(stringResource(R.string.locationpage_private), stringResource(R.string.locationpage_private_help), private) { private = it }
            SettingsButton(stringResource(if (saving) R.string.locationpage_locating else R.string.locationpage_save_here)) {
                if (!saving) scope.launch {
                    saving = true
                    // No name typed: the type names it ("Home").
                    note = s.addPlaceHere(placeName.ifBlank { KINDS.toMap()[kind]?.let { tr(it) } ?: tr(R.string.locationpage_place) }, kind, 150, private)
                    if (note == null) { placeName = ""; private = false }
                    saving = false
                }
            }
            note?.let { Text(it, style = CType.footnote, color = C.colors.red) }
        }
    }
    Card(stringResource(R.string.locationpage_retention), AF.History) {
        Picker(stringResource(R.string.locationpage_history), p.retentionDays.toString(), listOf(
            "0" to stringResource(R.string.locationpage_latest_only),
            "1" to pluralStringResource(R.plurals.locationpage_days, 1, 1),
            "7" to pluralStringResource(R.plurals.locationpage_days, 7, 7),
            "30" to pluralStringResource(R.plurals.locationpage_days, 30, 30),
            "365" to stringResource(R.string.locationpage_one_year),
        )) { set(p.copy(retentionDays = it.toInt())) }
    }
    ActivityCard(graph)
}

private val KINDS = listOf(
    "home" to R.string.locationpage_kind_home, "work" to R.string.locationpage_kind_work, "gym" to R.string.locationpage_kind_gym,
    "school" to R.string.locationpage_kind_school, "other" to R.string.locationpage_kind_other,
)

/** Place rules Allfather set ("when I get home…") and its recent reads of the location. */
@Composable
private fun ActivityCard(graph: AppGraph) {
    val s = graph.location
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<JsonObject>?>(null) }
    var reads by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(Unit) { rules = s.rules(); reads = s.reads() }
    Card(stringResource(R.string.locationpage_activity, defaultProfileTitle), AF.History) {
        val r = rules ?: return@Card Help(stringResource(R.string.locationpage_loading))
        if (r.isEmpty()) Help(stringResource(R.string.locationpage_no_rules))
        r.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(rule.str("what") ?: rule.str("task"), rule.str("place_name"), rule.str("event")).joinToString(" · "),
                    style = CType.subheadline, color = C.colors.text, modifier = Modifier.weight(1f))
                TextButton({ scope.launch { runCatching { s.deleteRule(rule.str("id").orEmpty()) }; rules = s.rules() } }) { Text(stringResource(R.string.locationpage_remove), color = C.colors.red) }
            }
        }
        if (reads.isNotEmpty()) {
            Help(stringResource(R.string.locationpage_recent_reads))
            reads.take(8).forEach { Text("${it.str("at")?.take(16)?.replace('T', ' ') ?: ""} · ${it.str("session_title") ?: it.str("action") ?: ""}", style = CType.footnote, color = C.colors.secondary) }
        }
    }
}
