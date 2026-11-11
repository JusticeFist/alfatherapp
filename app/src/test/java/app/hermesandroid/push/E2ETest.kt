package app.hermesandroid.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Against envelopes sealed by the plugin's own e2e.py (resources/e2e_vector.json, test secret). */
class E2ETest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("e2e_vector.json")!!.readText()).jsonObject
    private fun p(k: String) = v[k]!!.jsonPrimitive.content
    private val record = E2ERecord(p("kid"), p("secret"), p("installation_id"), p("gateway_id"))
    private val now = 1760000000L

    private fun envelope(name: String) = v[name]!!.jsonObject["e2e"]!!.jsonObject

    @Test
    fun opensPluginEnvelopes() {
        val small = E2E.open(envelope("small"), "response.ready", listOf(record), now)!!
        assertEquals("Respuesta lista", small.content["title"]!!.jsonPrimitive.content)
        assertEquals("Hola desde el vector de prueba áé", small.content["body"]!!.jsonPrimitive.content)
        assertEquals("s-42", small.content["session_id"]!!.jsonPrimitive.content)
        val big = E2E.open(envelope("z"), "response.ready", listOf(record), now)!!
        assertEquals(1500, big.content["body"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun refusesTamperingAndStaleness() {
        val env = envelope("small")
        assertNull(E2E.open(env, "approval.needed", listOf(record), now)) // type is in the AAD
        assertNull(E2E.open(env, "response.ready", listOf(record.copy(gatewayId = "gw-2")), now))
        assertNull(E2E.open(env, "response.ready", emptyList(), now))
        assertNull(E2E.open(env, "response.ready", listOf(record), now + E2E.MAX_AGE_S + 1))
        assertNull(E2E.open(JsonObject(env + ("msg" to JsonPrimitive("evt:x"))), "response.ready", listOf(record), now))
    }

    @Test
    fun answersRoundTrip() {
        val sealed = E2E.sealAnswer("sí", record, "clarify:r1", "q1")
        assertEquals("sí", E2E.openAnswer(sealed, record, "clarify:r1", "q1"))
        assertNull(E2E.openAnswer(sealed, record, "clarify:r1", "q2"))
        assertNotNull(E2E.newRecord("i", "g", null, null).kid.takeIf { it.length == 32 })
    }
}

// The generic copy for a push it can't open comes from string resources (tr).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PushContentTest {
    private val v = Json.parseToJsonElement(javaClass.classLoader!!.getResource("e2e_vector.json")!!.readText()).jsonObject
    private fun p(k: String) = v[k]!!.jsonPrimitive.content
    private val record = E2ERecord(p("kid"), p("secret"), p("installation_id"), p("gateway_id"), dashboardId = "dash-1")

    @Test
    fun encryptedPushOpensOnThePhone() {
        val data = mapOf(
            "type" to "response.ready", "push_type" to "alert",
            "conduit" to """{"type":"response.ready","e2e":1,"gateway_id":"gw-1"}""",
            "conduit_e2e" to v["small"]!!.jsonObject["e2e"].toString(),
            "aps" to """{"alert":{"title":"Response ready","body":"Hermes has finished responding."}}""",
        )
        val c = pushContent(data, listOf(record), 1760000000)!!
        assertEquals("Respuesta lista", c.title)
        assertEquals("s-42", c.sessionId)
        assertEquals("dash-1", c.dashboardId)
        // Without the key: the generic copy, no session.
        val g = pushContent(data, emptyList(), 1760000000)!!
        assertEquals(E2E.genericCopy("response.ready").first, g.title)
        assertNull(g.sessionId)
    }

    @Test
    fun plaintextAndBackgroundPushes() {
        val plain = pushContent(mapOf("type" to "turn.failed", "conduit" to """{"type":"turn.failed","session_id":"s1","profile":"bot"}""",
            "aps" to """{"alert":{"title":"Turno","body":"Falló"}}"""), emptyList())!!
        assertEquals("Falló", plain.body)
        assertEquals("bot", plain.profile)
        assertEquals(false, plain.pulse)
        val pulse = pushContent(mapOf("type" to "response.ready", "conduit" to """{"type":"response.ready","session_id":"p1","title_label":"pulse.noticed"}""",
            "aps" to """{"alert":{"title":"Pulse noticed 3 things","body":"…"}}"""), emptyList())!!
        assertEquals(true, pulse.pulse)
        assertNull(pushContent(mapOf("type" to "token_challenge", "push_type" to "background"), emptyList()))
    }

    @Test
    fun callArrivesAsVoipPush() {
        // The FCM relay sends a call the way it would ring an iPhone: push_type voip, no alert, call in body.conduit.
        val call = pushContent(mapOf("type" to "call.requested", "push_type" to "voip", "aps" to "{}",
            "conduit" to """{"type":"call.requested","sent_at":1760000000}""",
            "body" to """{"conduit":{"type":"call.requested","sent_at":1760000000,"call":{"id":"watch_12345678","kind":"done","session_ids":["s1"]}}}"""), emptyList())!!
        assertEquals("call.requested", call.type)
        assertEquals("watch_12345678", call.call?.get("id")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        // A "stop ringing" for another device carries no type: nothing to show.
        assertNull(pushContent(mapOf("type" to "unknown", "push_type" to "voip", "conduit" to """{"ring":{"id":"r1","settled":"answered"}}"""), emptyList()))
    }
}

class DictationTest {
    @Test
    fun wavHeaderAndJoin() {
        val w = app.hermesandroid.voice.wav(ByteArray(32000))
        assertEquals(44 + 32000, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4))
        assertEquals(16000, java.nio.ByteBuffer.wrap(w, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("hola mundo", app.hermesandroid.voice.joinDictation("hola", " mundo "))
        assertEquals("mundo", app.hermesandroid.voice.joinDictation("", "mundo"))
        assertEquals("hola\nmundo", app.hermesandroid.voice.joinDictation("hola\n", "mundo"))
    }
}

class SpokenTextTest {
    @Test
    fun stripsMarkdownForSpeech() {
        val s = app.hermesandroid.voice.spokenText("## Hola\n**Listo**: mira [la web](https://x.dev) y `ls`.\n```\ncode\n```\n*se ríe un poco fuerte* vale *sí*\nhttps://a.b/c")
        assertEquals("Hola\nListo: mira la web y ls.\nvale sí", s)
    }
}

class SoulNameTest {
    @Test
    fun readsTheAgentsName() {
        assertEquals("Allfather", app.hermesandroid.ui.shell.soulName("# Allfather\n\nEres Allfather, el asistente…"))
        assertEquals("Ada", app.hermesandroid.ui.shell.soulName("Eres Ada, la asistente de la casa."))
        assertNull(app.hermesandroid.ui.shell.soulName("Sé amable y breve."))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class IncomingCallTest {
    @Test
    fun parsesCallRequests() {
        val o = kotlinx.serialization.json.Json.parseToJsonElement("""{"id":"call_12345678","kind":"done","title":"Resumen  de\n\"gastos\"","reason":"x","session_ids":["s1"," ","s2","s3","s4","s5"]}""") as JsonObject
        val c = app.hermesandroid.voice.parseCall(o, "default", "dash", 5)!!
        assertEquals("Resumen de 'gastos'", c.title)
        assertEquals(listOf("s1", "s2", "s3", "s4"), c.sessionIds)
        assertEquals("x", app.hermesandroid.voice.callHeadline(c))
        assertEquals("Done: Resumen de 'gastos'", app.hermesandroid.voice.callHeadline(c.copy(reason = null)))
        assertNull(app.hermesandroid.voice.parseCall(Json.parseToJsonElement("""{"id":"bad id"}""") as JsonObject, null, null))
        assertEquals(c, app.hermesandroid.voice.HermesCalls.decode(app.hermesandroid.voice.HermesCalls.encode(c)))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProgressTest {
    @Test
    fun readsRelayStates() {
        val upd = Json.parseToJsonElement("""{"timestamp":1,"event":"update","content-state":{"phase":"tool","tools":3,"tool":"terminal","context":41}}""") as JsonObject
        val s = parseProgress(upd)!!
        assertEquals("Using the terminal · 3 tools · context 41 %", progressText(s))
        assertEquals(false, s.ended)
        val end = parseProgress(Json.parseToJsonElement("""{"event":"end","content-state":{"phase":"done","tools":1}}""") as JsonObject)!!
        assertEquals(true, end.ended)
        assertEquals("Waiting for your approval", progressText(ProgressState("approval", 0, null, null, false)))
        assertNull(parseProgress(null))
    }
}
