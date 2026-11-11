package app.hermesandroid.share

import app.hermesandroid.push.E2E
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import kotlinx.serialization.Serializable
import org.bouncycastle.math.ec.rfc7748.X25519

// Phone / location / health sharing: one X25519
// agreement per channel with a host key, HKDF-SHA256 keys per purpose, ChaCha20-Poly1305 seals bound by AAD lines.
// Not the push E2E scheme: separate salts, no compression. The device private key is thrown away after agreeing.

/** One sharing channel's constants (`<channel>_store.py`). */
enum class ShareChannel(val path: String, val salt: String, val kidTag: String, val aadTag: String, val purposes: List<String>) {
    Phone("phone", "conduit-phone-v1", "conduit-phone-kid\n", "conduit-phone/1", listOf("ingest device-to-host", "exchange device-to-host", "exchange host-to-device")),
    Location("location", "conduit-location-v1", "conduit-location-kid\n", "conduit-location/1",
        listOf("ingest device-to-host", "voice device-to-host", "voice host-to-device", "maps host-to-device", "maps device-to-host")),
    Health("health", "conduit-health-v1", "conduit-health-kid\n", "conduit-health/1", listOf("ingest device-to-host", "voice device-to-host", "voice host-to-device"));

    fun info(purpose: String) = "$salt $purpose"
}

/** What the phone keeps after agreeing: the key id and one derived key (b64url) per purpose. */
@Serializable
data class ShareKey(val kid: String, val keys: Map<String, String>) {
    fun key(purpose: String): ByteArray? = keys[purpose]?.let(E2E::unb64u)
}

class Sealed(val nonce: String, val ct: String)

object ShareCrypto {
    private val random = SecureRandom()

    /** A fresh X25519 pair: private, public (32 raw bytes each). */
    fun keypair(): Pair<ByteArray, ByteArray> {
        val priv = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(random, it) }
        val pub = ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(priv, 0, it, 0) }
        return priv to pub
    }

    fun kid(ch: ShareChannel, devicePublic: ByteArray, hostPublic: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(ch.kidTag.toByteArray() + devicePublic + hostPublic).joinToString("") { "%02x".format(it) }.take(32)

    /** The host's `/key` answer turned into keys, or null when its kid doesn't match (or the agreement is degenerate). */
    fun agree(ch: ShareChannel, deviceId: String, devicePrivate: ByteArray, devicePublic: ByteArray, hostPublic: ByteArray, kid: String): ShareKey? {
        if (kid != kid(ch, devicePublic, hostPublic)) return null
        val shared = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(devicePrivate, 0, hostPublic, 0, shared, 0)) return null
        return ShareKey(kid, ch.purposes.associateWith { p -> E2E.b64u(derive(ch, shared, p, deviceId, devicePublic, hostPublic)) })
    }

    fun derive(ch: ShareChannel, shared: ByteArray, purpose: String, deviceId: String, devicePublic: ByteArray, hostPublic: ByteArray): ByteArray =
        E2E.hkdf(shared, ch.info(purpose).toByteArray() + "\n$deviceId\n".toByteArray() + devicePublic + hostPublic, ch.salt.toByteArray())

    /** `<tag>\n<kind>\n<k=v>...`, no trailing newline. */
    fun aad(ch: ShareChannel, kind: String, vararg fields: Pair<String, String>) =
        (listOf(ch.aadTag, kind) + fields.map { (k, v) -> "$k=$v" }).joinToString("\n")

    fun seal(key: ByteArray, aad: String, plaintext: String, nonce: ByteArray = ByteArray(12).also(random::nextBytes)): Sealed =
        Sealed(E2E.b64u(nonce), E2E.b64u(E2E.chacha(Cipher.ENCRYPT_MODE, key, nonce, aad.toByteArray(), plaintext.toByteArray())))

    fun open(key: ByteArray, aad: String, nonce: String, ct: String): String? = runCatching {
        String(E2E.chacha(Cipher.DECRYPT_MODE, key, E2E.unb64u(nonce)!!, aad.toByteArray(), E2E.unb64u(ct)!!))
    }.getOrNull()
}
