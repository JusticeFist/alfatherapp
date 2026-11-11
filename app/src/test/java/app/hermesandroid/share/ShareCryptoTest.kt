package app.hermesandroid.share

import app.hermesandroid.push.E2E
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Against the plugin's own vectors (tests/<channel>_vector.json on the host, copied to resources). */
class ShareCryptoTest {
    private fun vector(name: String) = Json.parseToJsonElement(javaClass.classLoader!!.getResource("${name}_vector.json")!!.readText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun b(s: String) = E2E.unb64u(s)!!

    private fun agreed(ch: ShareChannel, v: JsonObject): ShareKey {
        val key = ShareCrypto.agree(ch, v.s("device_id"), b(v.s("device_private")), b(v.s("device_public")), b(v.s("host_public")), v.s("kid"))!!
        assertEquals(v.s("kid"), key.kid)
        return key
    }

    /** seal → the plugin's ct for the same nonce, and back. */
    private fun roundTrip(key: ByteArray, aad: String, plaintext: String, nonce: String, ct: String) {
        assertEquals(ct, ShareCrypto.seal(key, aad, plaintext, b(nonce)).ct)
        assertEquals(plaintext, ShareCrypto.open(key, aad, nonce, ct))
        assertNull(ShareCrypto.open(key, aad + "x", nonce, ct))
    }

    @Test
    fun phone() {
        val v = vector("phone")
        val key = agreed(ShareChannel.Phone, v)
        val i = v.o("ingest")
        assertEquals(i.s("key"), key.key("ingest device-to-host")!!.joinToString("") { "%02x".format(it) })
        val aad = ShareCrypto.aad(ShareChannel.Phone, "ingest", "kid" to key.kid, "dev" to v.s("device_id"), "batch" to i.s("batch_id"))
        assertEquals(i.s("aad"), aad)
        roundTrip(hex(i.s("key")), aad, i.s("plaintext"), i.s("nonce"), i.s("ct"))
        for ((name, purpose) in listOf("exchange_request" to "exchange device-to-host", "exchange_answer" to "exchange host-to-device")) {
            val x = v.o(name)
            assertEquals(x.s("key"), key.key(purpose)!!.joinToString("") { "%02x".format(it) })
            roundTrip(hex(x.s("key")), x.s("aad"), x.s("plaintext"), x.s("nonce"), x.s("ct"))
        }
        // A kid the host didn't derive from these keys is refused.
        assertNull(ShareCrypto.agree(ShareChannel.Phone, v.s("device_id"), b(v.s("device_private")), b(v.s("device_public")), b(v.s("host_public")), "0".repeat(32)))
    }

    @Test
    fun locationAndHealth() {
        for ((ch, name) in listOf(ShareChannel.Location to "location", ShareChannel.Health to "health")) {
            val v = vector(name)
            val key = agreed(ch, v)
            assertEquals(v.s("key"), key.key("ingest device-to-host")!!.joinToString("") { "%02x".format(it) })
            assertEquals(v.s("aad"), ShareCrypto.aad(ch, "ingest", "kid" to key.kid, "dev" to v.s("device_id"), "batch" to v.s("batch_id")))
            roundTrip(hex(v.s("key")), v.s("aad"), v.s("plaintext"), v.s("nonce"), v.s("ct"))
            val voice = v.o("voice")
            assertEquals(voice.s("request_key"), key.key("voice device-to-host")!!.joinToString("") { "%02x".format(it) })
            assertEquals(voice.s("answer_key"), key.key("voice host-to-device")!!.joinToString("") { "%02x".format(it) })
        }
        val maps = vector("location").o("maps")
        val key = agreed(ShareChannel.Location, vector("location"))
        assertEquals(maps.s("request_key"), key.key("maps host-to-device")!!.joinToString("") { "%02x".format(it) })
        assertEquals(maps.s("answer_key"), key.key("maps device-to-host")!!.joinToString("") { "%02x".format(it) })
    }
}
