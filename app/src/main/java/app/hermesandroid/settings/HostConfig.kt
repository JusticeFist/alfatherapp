package app.hermesandroid.settings

import app.hermesandroid.AppGraph
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// A profile's config on the host (AppState+profile settings): `GET /api/config`, a whole-config `PUT` that
// deep-merges objects and replaces lists, and `/api/config/schema` to hide keys an older Hermes would ignore.

/** One `/api/config/schema` entry: "boolean", "number", "string", "list", "object" or "select". */
data class SchemaField(val type: String, val options: List<String>)

fun parseSchema(o: JsonObject?): Map<String, SchemaField> =
    (o?.get("fields") as? JsonObject).orEmpty().mapNotNull { (k, v) ->
        val e = v.obj() ?: return@mapNotNull null
        k to SchemaField(e.str("type") ?: "string", (e["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
    }.toMap()

/** The value at a dotted key ("memory.provider"), or null. */
fun JsonObject.at(key: String): JsonElement? = key.split('.').fold(this as JsonElement?) { acc, k -> (acc as? JsonObject)?.get(k) }

/** A copy with the dotted key set (null removes it); missing parents become objects. */
fun JsonObject.with(key: String, value: JsonElement?): JsonObject = with(key.split('.'), value)

private fun JsonObject.with(parts: List<String>, value: JsonElement?): JsonObject {
    val m = toMutableMap()
    val head = parts.first()
    if (parts.size == 1) {
        if (value == null) m.remove(head) else m[head] = value
    } else {
        m[head] = ((this[head] as? JsonObject) ?: JsonObject(emptyMap())).with(parts.drop(1), value)
    }
    return JsonObject(m)
}

/** A setting as text: numbers without a trailing ".0", lists one entry per line. */
fun JsonElement?.settingText(): String? = when (this) {
    is JsonArray -> joinToString("\n") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
    is JsonPrimitive -> if (isString) content else doubleOrNull?.let(::numberText) ?: contentOrNull
    else -> null
}

/** A JSON boolean only: a count of 1 isn't a switch that's on. */
fun JsonElement?.settingBool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

fun numberText(d: Double) = if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/** Whole numbers go up as integers so Hermes' int settings stay ints. */
fun numberJson(d: Double): JsonPrimitive = if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

/** One entry per non-blank line. */
fun listEntries(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Hermes keeps the default reasoning at `agent.reasoning_effort`; older clients saved a top-level `reasoning`.
 * A hand-written false/off means none.
 */
fun reasoningEffort(config: JsonObject): String {
    for (v in listOf(config.at("agent.reasoning_effort"), config["reasoning"], config["reasoning_effort"])) {
        if (v.settingBool() == false) return "none"
        val t = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()
        if (t.isNullOrEmpty()) continue
        return if (t == "false" || t == "off" || t == "disabled") "none" else t
    }
    return "medium"
}

class HostConfig(private val graph: AppGraph) {
    private val client get() = graph.client
    /** One GET-change-PUT at a time: overlapping round trips would let the later PUT undo the earlier one. */
    private val writes = Mutex()

    suspend fun get(profile: String = graph.profile): JsonObject =
        client.rest("GET", client.withProfile("/api/config", profile)).obj() ?: JsonObject(emptyMap())

    /** Empty when the dashboard can't serve it; pages then show every setting. */
    suspend fun schema(): Map<String, SchemaField> =
        runCatching { parseSchema(client.rest("GET", client.withProfile("/api/config/schema")).obj()) }.getOrDefault(emptyMap())

    suspend fun update(profile: String = graph.profile, change: (JsonObject) -> JsonObject) = writes.withLock {
        val next = change(get(profile))
        client.rest("PUT", client.withProfile("/api/config", profile), buildJsonObject { put("config", next); put("profile", profile) })
    }

    suspend fun set(key: String, value: JsonElement) {
        val profile = graph.profile
        // The default profile's providers go through the plugin-provider route, which also reloads them.
        if (profile == "default" && (key == "context.engine" || key == "memory.provider")) {
            val raw = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
            client.rest("PUT", "/api/dashboard/plugin-providers", buildJsonObject { put(if (key == "context.engine") "context_engine" else "memory_provider", raw) })
            return
        }
        update(profile) { it.with(key, value) }
    }
}
