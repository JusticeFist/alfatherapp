package app.hermesandroid.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.PermissionController
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.health.CATEGORIES
import app.hermesandroid.health.HealthPrefs
import app.hermesandroid.health.HealthSync
import app.hermesandroid.health.permissionsFor
import app.hermesandroid.tr
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.shell.defaultProfileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import app.hermesandroid.ui.icons.AF

// Settings > Health (HealthSettingsView): which categories of Health Connect Allfather may read, and how far back.

private val HELP = mapOf(
    "activity" to R.string.healthpage_help_activity,
    "heart" to R.string.healthpage_help_heart,
    "sleep" to R.string.healthpage_help_sleep,
    "workouts" to R.string.healthpage_help_workouts,
    "body" to R.string.healthpage_help_body,
    "vitals" to R.string.healthpage_help_vitals,
    "fitness" to R.string.healthpage_help_fitness,
)

@Composable
fun HealthPage(graph: AppGraph) {
    val s = graph.health
    val p = s.prefs
    val name = defaultProfileTitle
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { got ->
        granted = got
        if (got.isEmpty()) s.error = tr(R.string.healthpage_no_permission, name) else s.sync("permission", force = true)
    }
    LaunchedEffect(p.categories) { granted = s.granted() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) s.choose(s.prefs.copy(phoneSleep = true)) else s.error = tr(R.string.healthpage_no_activity_permission)
    }
    fun set(next: HealthPrefs) {
        s.choose(next)
        val need = permissionsFor(next.categories) + app.hermesandroid.health.BACKGROUND + (if (next.historyDays > 30) setOf("android.permission.health.READ_HEALTH_DATA_HISTORY") else emptySet())
        if (next.enabled && !granted.containsAll(need)) ask.launch(need)
    }

    Card(stringResource(R.string.healthpage_health), AF.FavoriteBorder) {
        if (!s.available) {
            Help(stringResource(R.string.healthpage_unavailable))
            return@Card
        }
        Help(stringResource(R.string.healthpage_help, name))
        SwitchRow(stringResource(R.string.healthpage_share_with, name), null, p.enabled) { set(p.copy(enabled = it)) }
        if (p.enabled) {
            val n = s.rows?.values?.sumOf { (it as? JsonPrimitive)?.intOrNull ?: 0 }
            Text(s.error?.let { stringResource(R.string.healthpage_sync_failed, it) }
                ?: s.lastSync?.let {
                    stringResource(R.string.healthpage_last_sync, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) +
                        (n?.let { c -> " · " + pluralStringResource(R.plurals.healthpage_rows_on_host, c, c) } ?: "")
                }
                ?: stringResource(R.string.healthpage_never_synced), style = CType.footnote, color = if (s.error != null) C.colors.red else C.colors.secondary)
            SettingsButton(stringResource(if (s.busy) R.string.healthpage_syncing else R.string.healthpage_sync_now)) { s.sync("manual", force = true) }
        }
    }
    if (!p.enabled || !s.available) return
    Card(stringResource(R.string.healthpage_what), AF.Category) {
        val missingNote = stringResource(R.string.healthpage_missing_permission)
        CATEGORIES.forEach { c ->
            val missing = !granted.containsAll(permissionsFor(listOf(c)))
            SwitchRow((HealthSync.CATEGORY_LABELS[c]?.let { stringResource(it) } ?: c).replaceFirstChar { it.uppercase() },
                HELP[c]?.let { stringResource(it) }.orEmpty() + if (c in p.categories && missing) " $missingNote" else "", c in p.categories) { on ->
                set(p.copy(categories = if (on) p.categories + c else p.categories - c))
            }
        }
        if ("sleep" in p.categories) {
            SwitchRow(stringResource(R.string.healthpage_phone_sleep), stringResource(R.string.healthpage_phone_sleep_help), p.phoneSleep) { on ->
                if (on && !app.hermesandroid.health.PhoneSleep.permitted(ctx)) activity.launch(android.Manifest.permission.ACTIVITY_RECOGNITION)
                else set(p.copy(phoneSleep = on))
            }
        }
        Picker(stringResource(R.string.healthpage_history), p.historyDays.toString(), listOf(
            "30" to pluralStringResource(R.plurals.healthpage_days, 30, 30),
            "90" to pluralStringResource(R.plurals.healthpage_days, 90, 90),
            "365" to stringResource(R.string.healthpage_one_year),
        )) { set(p.copy(historyDays = it.toInt())) }
    }
}
