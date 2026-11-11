package app.hermesandroid.tasks

import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What a scheduled run produced: its title (when it posted a card) and the Markdown body. */
data class RunResult(val title: String?, val body: String)

private val TITLE_FLAG = Regex("""--(?:titulo|title)\s+(?:"((?:[^"\\]|\\.)*)"|'([^']*)')""")
private val HEREDOC = Regex("""<<-?\s*['"]?(\w+)['"]?[^\n]*\n([\s\S]*?)\n\s*\1\s*(?:\n|$)""")

/**
 * The result of a cron run from its stored messages (oldest first). A job that posted its result itself (e.g. a
 * Discord card through a script) shows that card: the heredoc body of the last terminal command, titled by its
 * `--titulo`/`--title` flag; its reply is then `[SILENT]` or just the phone's one-line notice. Else the final reply.
 */
fun runResult(rows: List<JsonObject>): RunResult? {
    val assistant = rows.filter { it.str("role") == "assistant" }
    val reply = assistant.lastOrNull { !it.str("content").isNullOrBlank() }?.str("content")?.trim()?.takeIf { it != "[SILENT]" }
    for (m in assistant.asReversed()) {
        val calls = when (val tc = m["tool_calls"]) {
            is JsonArray -> tc
            is JsonPrimitive -> tc.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            else -> null
        } ?: continue
        for (call in calls.asReversed()) {
            val args = call.obj()?.get("function").obj()?.str("arguments") ?: continue
            val command = runCatching { Json.parseToJsonElement(args).obj()?.str("command") }.getOrNull() ?: continue
            val body = HEREDOC.find(command)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val title = TITLE_FLAG.find(command)?.let { t -> t.groupValues[1].ifEmpty { t.groupValues[2] }.replace("\\\"", "\"") }
            // Next to a reply only a titled card counts: another heredoc (a file written with cat) isn't the result.
            if (reply != null && title == null) continue
            return RunResult(title, discordMarkdown(body))
        }
    }
    return reply?.let { RunResult(null, it) }
}

/** Discord reads a `---` right under a line as a separator; Markdown would make that line a heading. */
private fun discordMarkdown(text: String): String {
    val out = mutableListOf<String>()
    for (line in text.lines()) {
        if (line.trim().matches(Regex("-{3,}")) && out.lastOrNull()?.isNotBlank() == true) out += ""
        out += line
    }
    return out.joinToString("\n")
}
