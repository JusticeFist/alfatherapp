package app.hermesandroid.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostConfigTest {
    private fun cfg(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun readsAndWritesDottedKeys() {
        val c = cfg("""{"memory":{"provider":"x","memory_enabled":true},"model":"gpt"}""")
        assertEquals("x", c.at("memory.provider").settingText())
        assertEquals(true, c.at("memory.memory_enabled").settingBool())
        assertNull(c.at("memory.nope"))
        val next = c.with("memory.provider", JsonPrimitive("y")).with("agent.reasoning_effort", JsonPrimitive("high")).with("model", null)
        assertEquals("""{"memory":{"provider":"y","memory_enabled":true},"agent":{"reasoning_effort":"high"}}""", next.toString())
    }

    @Test
    fun numbersAndListsAsText() {
        val c = cfg("""{"a":2.0,"b":0.8,"c":["x","y"],"d":1}""")
        assertEquals("2", c["a"].settingText())
        assertEquals("0.8", c["b"].settingText())
        assertEquals("x\ny", c["c"].settingText())
        assertNull(c["d"].settingBool())
        assertEquals("250", numberJson(250.0).toString())
        assertEquals(listOf("ls", "git status"), listEntries(" ls \n\n git status\n"))
    }

    @Test
    fun reasoningPrefersAgentKey() {
        assertEquals("high", reasoningEffort(cfg("""{"agent":{"reasoning_effort":"high"},"reasoning":"low"}""")))
        assertEquals("low", reasoningEffort(cfg("""{"reasoning":"low"}""")))
        assertEquals("none", reasoningEffort(cfg("""{"agent":{"reasoning_effort":false}}""")))
        assertEquals("medium", reasoningEffort(cfg("{}")))
    }

    @Test
    fun schemaHidesMissingKeys() {
        val schema = parseSchema(cfg("""{"fields":{"approvals.mode":{"type":"select","options":["manual","smart","off"]}}}"""))
        val needs = Field("x.y", 0, 0, Ctl.Toggle(false), requiresSchema = true)
        assertNull(needs.resolved(schema))
        assertEquals(needs, needs.resolved(emptyMap()))
        val opts = Field("approvals.mode", 0, 0, Ctl.Options(emptyList(), "smart")).resolved(schema)!!
        assertEquals(listOf("manual", "smart", "off"), (opts.ctl as Ctl.Options).options)
    }
}

class FrontmatterTest {
    @Test
    fun splitsFieldsAndBody() {
        val (fields, body) = parseFrontmatter("---\nname: pdf\ndescription: \"Read PDFs\"\n  nested: no\ntags:\n- a\n---\n# Use\nText\n")
        assertEquals(listOf("name" to "pdf", "description" to "Read PDFs"), fields)
        assertEquals("# Use\nText", body)
        assertEquals(emptyList<Pair<String, String>>() to "plain", parseFrontmatter("plain"))
        assertEquals("Web Search", prettyCategory("web_search"))
    }
}

class ToolsetLabelTest {
    @Test
    fun stripsLeadingEmoji() {
        assertEquals("Web Search", toolsetLabel("🔍 Web Search", "web"))
        assertEquals("Code Execution", toolsetLabel(null, "code_execution"))
        assertEquals("Browser", toolsetLabel("Browser", "browser"))
    }
}

class McpStateTest {
    private val sv = McpServer("x", "http", "https://x", null, emptyList(), null, true, null)

    @Test
    fun classifiesProbes() {
        assertEquals(McpState.Off, mcpState(sv.copy(enabled = false), null, false))
        assertEquals(McpState.Checking, mcpState(sv, null, true))
        assertEquals(McpState.Unknown, mcpState(sv, null, false))
        assertEquals(McpState.On(2), mcpState(sv, McpProbe(true, null, listOf("a" to "", "b" to "")), false))
        assertEquals(McpState.NeedsSignIn, mcpState(sv, McpProbe(false, "HTTP 401 Unauthorized", emptyList()), false))
        assertEquals(McpState.Failed("timeout"), mcpState(sv, McpProbe(false, "timeout", emptyList()), false))
    }
}

class PluginTest {
    @Test
    fun comparesVersions() {
        assertEquals(true, versionOlder("0.47.9", "0.48.0"))
        assertEquals(false, versionOlder("0.54.1", "0.48.0"))
        assertEquals(false, versionOlder("0.48", "0.48.0"))
        assertEquals(true, versionOlder(null, "0.4.0"))
        assertEquals(false, versionOlder("1.0.0-beta", "0.9"))
    }

    @Test
    fun listsUserPluginsOnly() {
        val r = Json.parseToJsonElement("""{"plugins":[
            {"name":"conduit_push","version":"0.54.1","source":"git","runtime_status":"enabled","path":"/h/.hermes/plugins/conduit_push"},
            {"name":"platforms/discord","source":"bundled"},{"name":"disk-cleanup","source":"bundled"},{"name":"memory-x","source":"bundled"}]}""") as JsonObject
        val list = parsePlugins(r)
        assertEquals(listOf("conduit_push", "disk-cleanup"), list.map { it.name })
        assertEquals(true, list[0].enabled)
        assertEquals("conduit_push", list[0].folder)
    }
}

class ArtifactsTest {
    @Test
    fun parsesRowsAndDropsBadOnes() {
        val r = Json.parseToJsonElement("""{"ok":true,"next_before":120,"items":[
            {"kind":"image","source":"delivered","path":"/h/a.png","session_id":"s1","message_id":5,"timestamp":1700000000.5,"size":2048},
            {"kind":"link","source":"link","url":"https://x.dev/p","session_id":"s1","message_id":6,"timestamp":1700000001},
            {"kind":"link","source":"link","url":"javascript:alert(1)","session_id":"s1","message_id":7,"timestamp":1},
            {"kind":"page","source":"written","session_id":"s1","message_id":8,"timestamp":1},
            {"kind":"weird","source":"written","path":"/x","session_id":"s1","message_id":9,"timestamp":1}]}""") as JsonObject
        val (items, next) = app.hermesandroid.sessions.parseArtifacts(r)!!
        assertEquals(listOf("a.png", "x.dev"), items.map { it.name })
        assertEquals(120, next)
        assertEquals(1700000000500L, items[0].at)
        assertNull(app.hermesandroid.sessions.parseArtifacts(Json.parseToJsonElement("""{"ok":false}""") as JsonObject))
    }
}

class ParentPathTest {
    @Test
    fun walksUp() {
        assertEquals("/home/a", parentPath("/home/a/b"))
        assertEquals("/", parentPath("/home"))
        assertNull(parentPath("/"))
        assertEquals("C:\\", parentPath("C:\\Users"))
    }
}
