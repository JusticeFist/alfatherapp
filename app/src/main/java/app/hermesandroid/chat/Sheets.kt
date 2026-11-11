package app.hermesandroid.chat

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.components.SettingsGroup
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Haptic
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.hermesandroid.ui.icons.AF
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R
import app.hermesandroid.tr

// Bottom sheets of the conversation: model picker (ModelPickerView), context (ContextSheet), delegate agents.

/** Reasoning efforts and their names, for the model sheet and the composer's pill. */
internal val REASONING = listOf(
    "none" to R.string.sheets_reasoning_none, "minimal" to R.string.sheets_reasoning_minimal, "low" to R.string.sheets_reasoning_low,
    "medium" to R.string.sheets_reasoning_medium, "high" to R.string.sheets_reasoning_high, "xhigh" to R.string.sheets_reasoning_xhigh,
    "max" to R.string.sheets_reasoning_max, "ultra" to R.string.sheets_reasoning_ultra,
)

/** An effort's name ("high" → "High"); an unknown one as it comes. */
internal fun reasoningLabel(value: String) = REASONING.firstOrNull { it.first == value }?.second?.let { tr(it) } ?: value.replaceFirstChar { it.uppercase() }

private data class Provider(val slug: String, val title: String, val current: Boolean, val models: List<Pair<String, String?>>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = C.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = c.surface, contentColor = c.text,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            androidx.compose.foundation.layout.Box(
                Modifier.padding(top = 10.dp, bottom = 14.dp).size(width = 36.dp, height = 4.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(c.text.copy(alpha = 0.22f)),
            )
        },
    ) { content() }
}

@Composable
fun ModelPickerSheet(s: ChatState, onDismiss: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    val providers = remember { mutableStateListOf<Provider>() }
    var loading by remember { mutableStateOf(true) }
    var open by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(s.model to s.provider) }
    /** Confirmation text for an expensive model, with the model it is about. */
    var confirm by remember { mutableStateOf<Pair<String, Pair<String, String>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val r = s.modelOptions()
        (r?.get("providers") as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { p ->
            val slug = p.str("slug") ?: p.str("id") ?: p.str("name") ?: return@forEach
            val models = (p["models"] as? JsonArray).orEmpty().mapNotNull { m ->
                (m as? JsonPrimitive)?.contentOrNull?.let { it to null } ?: m.obj()?.let { o -> (o.str("id") ?: o.str("model") ?: o.str("name"))?.let { it to o.str("label") } }
            }
            providers += Provider(slug, p.str("name") ?: slug, p.bool("is_current") == true, models)
        }
        if (picked.first == null) picked = r?.str("model") to r?.str("provider")
        open = picked.second ?: providers.firstOrNull { it.current }?.slug
        loading = false
    }

    // Every pick applies at once (a separate "apply" button sat below the model list, and closing the sheet
    // without reaching it silently dropped the choice).
    fun run(block: suspend () -> Unit) = scope.launch {
        error = null
        try { block() } catch (e: Exception) { error = e.message }
    }
    fun pickModel(m: String, p: String, confirmed: Boolean = false) = run {
        s.setModel(m, p, confirmed)?.let { confirm = it to (m to p); return@run }
        picked = m to p
    }

    AfSheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text(stringResource(R.string.sheets_model), style = CType.headline, color = c.text, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) }
            error?.let { item { Text(it, style = CType.footnote, color = c.red) } }
            item {
                SettingsGroup(stringResource(R.string.sheets_reasoning), AF.Psychology) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        REASONING.forEach { (value, label) ->
                            val sel = s.reasoning == value
                            Text(
                                stringResource(label),
                                style = if (sel) CType.subheadline.semibold() else CType.subheadline,
                                color = if (sel) c.onAccent else c.text,
                                modifier = Modifier.heightIn(min = 36.dp).clip(CircleShape)
                                    .background(if (sel) c.accent else c.text.copy(alpha = 0.05f))
                                    .border(0.5.dp, if (sel) c.accent else c.text.copy(alpha = 0.07f), CircleShape)
                                    .pressable(haptic = Haptic.Selection) { if (!sel) run { s.setOption("reasoning", value) } }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.sheets_model), AF.Memory) {
                    if (loading) Text(stringResource(R.string.sheets_loading), style = CType.footnote, color = c.secondary, modifier = Modifier.padding(16.dp))
                    providers.forEachIndexed { i, p ->
                        if (i > 0) app.hermesandroid.ui.components.Divider(inset = 46.dp)
                        val expanded = open == p.slug
                        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).pressable(haptic = Haptic.Light) { open = if (expanded) null else p.slug }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(AF.ViewInAr, null, tint = c.accent, modifier = Modifier.width(20.dp).size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(p.title, style = CType.subheadline.semibold(), color = c.text, modifier = Modifier.weight(1f))
                            Text("${p.models.size}", style = CType.caption, color = c.secondary)
                            Icon(AF.ExpandMore, null, tint = c.tertiary, modifier = Modifier.rotate(if (expanded) 180f else 0f))
                        }
                        if (expanded) p.models.forEach { (id, label) ->
                            val sel = picked.first == id && picked.second == p.slug
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (sel) c.accent.copy(alpha = if (c.dark) 0.14f else 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .pressable(haptic = Haptic.Selection) { if (!sel) pickModel(id, p.slug) }
                                    .padding(start = 34.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    // The name the composer's pill shows, then the server's label and the id it stands for.
                                    Text(modelLabel(id), style = CType.subheadline.medium(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    listOfNotNull(label, id.takeIf { it != modelLabel(it) }).joinToString(" · ").takeIf { it.isNotEmpty() }
                                        ?.let { Text(it, style = CType.caption, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                if (sel) Icon(AF.CheckCircle, null, tint = c.accent)
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.sheets_execution), AF.Tune) {
                    ToggleRow(stringResource(R.string.sheets_fast_mode), stringResource(R.string.sheets_fast_mode_detail), s.fast) { on -> run { s.setOption("fast", if (on) "fast" else "normal") } }
                    app.hermesandroid.ui.components.Divider(inset = 16.dp)
                    ToggleRow("YOLO", stringResource(R.string.sheets_yolo_detail), s.yolo) { on -> run { s.setOption("yolo", if (on) "1" else "0") } }
                }
            }
        }
    }
    confirm?.let { (msg, target) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.sheets_confirm_model)) },
            text = { Text(msg) },
            confirmButton = { TextButton({ confirm = null; pickModel(target.first, target.second, confirmed = true) }) { Text(stringResource(R.string.sheets_change)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.sheets_cancel)) } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = C.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = CType.callout.semibold(), color = c.text)
            Text(detail, style = CType.subheadline, color = c.text.copy(alpha = 0.58f))
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    }
}

@Composable
fun ContextSheet(s: ChatState, onDismiss: () -> Unit) {
    val c = C.colors
    var data by remember { mutableStateOf<JsonObject?>(null) }
    var focus by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { data = s.contextBreakdown() }
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.sheets_context), style = CType.headline, color = c.text)
            val d = data
            if (d == null) Text(stringResource(R.string.sheets_loading), style = CType.footnote, color = c.secondary)
            else {
                val used = (d.num("context_used") ?: d.num("estimated_total") ?: 0.0).toLong()
                val max = (d.num("context_max") ?: 0.0).toLong()
                Text(stringResource(R.string.sheets_tokens, used, max), style = CType.title3.semibold(), color = c.text)
                Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (d["categories"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.forEach { cat ->
                        Row {
                            Icon(AF.DonutLarge, null, tint = c.accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(cat.str("label").orEmpty(), style = CType.subheadline, color = c.text, modifier = Modifier.weight(1f))
                            Text("%,d".format((cat.num("tokens") ?: 0.0).toLong()), style = CType.subheadline.copy(fontFamily = CType.mono), color = c.secondary)
                        }
                    }
                }
            }
            Text(stringResource(R.string.sheets_compress_info), style = CType.footnote, color = c.secondary)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(c.text.copy(alpha = 0.06f)).padding(14.dp)) {
                Box(Modifier.weight(1f)) {
                    if (focus.isEmpty()) Text(stringResource(R.string.sheets_focus_hint), style = CType.body, color = c.tertiary)
                    BasicTextField(focus, { focus = it }, textStyle = CType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent), singleLine = true)
                }
            }
            SettingsButton(stringResource(R.string.sheets_compress_now), prominent = true) { s.compress(focus); onDismiss() }
        }
    }
}

@Composable
fun SubagentsSheet(s: ChatState, onDismiss: () -> Unit) {
    val c = C.colors
    val scope = rememberCoroutineScope()
    var paused by remember { mutableStateOf<Boolean?>(null) }
    var tailOf by remember { mutableStateOf<String?>(null) }
    var tail by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { s.refreshSubagents(); paused = s.delegationPaused() }
    LaunchedEffect(tailOf) { tailOf?.let { tail = s.tailSubagent(it) } }
    AfSheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AF.Group, null, tint = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sheets_agents_title, s.subagents.count { it.status == "running" }), style = CType.headline, color = c.text, modifier = Modifier.weight(1f))
                }
            }
            paused?.let { p ->
                item {
                    Row(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(20.dp)), verticalAlignment = Alignment.CenterVertically) {
                        ToggleRow(stringResource(R.string.sheets_pause_delegation), stringResource(R.string.sheets_pause_detail), p) { v -> scope.launch { paused = s.setDelegationPaused(v) ?: p } }
                    }
                }
            }
            if (s.subagents.isEmpty()) item { Text(stringResource(R.string.sheets_no_agents), style = CType.subheadline, color = c.secondary) }
            items(s.subagents, key = { it.id }) { a ->
                var steer by remember(a.id) { mutableStateOf("") }
                Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(22.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.goal.ifBlank { a.id }, style = CType.subheadline.semibold(), color = c.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(a.status, style = CType.caption.semibold(), color = if (a.status == "running") c.accent else c.secondary)
                    }
                    if (tailOf == a.id) Text(tail.takeLast(3000).ifBlank { stringResource(R.string.sheets_no_output) }, style = CType.data, color = c.text,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.text.copy(alpha = 0.06f)).padding(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsButton(stringResource(if (tailOf == a.id) R.string.sheets_hide_output else R.string.sheets_show_output)) { tailOf = if (tailOf == a.id) null else a.id }
                        if (a.status == "running") SettingsButton(stringResource(R.string.sheets_stop), AF.Stop) { s.interruptSubagent(a.id) }
                    }
                    if (a.status == "running") Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.text.copy(alpha = 0.05f)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            if (steer.isEmpty()) Text(stringResource(R.string.sheets_steer_hint), style = CType.subheadline, color = c.tertiary)
                            BasicTextField(steer, { steer = it }, textStyle = CType.subheadline.copy(color = c.text), cursorBrush = SolidColor(c.accent), singleLine = true)
                        }
                        Box(Modifier.size(40.dp).pressable(enabled = steer.isNotBlank()) { s.steerSubagent(a.id, steer); steer = "" }, contentAlignment = Alignment.Center) {
                            Icon(AF.Send, stringResource(R.string.sheets_send), tint = c.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
