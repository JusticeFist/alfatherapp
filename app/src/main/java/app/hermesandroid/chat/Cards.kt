package app.hermesandroid.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.bold
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.semibold
import app.hermesandroid.ui.theme.settingsCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import app.hermesandroid.ui.icons.AF
import androidx.compose.ui.res.stringResource
import app.hermesandroid.R

// Rich cards drawn from the notifier plugin's `conduit_show` tool (card-schema.json, max 4 per call).
// Kept per type: places (map/place/places), people (contact), else the field named like the type (options for comparison).

const val MAX_CARDS = 4
private val cardJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun isCardTool(name: String) = name == "conduit_show" || name == "conduit_present"

/** Cards from the tool output's `cards`, or from its input when the output is just `{ok:true}`. */
fun cardsOf(m: ChatMsg): List<JsonObject> {
    fun arr(s: String) = runCatching { cardJson.parseToJsonElement(s).obj()?.get("cards") as? JsonArray }.getOrNull()
    val list = arr(m.result) ?: arr(m.args) ?: return emptyList()
    return list.mapNotNull { it.obj() }.filter { validCard(it) }.take(MAX_CARDS)
}

private val FIELD = mapOf(
    "map" to "places", "place" to "places", "places" to "places", "contact" to "people", "comparison" to "options",
    "stats" to "stats", "chart" to "chart", "weather" to "weather", "steps" to "steps", "media" to "media", "status" to "status",
)

private fun validCard(o: JsonObject): Boolean {
    val field = FIELD[o.str("type")] ?: return false
    return o[field] != null
}

@Composable
fun RichCard(card: JsonObject) {
    val c = C.colors
    Column(Modifier.fillMaxWidth().settingsCard(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        card.str("title")?.let { Text(it, style = CType.headline, color = c.text) }
        when (card.str("type")) {
            "map", "place", "places" -> Places(card)
            "stats" -> Stats(card["stats"] as? JsonArray)
            "chart" -> Chart(card["chart"].obj())
            "weather" -> Weather(card["weather"].obj())
            "comparison" -> Comparison(card["options"] as? JsonArray)
            "steps" -> Steps(card["steps"] as? JsonArray)
            "media" -> MediaList(card["media"] as? JsonArray)
            "contact" -> People(card["people"] as? JsonArray)
            "status" -> Status(card["status"].obj())
        }
        card["source"].obj()?.str("name")?.let { Text(stringResource(R.string.cards_source, it), style = CType.caption, color = c.secondary) }
    }
}

private fun JsonArray?.objs() = orEmpty().mapNotNull { it.obj() }
private fun JsonArray?.strings() = orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** ponytail: places as a list with "open in Maps" (geo: intent); an in-card map (MapLibre) when worth the size. */
@Composable
private fun Places(card: JsonObject) {
    val c = C.colors
    val ctx = LocalContext.current
    val places = (card["places"] as? JsonArray).objs().let { if (card.str("type") == "place") it.take(1) else it }
    val route = card["route"].obj()
    places.forEachIndexed { i, p ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(AF.Place, null, tint = c.accent, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.str("name").orEmpty(), style = CType.callout.semibold(), color = c.text)
                p.str("address")?.let { Text(it, style = CType.footnote, color = c.secondary) }
                p["rating"].obj()?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(AF.Star, null, tint = c.yellow, modifier = Modifier.size(12.dp))
                        Text("${r.num("value") ?: ""} · ${r.str("source").orEmpty()}", style = CType.caption, color = c.secondary)
                    }
                }
                listOfNotNull(p["hours"].obj()?.str("text"), p["price"].obj()?.str("text")).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = CType.caption, color = c.secondary)
                }
                p.str("note")?.let { Text(it, style = CType.footnote, color = c.text) }
            }
            Box(Modifier.size(36.dp).pressable(label = stringResource(R.string.cards_open_maps)) { openPlace(ctx, p, directions = route?.num("to")?.toInt() == i) }, contentAlignment = Alignment.Center) {
                Icon(AF.Directions, null, tint = c.accent)
            }
        }
    }
}

private fun openPlace(ctx: android.content.Context, p: JsonObject, directions: Boolean) {
    val lat = p.num("lat")
    val lon = p.num("lon")
    val q = Uri.encode(listOfNotNull(p.str("name"), p.str("address")).joinToString(", "))
    val uri = when {
        directions && lat != null && lon != null -> "google.navigation:q=$lat,$lon"
        lat != null && lon != null -> "geo:$lat,$lon?q=$lat,$lon($q)"
        else -> "geo:0,0?q=$q"
    }
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Stats(stats: JsonArray?) {
    val c = C.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.objs().forEach { s ->
            Column(Modifier.width(140.dp)) {
                Text(s.str("label").orEmpty(), style = CType.caption.semibold(), color = c.secondary)
                Text(s.str("value").orEmpty(), style = CType.title2.bold(), color = c.text)
                val tone = when (s.str("tone")) { "good" -> c.success; "bad" -> c.red; else -> c.secondary }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.str("change")?.let { Text(it, style = CType.caption.semibold(), color = tone) }
                    s.str("detail")?.let { Text(it, style = CType.caption, color = c.secondary) }
                }
                (s["spark"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }?.takeIf { it.size > 1 }?.let { Sparkline(it, tone) }
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(24.dp).padding(top = 4.dp)) {
        val min = values.min()
        val span = (values.max() - min).takeIf { it > 0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1 - ((v - min) / span)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

/** Line / area / bar charts and a pie; one colour per series from the accent family. */
@Composable
private fun Chart(chart: JsonObject?) {
    chart ?: return
    val c = C.colors
    val palette = listOf(c.accent, c.aura, c.success, c.scheduled, c.orange, c.voice)
    val labels = (chart["labels"] as? JsonArray).strings()
    val series = (chart["series"] as? JsonArray).objs().mapNotNull { s ->
        val values = (s["values"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
        if (values.size == labels.size && values.isNotEmpty()) s.str("name").orEmpty() to values else null
    }
    if (series.isEmpty()) return
    val kind = chart.str("kind") ?: "line"
    if (kind == "pie") {
        val values = series.first().second
        val total = values.sum().takeIf { it > 0 } ?: return
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Canvas(Modifier.size(120.dp)) {
                var start = -90f
                values.forEachIndexed { i, v ->
                    val sweep = (v / total * 360).toFloat()
                    drawArc(palette[i % palette.size], start, sweep, true, Offset.Zero, Size(size.width, size.height))
                    start += sweep
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEachIndexed { i, l -> Legend(palette[i % palette.size], "$l · ${values[i].fmt()}") }
            }
        }
        return
    }
    val max = series.maxOf { it.second.max() }.takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val n = labels.size
        series.forEachIndexed { si, (_, values) ->
            val color = palette[si % palette.size]
            if (kind == "bar") {
                val slot = size.width / n
                val bw = slot / (series.size + 1)
                values.forEachIndexed { i, v ->
                    val h = (v / max * size.height).toFloat()
                    drawRect(color, Offset(slot * i + bw * (si + 0.5f), size.height - h), Size(bw * 0.9f, h))
                }
            } else {
                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = if (n == 1) 0f else size.width * i / (n - 1)
                    val y = size.height * (1 - (v / max)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(2.5.dp.toPx()))
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${labels.first()} … ${labels.last()}${chart.str("unit")?.let { " ($it)" }.orEmpty()}", style = CType.caption, color = c.secondary)
        series.forEachIndexed { i, (name, _) -> if (name.isNotBlank()) Legend(palette[i % palette.size], name) }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = CType.caption, color = C.colors.text)
    }
}

private fun Double.fmt() = if (this % 1.0 == 0.0) toLong().toString() else "%.1f".format(this)

private val WEATHER = mapOf(
    "clear" to "☀️", "partly_cloudy" to "⛅", "cloudy" to "☁️", "fog" to "🌫️", "drizzle" to "🌦️", "rain" to "🌧️",
    "heavy_rain" to "🌧️", "thunderstorm" to "⛈️", "snow" to "🌨️", "sleet" to "🌨️", "wind" to "💨",
)

@Composable
private fun Weather(w: JsonObject?) {
    w ?: return
    val c = C.colors
    val unit = "°" + (w.str("unit") ?: "C")
    w["now"].obj()?.let { now ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(WEATHER[now.str("condition")] ?: "🌡️", style = CType.largeTitle)
            Column {
                Text("${now.num("temp")?.fmt() ?: "–"}$unit", style = CType.title2.bold(), color = c.text)
                Text(listOfNotNull(w.str("place"), now.str("text")).joinToString(" · "), style = CType.footnote, color = c.secondary)
            }
        }
    }
    (w["hours"] as? JsonArray).objs().takeIf { it.isNotEmpty() }?.let { hours ->
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            hours.forEach { h ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.str("time").orEmpty(), style = CType.caption, color = c.secondary)
                    Text(WEATHER[h.str("condition")] ?: "", style = CType.body)
                    Text("${h.num("temp")?.fmt() ?: ""}°", style = CType.footnote.semibold(), color = c.text)
                }
            }
        }
    }
    (w["days"] as? JsonArray).objs().forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.str("day").orEmpty(), style = CType.subheadline, color = c.text, modifier = Modifier.width(90.dp))
            Text(WEATHER[d.str("condition")] ?: "", style = CType.body, modifier = Modifier.width(36.dp))
            d.num("precip")?.takeIf { it > 0 }?.let { Text("${it.fmt()}%", style = CType.caption, color = c.aura, modifier = Modifier.width(40.dp)) }
            Box(Modifier.weight(1f))
            Text("${d.num("low")?.fmt() ?: ""}° / ${d.num("high")?.fmt() ?: ""}°", style = CType.subheadline.medium(), color = c.text)
        }
    }
}

@Composable
private fun Comparison(options: JsonArray?) {
    val c = C.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.objs().forEach { o ->
            val rec = o.bool("recommended") == true
            Column(
                Modifier.width(220.dp).clip(RoundedCornerShape(18.dp)).background(if (rec) c.accent.copy(alpha = 0.1f) else c.text.copy(alpha = 0.04f))
                    .border(1.dp, if (rec) c.accent.copy(alpha = 0.5f) else c.text.copy(alpha = 0.07f), RoundedCornerShape(18.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (rec) Text(stringResource(R.string.cards_recommended), style = CType.caption2.bold(), color = c.accent)
                Text(o.str("name").orEmpty(), style = CType.callout.semibold(), color = c.text)
                o.str("summary")?.let { Text(it, style = CType.footnote, color = c.secondary) }
                (o["facts"] as? JsonArray).objs().forEach { f -> Text("${f.str("label")}: ${f.str("value")}", style = CType.caption, color = c.text) }
                (o["pros"] as? JsonArray).strings().forEach { Text("＋ $it", style = CType.caption, color = c.success) }
                (o["cons"] as? JsonArray).strings().forEach { Text("－ $it", style = CType.caption, color = c.red) }
            }
        }
    }
}

@Composable
private fun Steps(steps: JsonArray?) {
    val c = C.colors
    steps.objs().forEachIndexed { i, s ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Text("${i + 1}", style = CType.caption.semibold(), color = c.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(s.str("text").orEmpty(), style = CType.subheadline, color = c.text)
                s.num("timer")?.let { secs ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(AF.Timer, null, tint = c.aura, modifier = Modifier.size(12.dp))
                        Text("%d:%02d".format(secs.toInt() / 60, secs.toInt() % 60), style = CType.caption, color = c.aura)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaList(media: JsonArray?) {
    val c = C.colors
    val ctx = LocalContext.current
    media.objs().forEach { m ->
        Row(
            Modifier.fillMaxWidth().pressable { (m.str("page") ?: m.str("url"))?.let { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.voice.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(AF.PlayArrow, null, tint = c.voice)
            }
            Column(Modifier.weight(1f)) {
                Text(m.str("title") ?: m.str("url").orEmpty(), style = CType.subheadline.semibold(), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(m.str("by"), m.str("caption")).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = CType.caption, color = c.secondary, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun People(people: JsonArray?) {
    val c = C.colors
    val ctx = LocalContext.current
    fun go(uri: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    people.objs().forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            app.hermesandroid.ui.shell.Avatar(p.str("name").orEmpty(), size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(p.str("name").orEmpty(), style = CType.callout.semibold(), color = c.text)
                (p.str("detail") ?: p.str("note"))?.let { Text(it, style = CType.footnote, color = c.secondary) }
            }
            p.str("phone")?.let { phone -> Icon(AF.Call, stringResource(R.string.cards_call), tint = c.success, modifier = Modifier.size(34.dp).pressable { go("tel:$phone") }.padding(6.dp)) }
            p.str("email")?.let { mail -> Icon(AF.Email, stringResource(R.string.cards_email), tint = c.aura, modifier = Modifier.size(34.dp).pressable { go("mailto:$mail") }.padding(6.dp)) }
        }
    }
}

@Composable
private fun Status(s: JsonObject?) {
    s ?: return
    val c = C.colors
    val tint = when (s.str("phase")) { "done" -> c.success; "problem" -> c.red; "waiting" -> c.secondary; else -> c.accent }
    Text(s.str("state").orEmpty(), style = CType.title3.semibold(), color = tint)
    s.str("detail")?.let { Text(it, style = CType.subheadline, color = c.text) }
    s.num("progress")?.let { LinearProgressIndicator(progress = { (if (it > 1) it / 100 else it).toFloat() }, color = tint, trackColor = tint.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) }
    s.str("eta")?.let { Text(stringResource(R.string.cards_eta, it), style = CType.footnote, color = c.secondary) }
    (s["milestones"] as? JsonArray).objs().forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val done = m.bool("done") == true
            Icon(if (done) AF.CheckCircle else AF.RadioButtonUnchecked, null, tint = if (done) c.success else c.tertiary, modifier = Modifier.size(16.dp))
            Text(m.str("label").orEmpty(), style = CType.subheadline, color = if (done) c.text else c.secondary)
        }
    }
}
