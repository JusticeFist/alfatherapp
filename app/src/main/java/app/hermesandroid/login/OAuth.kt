package app.hermesandroid.login

import android.net.Uri
import android.util.Base64
import app.hermesandroid.R
import app.hermesandroid.tr
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native OAuth: PKCE S256, a one-shot loopback listener on 127.0.0.1 for
 * `/callback?code&state`, and the dashboard's `/auth/native/authorize`. The browser does the sign-in.
 */
class OAuthLoopback(private val baseUrl: String, private val provider: String?) {
    // A blocking accept() ignores coroutine cancellation, so the socket itself times out.
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 300_000 }
    private val state = random()
    val verifier = random()

    val authorizeUrl: String = run {
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val redirect = "http://127.0.0.1:${server.localPort}/callback"
        buildString {
            append(baseUrl.trimEnd('/')).append("/auth/native/authorize")
            append("?code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&redirect_uri=").append(URLEncoder.encode(redirect, "UTF-8"))
            append("&state=").append(state)
            if (!provider.isNullOrEmpty()) append("&provider=").append(URLEncoder.encode(provider, "UTF-8"))
        }
    }

    /** Waits (up to 5 min) for the browser to hit the callback; returns the authorization code. */
    suspend fun awaitCode(): String = withContext(Dispatchers.IO) {
        try {
            run {
                server.accept().use { sock ->
                    val line = sock.getInputStream().bufferedReader().readLine().orEmpty() // "GET /callback?... HTTP/1.1"
                    val target = line.split(' ').getOrNull(1).orEmpty()
                    val q = Uri.parse("http://127.0.0.1$target")
                    val ok = line.startsWith("GET ") && q.path == "/callback" && q.getQueryParameter("state") == state && q.getQueryParameter("error") == null
                    val code = q.getQueryParameter("code")
                    val msg = tr(if (ok && code != null) R.string.oauth_done else R.string.oauth_failed)
                    sock.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n<!doctype html><meta name=viewport content='width=device-width'><body style='font-family:sans-serif;padding:32px'>$msg</body>".toByteArray()
                    )
                    if (!ok || code.isNullOrEmpty()) throw IllegalStateException(tr(R.string.oauth_provider_failed))
                    code
                }
            }
        } finally {
            runCatching { server.close() }
        }
    }

    fun cancel() = runCatching { server.close() }

    private companion object {
        fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let(::b64)
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
