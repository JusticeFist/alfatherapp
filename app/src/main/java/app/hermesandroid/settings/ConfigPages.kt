package app.hermesandroid.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.net.bool
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.tasks.Segmented
import app.hermesandroid.tr
import app.hermesandroid.ui.shell.profileTitle
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.DisplayPrefs
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import app.hermesandroid.ui.icons.AF

// Profile settings pages: each field is a
// dotted config key with a control; keys the host's schema lacks are hidden when `requiresSchema`.

sealed interface Ctl {
    data class Toggle(val def: Boolean) : Ctl
    /** A string setting shown as a switch (display.tool_progress = all/off). */
    data class TextToggle(val on: String, val off: String, val def: Boolean) : Ctl
    /** Empty `options` take the schema's (or an override's). `labels` maps a value to a string resource id. */
    data class Options(val options: List<String>, val def: String, val labels: Map<String, Int> = emptyMap()) : Ctl
    data class Text(val def: String = "") : Ctl
    data class Number(val def: Double) : Ctl
    data object Lines : Ctl
    data object TimeZone : Ctl
}

data class Field(val key: String, @StringRes val label: Int, @StringRes val help: Int, val ctl: Ctl, val requiresSchema: Boolean = false)
data class Section(@StringRes val title: Int?, val icon: ImageVector, val fields: List<Field>)

/** The field as this host's schema describes it: null when it needs a key the schema lacks. */
fun Field.resolved(schema: Map<String, SchemaField>): Field? {
    if (schema.isEmpty()) return this
    val e = schema[key] ?: return if (requiresSchema) null else this
    return if (ctl is Ctl.Options && ctl.options.isEmpty() && e.options.isNotEmpty()) copy(ctl = ctl.copy(options = e.options)) else this
}

private val OPTION_LABELS = mapOf(
    "approvals.mode" to mapOf("manual" to R.string.configpages_opt_approvals_manual, "smart" to R.string.configpages_opt_approvals_smart, "off" to R.string.configpages_opt_approvals_off),
    "code_execution.mode" to mapOf("project" to R.string.configpages_opt_exec_project, "strict" to R.string.configpages_opt_exec_strict),
    "agent.image_input_mode" to mapOf("auto" to R.string.configpages_opt_image_auto, "native" to R.string.configpages_opt_image_native, "text" to R.string.configpages_opt_image_text),
    "agent.service_tier" to mapOf(
        "normal" to R.string.configpages_opt_tier_normal, "fast" to R.string.configpages_opt_tier_fast, "auto" to R.string.configpages_opt_tier_auto,
        "cold" to R.string.configpages_opt_tier_cold, "ultrafast" to R.string.configpages_opt_tier_ultrafast,
    ),
    "updates.non_interactive_local_changes" to mapOf("stash" to R.string.configpages_opt_updates_stash, "discard" to R.string.configpages_opt_updates_discard),
    "display.personality" to mapOf(
        "helpful" to R.string.configpages_opt_personality_helpful, "concise" to R.string.configpages_opt_personality_concise,
        "technical" to R.string.configpages_opt_personality_technical, "creative" to R.string.configpages_opt_personality_creative,
        "teacher" to R.string.configpages_opt_personality_teacher, "pirate" to R.string.configpages_opt_personality_pirate,
        "surfer" to R.string.configpages_opt_personality_surfer, "philosopher" to R.string.configpages_opt_personality_philosopher,
    ),
)

@Composable
fun optionLabel(key: String, v: String): String = when {
    v.isEmpty() -> stringResource(R.string.configpages_default)
    else -> OPTION_LABELS[key]?.get(v)?.let { stringResource(it) } ?: v.replaceFirstChar { it.uppercase() }
}

val PERSONALITIES = listOf("", "helpful", "concise", "technical", "creative", "teacher", "kawaii", "catgirl", "pirate", "shakespeare", "surfer", "noir", "uwu", "philosopher", "hype")

val CHAT_FIELDS = listOf(
    Field("display.personality", R.string.configpages_personality, R.string.configpages_personality_help, Ctl.Options(emptyList(), "")),
    Field("timezone", R.string.configpages_timezone, R.string.configpages_timezone_help, Ctl.TimeZone),
    Field("display.show_reasoning", R.string.configpages_show_reasoning, R.string.configpages_show_reasoning_help, Ctl.Toggle(true)),
    Field("display.tool_progress", R.string.configpages_tool_progress, R.string.configpages_tool_progress_help, Ctl.TextToggle("all", "off", true)),
    Field("display.expand_tools", R.string.configpages_expand_tools, R.string.configpages_expand_tools_help, Ctl.Toggle(false)),
    Field("display.memory_notifications", R.string.configpages_memory_notices, R.string.configpages_memory_notices_help,
        Ctl.Options(listOf("default", "on", "off"), "default", mapOf("default" to R.string.configpages_opt_notices_default, "on" to R.string.configpages_opt_notices_on, "off" to R.string.configpages_opt_notices_off))),
    Field("agent.image_input_mode", R.string.configpages_image_input, R.string.configpages_image_input_help, Ctl.Options(listOf("auto", "native", "text"), "auto")),
    Field("sessions.auto_archive", R.string.configpages_auto_archive, R.string.configpages_auto_archive_help, Ctl.Toggle(false), true),
    Field("sessions.auto_archive_days", R.string.configpages_auto_archive_days, R.string.configpages_auto_archive_days_help, Ctl.Number(3.0), true),
)

val WORKSPACE_SECTIONS = listOf(
    Section(R.string.configpages_section_workspace, AF.Folder, listOf(
        Field("terminal.cwd", R.string.configpages_cwd, R.string.configpages_cwd_help, Ctl.Text()),
        Field("code_execution.mode", R.string.configpages_code_exec, R.string.configpages_code_exec_help, Ctl.Options(listOf("project", "strict"), "project")),
        Field("terminal.persistent_shell", R.string.configpages_persistent_shell, R.string.configpages_persistent_shell_help, Ctl.Toggle(true), true),
        Field("terminal.env_passthrough", R.string.configpages_env_passthrough, R.string.configpages_env_passthrough_help, Ctl.Lines, true),
        Field("file_read_max_chars", R.string.configpages_read_limit, R.string.configpages_read_limit_help, Ctl.Number(100_000.0), true),
    )),
    Section(R.string.configpages_section_security, AF.Security, listOf(
        Field("approvals.mode", R.string.configpages_approval_mode, R.string.configpages_approval_mode_help, Ctl.Options(listOf("manual", "smart", "off"), "smart")),
        Field("approvals.timeout", R.string.configpages_approval_timeout, R.string.configpages_approval_timeout_help, Ctl.Number(300.0), true),
        Field("approvals.mcp_reload_confirm", R.string.configpages_mcp_reload, R.string.configpages_mcp_reload_help, Ctl.Toggle(true), true),
        Field("command_allowlist", R.string.configpages_allowlist, R.string.configpages_allowlist_help, Ctl.Lines, true),
        Field("security.redact_secrets", R.string.configpages_redact, R.string.configpages_redact_help, Ctl.Toggle(true)),
        Field("security.allow_private_urls", R.string.configpages_private_urls, R.string.configpages_private_urls_help, Ctl.Toggle(false)),
        Field("checkpoints.enabled", R.string.configpages_checkpoints, R.string.configpages_checkpoints_help, Ctl.Toggle(false), true),
    )),
    Section(R.string.configpages_section_browser, AF.Language, listOf(
        Field("browser.use_real_profile", R.string.configpages_real_profile, R.string.configpages_real_profile_help, Ctl.Toggle(false), true),
        Field("browser.allow_private_urls", R.string.configpages_browser_private, R.string.configpages_browser_private_help, Ctl.Toggle(false), true),
        Field("browser.auto_local_for_private_urls", R.string.configpages_browser_local, R.string.configpages_browser_local_help, Ctl.Toggle(true), true),
    )),
)

val MEMORY_SECTIONS = listOf(
    Section(R.string.configpages_section_memory, AF.Psychology, listOf(
        Field("memory.provider", R.string.configpages_memory_provider, R.string.configpages_memory_provider_help, Ctl.Options(emptyList(), "")),
        Field("memory.memory_enabled", R.string.configpages_memory_enabled, R.string.configpages_memory_enabled_help, Ctl.Toggle(true)),
        Field("memory.user_profile_enabled", R.string.configpages_user_profile, R.string.configpages_user_profile_help, Ctl.Toggle(true)),
        Field("memory.memory_char_limit", R.string.configpages_memory_budget, R.string.configpages_memory_budget_help, Ctl.Number(2200.0), true),
        Field("memory.user_char_limit", R.string.configpages_profile_budget, R.string.configpages_profile_budget_help, Ctl.Number(1375.0), true),
    )),
    Section(R.string.configpages_section_context, AF.DonutLarge, listOf(
        Field("context.engine", R.string.configpages_context_engine, R.string.configpages_context_engine_help, Ctl.Options(emptyList(), "default")),
        Field("compression.enabled", R.string.configpages_compression, R.string.configpages_compression_help, Ctl.Toggle(true)),
        Field("compression.threshold", R.string.configpages_compression_threshold, R.string.configpages_compression_threshold_help, Ctl.Number(0.8)),
        Field("compression.target_ratio", R.string.configpages_compression_target, R.string.configpages_compression_target_help, Ctl.Number(0.5)),
        Field("compression.protect_last_n", R.string.configpages_protected, R.string.configpages_protected_help, Ctl.Number(8.0)),
        Field("compression.codex_gpt55_autoraise", R.string.configpages_codex_autoraise, R.string.configpages_codex_autoraise_help, Ctl.Toggle(true), true),
        Field("auxiliary.compression.timeout", R.string.configpages_compression_timeout, R.string.configpages_compression_timeout_help, Ctl.Number(120.0), true),
    )),
    Section(R.string.configpages_section_delegation, AF.AccountTree, listOf(
        Field("delegation.max_concurrent_children", R.string.configpages_children, R.string.configpages_children_help, Ctl.Number(2.0)),
        Field("delegation.max_iterations", R.string.configpages_child_turns, R.string.configpages_child_turns_help, Ctl.Number(250.0), true),
        Field("delegation.child_timeout_seconds", R.string.configpages_child_timeout, R.string.configpages_child_timeout_help, Ctl.Number(0.0), true),
    )),
)

val ADVANCED_SECTIONS = listOf(
    Section(R.string.configpages_section_execution, AF.Memory, listOf(
        Field("agent.max_turns", R.string.configpages_max_turns, R.string.configpages_max_turns_help, Ctl.Number(0.0), true),
        Field("agent.api_max_retries", R.string.configpages_api_retries, R.string.configpages_api_retries_help, Ctl.Number(3.0), true),
    )),
    Section(R.string.configpages_section_terminal, AF.Terminal, listOf(
        Field("terminal.timeout", R.string.configpages_command_timeout, R.string.configpages_command_timeout_help, Ctl.Number(180.0), true),
        Field("terminal.docker_image", R.string.configpages_docker_image, R.string.configpages_docker_image_help, Ctl.Text(), true),
        Field("terminal.singularity_image", R.string.configpages_singularity_image, R.string.configpages_singularity_image_help, Ctl.Text(), true),
        Field("terminal.modal_image", R.string.configpages_modal_image, R.string.configpages_modal_image_help, Ctl.Text(), true),
        Field("terminal.daytona_image", R.string.configpages_daytona_image, R.string.configpages_daytona_image_help, Ctl.Text(), true),
    )),
    Section(R.string.configpages_section_files_output, AF.Article, listOf(
        Field("tool_output.max_bytes", R.string.configpages_output_bytes, R.string.configpages_output_bytes_help, Ctl.Number(50_000.0), true),
        Field("tool_output.max_lines", R.string.configpages_output_lines, R.string.configpages_output_lines_help, Ctl.Number(2000.0), true),
        Field("tool_output.max_line_length", R.string.configpages_line_length, R.string.configpages_line_length_help, Ctl.Number(2000.0), true),
        Field("checkpoints.max_snapshots", R.string.configpages_snapshots, R.string.configpages_snapshots_help, Ctl.Number(20.0), true),
    )),
    Section(R.string.configpages_section_updates, AF.SystemUpdate, listOf(
        Field("updates.non_interactive_local_changes", R.string.configpages_local_changes, R.string.configpages_local_changes_help, Ctl.Options(listOf("stash", "discard"), "stash"), true),
    )),
)

val MODEL_FIELDS = listOf(
    Field("agent.service_tier", R.string.configpages_fast_mode, R.string.configpages_fast_mode_help, Ctl.Options(emptyList(), ""), true),
    Field("model_context_length", R.string.configpages_context_window, R.string.configpages_context_window_help, Ctl.Number(0.0), true),
)

/** A pushed settings page: back arrow, title, scrolling column. */
@Composable
fun SettingsPage(title: String, onBack: () -> Unit, bottomInset: Dp, content: @Composable ColumnScope.() -> Unit) {
    val c = C.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).pressable(label = stringResource(R.string.configpages_back), onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(app.hermesandroid.ui.icons.AF.Back, null, tint = c.text, modifier = Modifier.size(22.dp))
            }
            Text(title, style = CType.title2, color = c.text, maxLines = 1)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

/** A titled card (SettingsSectionHeader + settingsCard) with padded content. */
@Composable
fun Card(title: String?, icon: ImageVector, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = C.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) app.hermesandroid.ui.components.Eyebrow(title, Modifier.padding(horizontal = 16.dp))
        Column(Modifier.fillMaxWidth().settingsCard(app.hermesandroid.ui.theme.Radii.lg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun Help(text: String, modifier: Modifier = Modifier) = Text(text, style = CType.footnote, color = C.colors.secondary, modifier = modifier)

/** A value with a menu of choices. */
@Composable
fun Picker(label: String?, value: String, choices: List<Pair<String, String>>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val c = C.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(app.hermesandroid.ui.theme.Radii.md).background(c.raised)
                .pressable(enabled = enabled && choices.isNotEmpty()) { open = true }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) Text(label, style = CType.subheadline, color = c.secondary, modifier = Modifier.weight(1f))
            Text(choices.firstOrNull { it.first == value }?.second ?: value.ifEmpty { stringResource(R.string.configpages_default) }, style = CType.subheadline.medium(), color = c.text,
                modifier = if (label == null) Modifier.weight(1f) else Modifier)
            Icon(AF.UnfoldMore, null, tint = c.secondary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        DropdownMenu(open, { open = false }) {
            choices.forEach { (id, title) ->
                DropdownMenuItem({ Text(title, style = if (id == value) CType.subheadline.semibold() else CType.subheadline) }, { open = false; onSelect(id) })
            }
        }
    }
}

@Composable
fun SmallField(value: String, onChange: (String) -> Unit, placeholder: String, number: Boolean = false, multiline: Boolean = false, modifier: Modifier = Modifier) {
    val c = C.colors
    Box(modifier.clip(app.hermesandroid.ui.theme.Radii.md).background(c.raised).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(placeholder, style = CType.subheadline, color = c.tertiary)
        BasicTextField(
            value, onChange, singleLine = !multiline, minLines = if (multiline) 3 else 1,
            textStyle = CType.subheadline.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SwitchRow(title: String, help: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = C.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = CType.callout.medium(), color = c.text)
            if (help != null) Help(help)
        }
        Switch(value, onChange, enabled = enabled)
    }
}

/**
 * The fields of a profile settings page: loads the config and schema once, saves each field on its own
 * (optimistically, restoring the old value on failure).
 */
@Composable
fun ConfigFields(graph: AppGraph, sections: List<Section>, overrides: Map<String, List<String>> = emptyMap()) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    val values = remember { mutableStateMapOf<String, JsonElement>() }
    val drafts = remember { mutableStateMapOf<String, String>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var schema by remember { mutableStateOf<Map<String, SchemaField>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph.profile) {
        loading = true
        coroutineScope {
            val s = async { graph.hostConfig.schema() }
            runCatching { graph.hostConfig.get() }.onSuccess { cfg ->
                values.clear(); drafts.clear()
                sections.flatMap { it.fields }.forEach { f -> cfg.at(f.key)?.let { values[f.key] = it } }
                loadError = null
            }.onFailure { loadError = tr(R.string.configpages_load_error, it.message.toString()) }
            schema = s.await()
        }
        loading = false
    }

    fun save(f: Field, v: JsonElement) {
        val previous = values[f.key]
        values[f.key] = v
        errors.remove(f.key)
        scope.launch {
            saving = f.key
            runCatching { graph.hostConfig.set(f.key, v) }.onFailure {
                if (previous == null) values.remove(f.key) else values[f.key] = previous
                errors[f.key] = tr(R.string.configpages_save_error, it.message.toString())
            }.onSuccess { saved = f.key; drafts.remove(f.key) }
            saving = null
            delay(2000)
            if (saved == f.key) saved = null
        }
    }

    if (loading) {
        Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = c.accent) }
        return
    }
    loadError?.let { Text(it, style = CType.footnote, color = c.red); return }
    sections.forEach { sec ->
        val shown = sec.fields.mapNotNull { it.resolved(schema) }
        if (shown.isEmpty()) return@forEach
        Card(sec.title?.let { stringResource(it) }, sec.icon) {
            shown.forEachIndexed { i, f ->
                if (i > 0) app.hermesandroid.ui.components.Divider(inset = 0.dp)
                FieldRow(f, values[f.key], drafts[f.key], overrides[f.key], saving, onDraft = { drafts[f.key] = it; errors.remove(f.key) }, onError = { errors[f.key] = it }) { save(f, it) }
                when {
                    saving == f.key -> Text(stringResource(R.string.configpages_saving), style = CType.caption, color = c.secondary)
                    errors[f.key] != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AF.Warning, null, tint = c.red, modifier = Modifier.size(13.dp)); Text(" " + errors[f.key], style = CType.caption, color = c.red)
                    }
                    saved == f.key -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AF.CheckCircle, null, tint = c.success, modifier = Modifier.size(13.dp)); Text(" " + stringResource(R.string.configpages_saved), style = CType.caption, color = c.success)
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(f: Field, value: JsonElement?, draft: String?, override: List<String>?, saving: String?, onDraft: (String) -> Unit, onError: (String) -> Unit, save: (JsonElement) -> Unit) {
    val c = C.colors
    val title = stringResource(f.label)
    val help = stringResource(f.help)
    val label = @Composable {
        Column {
            Text(title, style = CType.callout.semibold(), color = c.text)
            Help(help)
        }
    }
    when (val ctl = f.ctl) {
        is Ctl.Toggle -> SwitchRow(title, help, value.settingBool() ?: ctl.def) { save(JsonPrimitive(it)) }
        is Ctl.TextToggle -> SwitchRow(title, help, (value.settingText() ?: if (ctl.def) ctl.on else ctl.off) != ctl.off) { save(JsonPrimitive(if (it) ctl.on else ctl.off)) }
        is Ctl.Options -> {
            label()
            val base = override?.takeIf { it.isNotEmpty() } ?: ctl.options
            val current = value.settingText() ?: ctl.def
            val all = if (current in base || current.isEmpty()) base else base + current
            Picker(null, current, all.map { v -> v to (ctl.labels[v]?.let { stringResource(it) } ?: optionLabel(f.key, v)) }, enabled = saving == null) { save(JsonPrimitive(it)) }
            if (all.isEmpty()) Help(stringResource(R.string.configpages_no_options))
        }
        is Ctl.Text, is Ctl.Number, Ctl.TimeZone -> {
            label()
            val def = when (ctl) { is Ctl.Text -> ctl.def; is Ctl.Number -> numberText(ctl.def); else -> "" }
            val text = draft ?: value.settingText() ?: def
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallField(text, onDraft, if (ctl == Ctl.TimeZone) stringResource(R.string.configpages_server_time) else title, number = ctl is Ctl.Number, modifier = Modifier.weight(1f))
                SettingsButton(stringResource(R.string.configpages_save)) {
                    when (ctl) {
                        is Ctl.Number -> text.replace(',', '.').trim().toDoubleOrNull()?.let { save(numberJson(it)) } ?: onError(tr(R.string.configpages_invalid_number))
                        Ctl.TimeZone -> {
                            val t = text.trim()
                            if (t.isEmpty() || t in ZoneId.getAvailableZoneIds()) save(JsonPrimitive(t))
                            else onError(tr(R.string.configpages_invalid_zone))
                        }
                        else -> save(JsonPrimitive(text))
                    }
                }
            }
            if (ctl == Ctl.TimeZone) {
                val device = java.util.TimeZone.getDefault().id
                val savedZone = value.settingText().orEmpty()
                if (savedZone != device) SettingsButton(stringResource(R.string.configpages_use_phone_zone, device)) { onDraft(device); save(JsonPrimitive(device)) }
                if (savedZone.isNotBlank()) SettingsButton(stringResource(R.string.configpages_use_server_zone)) { onDraft(""); save(JsonPrimitive("")) }
            }
        }
        Ctl.Lines -> {
            label()
            val text = draft ?: value.settingText().orEmpty()
            SmallField(text, onDraft, stringResource(R.string.configpages_one_per_line), multiline = true, modifier = Modifier.fillMaxWidth())
            SettingsButton(stringResource(R.string.configpages_save)) { save(JsonArray(listEntries(text).map(::JsonPrimitive))) }
        }
    }
}

// ---- Model (ProfileModelSettingsDetail, FallbackModelsSettings, DelegationModelSettings) ----

data class ModelProvider(val slug: String, val title: String, val current: Boolean, val models: List<Pair<String, String>>) {
    fun matches(name: String) = name.isNotBlank() && (slug.equals(name.trim(), true) || title.equals(name.trim(), true))
}

private fun parseProviders(o: JsonObject?): List<ModelProvider> = (o?.get("providers") as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { p ->
    val slug = p.str("slug") ?: p.str("id") ?: p.str("name") ?: return@mapNotNull null
    val models = (p["models"] as? JsonArray).orEmpty().mapNotNull { m ->
        (m as? JsonPrimitive)?.contentOrNull?.let { it to it } ?: m.obj()?.let { mo -> (mo.str("id") ?: mo.str("model") ?: mo.str("name"))?.let { it to (mo.str("label") ?: it) } }
    }
    ModelProvider(slug, p.str("name") ?: slug, p.bool("is_current") == true, models)
}

private val REASONING = listOf(
    "none" to R.string.configpages_reasoning_none, "minimal" to R.string.configpages_reasoning_minimal, "low" to R.string.configpages_reasoning_low,
    "medium" to R.string.configpages_reasoning_medium, "high" to R.string.configpages_reasoning_high, "xhigh" to R.string.configpages_reasoning_xhigh,
    "max" to R.string.configpages_reasoning_max, "ultra" to R.string.configpages_reasoning_ultra,
)

@Composable
private fun reasoningChoices() = REASONING.map { (id, label) -> id to stringResource(label) }

/** Choices with the saved value first when the list doesn't have it (never swapped for the first row). */
private fun withSaved(list: List<Pair<String, String>>, saved: String) = if (saved.isEmpty() || list.any { it.first == saved }) list else listOf(saved to saved) + list

@Composable
fun ModelPage(graph: AppGraph) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    val client = graph.client
    var providers by remember { mutableStateOf<List<ModelProvider>?>(null) }
    var provider by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf("medium") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val fallbacks = remember { mutableStateListOf<JsonObject>() }
    var savedFallbacks by remember { mutableStateOf<List<JsonObject>?>(null) }
    val savedText = stringResource(R.string.configpages_saved_dot)
    LaunchedEffect(graph.profile) {
        runCatching {
            coroutineScope {
                val opts = async { client.rest("GET", client.withProfile("/api/model/options?explicit_only=true")).obj() }
                val info = async { runCatching { client.rest("GET", client.withProfile("/api/model/info")).obj() }.getOrNull() }
                val cfg = graph.hostConfig.get()
                val list = parseProviders(opts.await())
                val mc = cfg["model"].obj()
                val savedModel = listOf(mc?.str("default"), (cfg["model"] as? JsonPrimitive)?.contentOrNull, info.await()?.str("model"), opts.await()?.str("model")).firstOrNull { !it.isNullOrBlank() }.orEmpty()
                val savedProvider = listOf(mc?.str("provider"), info.await()?.str("provider"), opts.await()?.str("provider")).firstOrNull { !it.isNullOrBlank() }.orEmpty()
                val row = list.firstOrNull { it.matches(savedProvider) } ?: list.firstOrNull { it.current } ?: list.filter { r -> r.models.any { it.first == savedModel } }.singleOrNull()
                when {
                    row != null -> { provider = row.slug; model = savedModel.ifEmpty { row.models.firstOrNull()?.first.orEmpty() } }
                    savedProvider.isEmpty() && savedModel.isEmpty() && list.isNotEmpty() -> { provider = list[0].slug; model = list[0].models.firstOrNull()?.first.orEmpty() }
                    else -> { provider = savedProvider; model = savedModel }
                }
                reasoning = reasoningEffort(cfg)
                val fb = (cfg["fallback_providers"] as? JsonArray).orEmpty().mapNotNull { e ->
                    e.obj() ?: (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                        buildJsonObject { put("provider", if ('/' in t) t.substringBefore('/') else ""); put("model", if ('/' in t) t.substringAfter('/') else t) }
                    }
                }
                fallbacks.clear(); fallbacks.addAll(fb); savedFallbacks = fb
                providers = list
            }
        }.onFailure { message = tr(R.string.configpages_models_error, it.message.toString()); providers = emptyList() }
    }
    val list = providers
    fun modelsOf(p: String) = list?.firstOrNull { it.slug == p }?.models.orEmpty()

    Card(stringResource(R.string.configpages_default_model), AF.Memory) {
        Help(stringResource(R.string.configpages_default_model_help))
        if (list == null) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
        else {
            Picker(stringResource(R.string.configpages_provider), provider, withSaved(list.map { it.slug to it.title }, provider)) { p ->
                provider = p
                if (modelsOf(p).none { it.first == model }) model = modelsOf(p).firstOrNull()?.first.orEmpty()
            }
            Picker(stringResource(R.string.configpages_model), model, withSaved(modelsOf(provider), model)) { model = it }
            Picker(stringResource(R.string.configpages_reasoning), reasoning, reasoningChoices()) { reasoning = it }
            SettingsButton(stringResource(if (busy) R.string.configpages_saving else R.string.configpages_save_model), prominent = true) {
                if (busy || provider.isEmpty() || model.isEmpty()) return@SettingsButton
                scope.launch {
                    busy = true; message = null
                    runCatching {
                        val r = client.rest("POST", client.withProfile("/api/model/set"), buildJsonObject {
                            put("model", model); put("provider", provider); put("scope", "main"); put("profile", graph.profile)
                        }).obj()
                        if (r?.bool("confirm_required") == true) { message = r.str("confirm_message") ?: tr(R.string.configpages_model_confirm); return@runCatching }
                        graph.hostConfig.update { cfg ->
                            val mc = (cfg["model"].obj() ?: JsonObject(emptyMap())).with("default", JsonPrimitive(model)).with("provider", JsonPrimitive(provider))
                            cfg.with("model", mc).with("agent.reasoning_effort", JsonPrimitive(reasoning)).with("reasoning", null)
                        }
                        message = savedText
                    }.onFailure { message = tr(R.string.configpages_save_error, it.message.toString()) }
                    busy = false
                }
            }
        }
        message?.let { Text(it, style = CType.footnote, color = if (it == savedText) c.success else c.red) }
    }
    ConfigFields(graph, listOf(Section(R.string.configpages_section_speed, AF.Bolt, MODEL_FIELDS)))
    Card(stringResource(R.string.configpages_fallbacks), AF.CallSplit) {
        Help(stringResource(R.string.configpages_fallbacks_help))
        if (list == null) return@Card
        if (fallbacks.isEmpty()) Help(stringResource(R.string.configpages_no_fallbacks))
        fallbacks.forEachIndexed { i, e ->
            val p = e.str("provider").orEmpty()
            val m = e.str("model").orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.configpages_fallback_n, i + 1), style = CType.subheadline.semibold(), color = c.text, modifier = Modifier.weight(1f))
                if (i > 0) Text("↑", color = c.accent, modifier = Modifier.pressable(label = stringResource(R.string.configpages_move_up)) { fallbacks.add(i - 1, fallbacks.removeAt(i)) }.padding(8.dp))
                if (i < fallbacks.size - 1) Text("↓", color = c.accent, modifier = Modifier.pressable(label = stringResource(R.string.configpages_move_down)) { fallbacks.add(i + 1, fallbacks.removeAt(i)) }.padding(8.dp))
                Text(stringResource(R.string.configpages_remove), style = CType.footnote, color = c.red, modifier = Modifier.pressable { fallbacks.removeAt(i) }.padding(8.dp))
            }
            // A new provider drops the entry's hand-written routing (base_url, key_env…); the model stays only if listed.
            Picker(stringResource(R.string.configpages_provider), p, withSaved(list.map { it.slug to it.title }, p)) { np ->
                if (np != p) fallbacks[i] = buildJsonObject { put("provider", np); put("model", modelsOf(np).firstOrNull { it.first == m }?.first ?: modelsOf(np).firstOrNull()?.first.orEmpty()) }
            }
            Picker(stringResource(R.string.configpages_model), m, withSaved(modelsOf(p), m)) { fallbacks[i] = e.with("model", JsonPrimitive(it)) }
        }
        val complete = fallbacks.all { !it.str("provider").isNullOrBlank() && !it.str("model").isNullOrBlank() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsButton(stringResource(R.string.configpages_add)) {
                val row = list.firstOrNull { !it.current } ?: list.firstOrNull() ?: return@SettingsButton
                fallbacks += buildJsonObject { put("provider", row.slug); put("model", row.models.firstOrNull()?.first.orEmpty()) }
            }
            if (fallbacks.toList() != savedFallbacks && complete) SettingsButton(stringResource(R.string.configpages_save_fallbacks), prominent = true) {
                scope.launch {
                    val next = fallbacks.toList()
                    runCatching { graph.hostConfig.update { it.with("fallback_providers", JsonArray(next)) } }
                        .onSuccess { savedFallbacks = next }.onFailure { message = tr(R.string.configpages_fallbacks_error, it.message.toString()) }
                }
            }
        }
        if (!complete) Help(stringResource(R.string.configpages_fallbacks_incomplete))
    }
}

@Composable
fun ChatSettingsPage(graph: AppGraph) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var personalities by remember { mutableStateOf(PERSONALITIES) }
    var busyMode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(graph.profile) {
        runCatching { graph.hostConfig.get() }.getOrNull()?.at("agent.personalities")?.obj()?.keys?.let { extra -> personalities = PERSONALITIES + extra.sorted().filter { it !in PERSONALITIES } }
        busyMode = runCatching { graph.client.call("config.set", buildJsonObject { put("key", "busy"); put("value", "status") }).obj()?.str("value")?.lowercase() }.getOrNull() ?: "interrupt"
    }
    Card(stringResource(R.string.configpages_busy), AF.Tune) {
        Help(stringResource(R.string.configpages_busy_help))
        val modes = listOf("steer", "queue", "interrupt")
        busyMode?.let { mode ->
            Segmented(listOf(stringResource(R.string.configpages_busy_steer), stringResource(R.string.configpages_busy_queue), stringResource(R.string.configpages_busy_interrupt)), modes.indexOf(mode).coerceAtLeast(0)) { i ->
                val prev = mode
                busyMode = modes[i]
                scope.launch {
                    runCatching { graph.client.call("config.set", buildJsonObject { put("key", "busy"); put("value", modes[i]) }) }
                        .onSuccess { graph.chat.busyAction = app.hermesandroid.chat.BusyAction.entries[listOf(1, 0, 2)[i]] }
                        .onFailure { busyMode = prev }
                }
            }
        } ?: CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
    }
    ConfigFields(graph, listOf(Section(R.string.configpages_section_hermes, AF.SettingsSuggest, CHAT_FIELDS)), mapOf("display.personality" to personalities))
    Card(stringResource(R.string.configpages_text_size), AF.TextFields) {
        Help(stringResource(R.string.configpages_text_size_help))
        val steps = listOf(0.85f, 0.92f, 1f, 1.12f, 1.25f)
        Slider(
            steps.indexOfFirst { it >= DisplayPrefs.chatScale - 0.001f }.coerceAtLeast(0).toFloat(), { graph.setChatScale(steps[it.toInt()]) },
            valueRange = 0f..4f, steps = 3, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
        )
        Text(stringResource(R.string.configpages_text_size_sample, profileTitle(graph.profile)), style = app.hermesandroid.ui.theme.chatTextStyle(), color = c.text)
    }
    Card(stringResource(R.string.configpages_vibration), AF.Vibration) {
        SwitchRow(stringResource(R.string.configpages_haptics), stringResource(R.string.configpages_haptics_help), DisplayPrefs.haptics) { graph.setHaptics(it) }
    }
}

@Composable
fun MemoryPage(graph: AppGraph) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var overrides by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var paused by remember { mutableStateOf<Boolean?>(null) }
    var pauseLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(graph.profile) {
        runCatching { graph.client.rest("GET", graph.client.withProfile("/api/dashboard/plugins/hub")).obj()?.get("providers").obj() }.getOrNull()?.let { p ->
            val memory = (p["memory_options"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.filter { it.str("status") == "ready" }.mapNotNull { it.str("name") }.sorted()
            val ctx = (listOf("default", "custom") + (p["context_options"] as? JsonArray).orEmpty().mapNotNull { it.obj()?.str("name") }).distinct().sorted()
            overrides = mapOf("memory.provider" to memory, "context.engine" to ctx)
        } ?: run { overrides = mapOf("context.engine" to listOf("custom", "default")) }
        paused = graph.chat.delegationPaused(); pauseLoaded = true
    }
    Help(stringResource(R.string.configpages_memory_help))
    ConfigFields(graph, MEMORY_SECTIONS, overrides)
    DelegateModel(graph)
    Card(stringResource(R.string.configpages_pause_delegation), AF.AccountTree) {
        val p = paused
        when {
            p != null -> SwitchRow(stringResource(R.string.configpages_pause_new), stringResource(R.string.configpages_pause_new_help), p) { v ->
                scope.launch { graph.chat.setDelegationPaused(v)?.let { paused = it } }
            }
            pauseLoaded -> Help(stringResource(R.string.configpages_pause_unavailable))
            else -> CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun DelegateModel(graph: AppGraph) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<ModelProvider>?>(null) }
    var provider by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var reasoning by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val savedText = stringResource(R.string.configpages_saved_dot)
    val inherit = stringResource(R.string.configpages_inherit)
    LaunchedEffect(graph.profile) {
        runCatching {
            val list = parseProviders(graph.client.rest("GET", graph.client.withProfile("/api/model/options?explicit_only=true")).obj())
            val cfg = graph.hostConfig.get()
            val sp = cfg.at("delegation.provider").settingText().orEmpty()
            provider = if (sp.isBlank()) "" else list.firstOrNull { it.matches(sp) }?.slug ?: sp
            model = cfg.at("delegation.model").settingText().orEmpty()
            reasoning = cfg.at("delegation.reasoning_effort").settingText().orEmpty()
            providers = list
        }.onFailure { message = it.message; providers = emptyList() }
    }
    Card(stringResource(R.string.configpages_delegate_model), AF.AccountTree) {
        Help(stringResource(R.string.configpages_delegate_model_help))
        val list = providers ?: run { CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp); return@Card }
        val models = (if (provider.isEmpty()) list.firstOrNull { it.current } else list.firstOrNull { it.slug == provider })?.models.orEmpty()
        Picker(stringResource(R.string.configpages_provider), provider, listOf("" to inherit) + withSaved(list.map { it.slug to it.title }, provider)) { p ->
            provider = p
            model = if (p.isEmpty()) "" else list.firstOrNull { it.slug == p }?.models?.let { ms -> ms.firstOrNull { it.first == model }?.first ?: ms.firstOrNull()?.first } .orEmpty()
        }
        if (provider.isNotEmpty() || model.isNotEmpty()) Picker(stringResource(R.string.configpages_model), model, listOf("" to inherit) + withSaved(models, model)) { model = it }
        Picker(stringResource(R.string.configpages_reasoning), reasoning, listOf("" to inherit) + reasoningChoices().drop(1)) { reasoning = it }
        SettingsButton(stringResource(R.string.configpages_save_delegate), prominent = true) {
            scope.launch {
                message = runCatching {
                    graph.hostConfig.set("delegation.provider", JsonPrimitive(provider))
                    graph.hostConfig.set("delegation.model", JsonPrimitive(model))
                    graph.hostConfig.set("delegation.reasoning_effort", JsonPrimitive(reasoning))
                    savedText
                }.getOrElse { tr(R.string.configpages_save_error, it.message.toString()) }
            }
        }
        message?.let { Text(it, style = CType.footnote, color = if (it == savedText) c.success else c.red) }
    }
}
