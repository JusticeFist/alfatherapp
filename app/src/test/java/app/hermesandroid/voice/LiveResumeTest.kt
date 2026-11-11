package app.hermesandroid.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveResumeTest {
    @Test fun resumptionUpdates() {
        assertEquals("h1", LiveProtocol.decode("""{"sessionResumptionUpdate":{"newHandle":"h1","resumable":true}}""").handle)
        assertNull(LiveProtocol.decode("""{"sessionResumptionUpdate":{"newHandle":"h2","resumable":false}}""").handle)
        assertTrue(LiveProtocol.decode("""{"goAway":{"timeLeft":"10s"}}""").goAway)
    }

    @Test fun setupCarriesTheHandle() {
        val base = buildJsonObject { putJsonObject("setup") { put("model", "models/x") } }
        val resumed = LiveProtocol.resuming(base, "h1")["setup"]!!.jsonObject
        assertEquals("models/x", resumed["model"]!!.jsonPrimitive.content)
        assertEquals("h1", resumed["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), LiveProtocol.resuming(base, null)["setup"]!!.jsonObject["sessionResumption"])
    }

    @Test fun whichClosesRetry() {
        assertTrue(LiveProtocol.resumptionLost("Session not found or expired"))
        assertTrue(LiveProtocol.permanent(1008, "policy"))
        assertTrue(LiveProtocol.permanent(1011, "You exceeded your current quota"))
        assertFalse(LiveProtocol.permanent(1011, "Internal error"))
        assertFalse(LiveProtocol.permanent(1000, ""))
    }
}
