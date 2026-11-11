package app.hermesandroid.share

import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.HttpError
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.push.E2E
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a channel keeps per dashboard: its device id (kept even when sharing is off, so the host copy can be wiped),
 *  the agreed keys, the host store generation it last synced with, and the channel's own sync memory. */
@Serializable
data class ShareState(
    val deviceId: String = "",
    val key: ShareKey? = null,
    val generation: String? = null,
    /** Per-item hashes already on the host (phone: event/calendar ids), so a pass sends only what changed. */
    val sent: Map<String, String> = emptyMap(),
    val lastFullAt: Long = 0,
    val lastSendAt: Long = 0,
    val statusHash: String? = null,
    val statusAt: Long = 0,
)

/** The host forgot or replaced this device's key: agree a new one and send everything again. */
class KeyLost : Exception() {
    override val message: String get() = tr(R.string.sharelink_key_lost)
}

/** The `/api/plugins/conduit_push/<channel>/...` routes of one channel, always the default profile (where Allfather lives). */
class ShareLink(private val graph: AppGraph, val ch: ShareChannel) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var state: ShareState = runCatching { json.decodeFromString<ShareState>(graph.store.share(ch.path)) }.getOrDefault(ShareState())
        private set

    fun update(f: (ShareState) -> ShareState) {
        state = f(state)
        graph.store.setShare(ch.path, json.encodeToString(ShareState.serializer(), state))
    }

    /** Health and phone use a dash-less UUID, location 16 hex bytes; any 8–64 url-safe id is accepted. */
    val deviceId: String
        get() = state.deviceId.ifEmpty { UUID.randomUUID().toString().replace("-", "").also { id -> update { it.copy(deviceId = id) } } }

    private fun path(p: String) = graph.client.withProfile("/api/plugins/conduit_push/${ch.path}/$p", "default")

    suspend fun status(): JsonObject? = graph.client.rest("GET", path("status?device_id=$deviceId")).obj()

    /** Makes sure the stored key is the host's: re-keys when the host has another one (or none), and forgets what
     *  was sent when the host store is new. True when everything must be sent again. */
    suspend fun ready(status: JsonObject?): Boolean {
        val hostKid = status?.get("device").obj()?.str("kid")
        val generation = status?.str("generation")
        var resend = false
        if (state.key == null || hostKid != state.key?.kid) { rekey(); resend = true }
        if (generation != null && generation != state.generation) { update { it.copy(generation = generation, sent = emptyMap(), lastFullAt = 0) }; resend = true }
        return resend
    }

    suspend fun rekey() {
        val (priv, pub) = ShareCrypto.keypair()
        val r = graph.client.rest("POST", path("key"), buildJsonObject {
            put("device_id", deviceId); put("device_name", deviceName()); put("public_key", E2E.b64u(pub))
        }).obj() ?: throw IllegalStateException(tr(R.string.sharelink_no_key))
        val hostPub = r.str("host_public_key")?.let(E2E::unb64u) ?: throw IllegalStateException(tr(R.string.sharelink_bad_host_key))
        val key = ShareCrypto.agree(ch, deviceId, priv, pub, hostPub, r.str("kid").orEmpty()) ?: throw IllegalStateException(tr(R.string.sharelink_key_mismatch))
        priv.fill(0)
        update { it.copy(key = key, generation = r.str("generation") ?: it.generation, sent = emptyMap(), lastFullAt = 0) }
    }

    /** Seals and posts one batch (its plaintext carries the same `batch_id`). */
    suspend fun ingest(plain: JsonObject): JsonObject? {
        val key = state.key ?: throw KeyLost()
        val batch = plain.str("batch_id")!!
        val sealed = ShareCrypto.seal(key.key("ingest device-to-host")!!, ShareCrypto.aad(ch, "ingest", "kid" to key.kid, "dev" to deviceId, "batch" to batch), plain.toString())
        return try {
            graph.client.rest("POST", path("ingest"), buildJsonObject {
                put("v", 1); put("device_id", deviceId); put("kid", key.kid); put("batch_id", batch); put("nonce", sealed.nonce); put("ct", sealed.ct)
            }, timeoutSec = 60).obj()
        } catch (e: HttpError) {
            if (e.word == "unknown_key" || e.word == "bad_seal") { update { it.copy(key = null) }; throw KeyLost() }
            throw e
        }
    }

    /** Deletes this device's data and key on the host; the phone keeps only its device id. */
    suspend fun wipe() {
        if (state.deviceId.isNotEmpty()) graph.client.rest("POST", path("wipe"), buildJsonObject { put("device_id", state.deviceId) })
        update { ShareState(deviceId = it.deviceId) }
    }

    companion object {
        fun batchId() = UUID.randomUUID().toString().replace("-", "")
        fun deviceName(): String = (android.os.Build.MODEL ?: "Android").take(60)
    }
}
