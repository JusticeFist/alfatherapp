package app.hermesandroid.push

import app.hermesandroid.R
import app.hermesandroid.tr
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// End-to-end encrypted notifications (plugin e2e.py): the phone makes a 32-byte
// secret per paired gateway and hands it to the plugin; HKDF-SHA256 derives a push key (gateway → phone) and
// an answer key (phone → gateway); content is ChaCha20-Poly1305 sealed, optionally raw-deflated, and bound by
// AAD to the key, installation, gateway, event and type. The relay only ever sees the envelope.

/** One provisioned key: which installation and gateway it belongs to. */
@Serializable
data class E2ERecord(
    val kid: String,
    val secret: String,
    val installationId: String,
    val gatewayId: String,
    val dashboardId: String? = null,
    val profile: String? = null,
    val createdAt: Long = 0,
)

object E2E {
    const val ANSWER_PREFIX = "e2e1."
    const val MAX_AGE_S = 24 * 60 * 60L
    const val MAX_SKEW_S = 60 * 60L
    private val SALT = "conduit-e2e-v1".toByteArray()
    private val PUSH_INFO = "conduit-e2e-v1 push gateway-to-device".toByteArray()
    private val ANSWER_INFO = "conduit-e2e-v1 answer device-to-gateway".toByteArray()
    private const val AAD_TAG = "conduit-e2e/1"
    private val KID = Regex("^[0-9a-f]{32}$")
    private val TOK = Regex("^[0-9a-f]{16}$")
    private val random = SecureRandom()

    /** RFC 5869 for one 32-byte block. */
    fun hkdf(secret: ByteArray, info: ByteArray, salt: ByteArray = SALT): ByteArray {
        fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
        val prk = hmac(salt, secret)
        return hmac(prk, info + byteArrayOf(1))
    }

    fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64u(s: String): ByteArray? = if (!Regex("^[A-Za-z0-9_-]*$").matches(s) || s.length % 4 == 1) null else runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()

    fun newRecord(installationId: String, gatewayId: String, dashboardId: String?, profile: String?): E2ERecord {
        fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
        return E2ERecord(bytes(16).joinToString("") { "%02x".format(it) }, b64u(bytes(32)), installationId, gatewayId, dashboardId, profile, System.currentTimeMillis())
    }

    internal fun chacha(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").apply { init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce)); updateAAD(aad) }.doFinal(data)

    fun pushAad(kid: String, inst: String, gw: String, msg: String, type: String, iat: Long, tok: String, z: Int, req: String) =
        listOf(AAD_TAG, "push", "kid=$kid", "inst=$inst", "gw=$gw", "msg=$msg", "type=$type", "iat=$iat", "tok=$tok", "z=$z", "req=$req").joinToString("\n").toByteArray()

    fun answerAad(kid: String, inst: String, gw: String, req: String, qid: String) =
        listOf(AAD_TAG, "answer", "kid=$kid", "inst=$inst", "gw=$gw", "req=$req", "qid=$qid").joinToString("\n").toByteArray()

    /** Raw DEFLATE (no zlib header), capped at 64 KB like the plugin. */
    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inf = Inflater(true).apply { setInput(data) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024) return null
        }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    data class Opened(val record: E2ERecord, val type: String, val content: JsonObject, val requestId: String, val replayKey: String)

    /**
     * Opens a `conduit_e2e` envelope, or null when it isn't one of ours, is stale, or doesn't verify
     * (the caller then shows the generic copy for [type]).
     */
    fun open(envelope: JsonObject, type: String, records: List<E2ERecord>, nowS: Long = System.currentTimeMillis() / 1000): Opened? {
        fun s(k: String) = (envelope[k] as? JsonPrimitive)?.contentOrNull
        if ((envelope["v"] as? JsonPrimitive)?.intOrNull != 1 || type.isBlank()) return null
        val kid = s("kid")?.takeIf { KID.matches(it) } ?: return null
        val msg = s("msg")?.takeIf { it.isNotEmpty() } ?: return null
        val iat = (envelope["iat"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val tok = s("tok")?.takeIf { TOK.matches(it) } ?: return null
        val z = (envelope["z"] as? JsonPrimitive)?.intOrNull?.takeIf { it == 0 || it == 1 } ?: return null
        val req = s("req") ?: return null
        val nonce = s("n")?.let(::unb64u)?.takeIf { it.size == 12 } ?: return null
        val ct = s("ct")?.let(::unb64u)?.takeIf { it.size >= 16 } ?: return null
        if (iat < nowS - MAX_AGE_S || iat > nowS + MAX_SKEW_S) return null
        val record = records.firstOrNull { it.kid == kid } ?: return null
        val secret = unb64u(record.secret) ?: return null
        val plain = runCatching {
            chacha(Cipher.DECRYPT_MODE, hkdf(secret, PUSH_INFO), nonce, pushAad(kid, record.installationId, record.gatewayId, msg, type, iat, tok, z, req), ct)
        }.getOrNull() ?: return null
        val raw = if (z == 1) inflate(plain) ?: return null else plain
        val content = runCatching { Json.parseToJsonElement(raw.decodeToString()) as JsonObject }.getOrNull() ?: return null
        // A decision must be the one the envelope's AAD names.
        val decisionReq = ((content["decision"] as? JsonObject)?.get("request_id") as? JsonPrimitive)?.contentOrNull
        if (decisionReq != null && decisionReq != req) return null
        return Opened(record, type, content, req, "$kid:$msg")
    }

    /** A clarify answer sealed for the gateway: `e2e1.<kid>.<nonce>.<ct+tag>`. */
    fun sealAnswer(answer: String, record: E2ERecord, requestId: String, questionId: String?): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = hkdf(unb64u(record.secret)!!, ANSWER_INFO)
        val ct = chacha(Cipher.ENCRYPT_MODE, key, nonce, answerAad(record.kid, record.installationId, record.gatewayId, requestId, questionId.orEmpty()), answer.toByteArray())
        return "$ANSWER_PREFIX${record.kid}.${b64u(nonce)}.${b64u(ct)}"
    }

    /** The test's inverse of [sealAnswer] (the plugin's open_answer). */
    fun openAnswer(sealed: String, record: E2ERecord, requestId: String, questionId: String?): String? {
        if (!sealed.startsWith(ANSWER_PREFIX)) return null
        val parts = sealed.removePrefix(ANSWER_PREFIX).split(".")
        if (parts.size != 3 || parts[0] != record.kid) return null
        val key = hkdf(unb64u(record.secret) ?: return null, ANSWER_INFO)
        return runCatching { chacha(Cipher.DECRYPT_MODE, key, unb64u(parts[1])!!, answerAad(record.kid, record.installationId, record.gatewayId, requestId, questionId.orEmpty()), unb64u(parts[2])!!).decodeToString() }.getOrNull()
    }

    /** Copy for a push it can't open, with the AI's own name. */
    fun genericCopy(type: String?, n: String = app.hermesandroid.ui.shell.defaultProfileTitle): Pair<String, String> = when (type) {
        "approval.needed" -> tr(R.string.e2e_approval_title) to tr(R.string.e2e_approval_body, n)
        "input.needed" -> tr(R.string.e2e_input_title, n) to tr(R.string.e2e_input_body, n)
        "turn.failed" -> tr(R.string.e2e_failed_title) to tr(R.string.e2e_failed_body, n)
        "background_task.finished" -> tr(R.string.e2e_background_title) to tr(R.string.e2e_background_body)
        "call.requested" -> tr(R.string.e2e_call_title, n) to tr(R.string.e2e_call_body, n)
        "phone.changes" -> tr(R.string.e2e_phone_title, n) to tr(R.string.e2e_phone_body)
        else -> tr(R.string.e2e_ready_title) to tr(R.string.e2e_ready_body, n)
    }
}
