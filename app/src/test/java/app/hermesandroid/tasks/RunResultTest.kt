package app.hermesandroid.tasks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunResultTest {
    private fun msg(role: String, content: String = "") = buildJsonObject { put("role", role); put("content", content) }

    /** An assistant turn calling `terminal`, with tool_calls as Hermes stores it (a JSON string) or already parsed. */
    private fun terminal(command: String, asString: Boolean): JsonObject {
        val calls = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "terminal"); put("arguments", buildJsonObject { put("command", command) }.toString()) })
            })
        }
        return buildJsonObject { put("role", "assistant"); put("content", ""); put("tool_calls", if (asString) JsonPrimitive(calls.toString()) else calls) }
    }

    private val card = "python3 ~/scripts/post_card.py --channel general --title \"Daily budget\" <<'EOF'\n" +
        "### Hoy puedes gastar hasta **\$29**.\n---\n- **Ayer:** gastó **\$18**\nEOF"

    @Test
    fun silentRunShowsThePostedCard() {
        for (asString in listOf(true, false)) {
            val r = runResult(listOf(msg("user", "prompt"), terminal(card, asString), msg("tool", "{\"output\":\"156\"}"), msg("assistant", "[SILENT]")))
            assertEquals(RunResult("Daily budget", "### Hoy puedes gastar hasta **\$29**.\n\n---\n- **Ayer:** gastó **\$18**"), r)
        }
    }

    @Test
    fun postedCardBeatsTheNotice() {
        val r = runResult(listOf(msg("user", "p"), terminal(card, true), msg("assistant", "Daily budget: hoy hasta \$29.")))
        assertEquals("Daily budget", r?.title)
        val untitled = "cat > /tmp/x <<'EOF'\nnotas\nEOF"
        assertEquals(RunResult(null, "Disco al 41 %."), runResult(listOf(msg("user", "p"), terminal(untitled, true), msg("assistant", " Disco al 41 %. "))))
    }

    @Test
    fun cronSessionNamesItsJob() {
        assertEquals("0a1b2c3d4e5f", cronJobOf("cron_0a1b2c3d4e5f_20261025_182946"))
        assertNull(cronJobOf("20260101_120000_abcdef"))
        assertNull(cronJobOf(null))
    }

    @Test
    fun nothingToShow() {
        assertNull(runResult(listOf(msg("user", "p"), terminal("df -h", true), msg("assistant", "[SILENT]"))))
        assertNull(runResult(emptyList()))
    }
}
