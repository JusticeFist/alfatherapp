package app.hermesandroid.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One saved Hermes dashboard (SavedDashboardRegistry). */
data class DashboardInfo(val id: String, val url: String, val username: String, val oauth: Boolean)

/**
 * Saved dashboards with their credentials and session state, encrypted with an Android Keystore key.
 * Every per-dashboard property reads and writes the *active* dashboard.
 * `encrypted = false` is for JVM tests only: Robolectric has no AndroidKeyStore.
 */
class Store(context: Context, private val encrypted: Boolean = true) {
    private val prefs = context.getSharedPreferences("hermes", Context.MODE_PRIVATE)
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Single-dashboard installs (≤ 0.4) kept their keys unprefixed: move them under an id.
        if (prefs.contains("url") && !prefs.contains("active")) {
            val id = "d1"
            prefs.edit().apply {
                listOf("url", "user", "pass", "cookies").forEach { k -> prefs.getString(k, null)?.let { putString("$id:$k", it) }; remove(k) }
                putString("ids", id)
                putString("active", id)
            }.apply()
        }
    }

    var activeId: String
        get() = prefs.getString("active", null) ?: ""
        set(v) = prefs.edit().putString("active", v).apply()

    private val ids: List<String> get() = prefs.getString("ids", "").orEmpty().split(',').filter { it.isNotEmpty() }

    fun dashboards(): List<DashboardInfo> = ids.map { id ->
        DashboardInfo(id, read("$id:url").orEmpty(), read("$id:user").orEmpty(), read("$id:oauth") != null)
    }.filter { it.url.isNotEmpty() }

    /** A fresh dashboard slot, made active. */
    fun addDashboard(): String {
        val id = "d" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString("ids", (ids + id).joinToString(",")).putString("active", id).apply()
        return id
    }

    fun removeDashboard(id: String) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("$id:") }.forEach(::remove)
            putString("ids", (ids - id).joinToString(","))
            if (activeId == id) putString("active", (ids - id).firstOrNull() ?: "")
        }.apply()
    }

    var baseUrl: String
        get() = read(k("url")).orEmpty()
        set(v) = write(k("url"), v)
    var username: String
        get() = read(k("user")).orEmpty()
        set(v) = write(k("user"), v)
    var password: String
        get() = read(k("pass")).orEmpty()
        set(v) = write(k("pass"), v)
    var cookies: String
        get() = read(k("cookies")).orEmpty()
        set(v) = write(k("cookies"), v)
    /** Cloudflare Access service token (id \n secret); only ever sent over HTTPS. */
    var cfAccess: String
        get() = read(k("cf")).orEmpty()
        set(v) = write(k("cf"), v)
    /** Extra proxy headers, one `Name: value` per line. */
    var customHeaders: String
        get() = read(k("headers")).orEmpty()
        set(v) = write(k("headers"), v)
    /** Native OAuth tokens as JSON (access_token, refresh_token, expires_at, provider); empty for password sign-in. */
    var oauthTokens: String
        get() = read(k("oauth")).orEmpty()
        set(v) = if (v.isEmpty()) prefs.edit().remove(k("oauth")).apply() else write(k("oauth"), v)

    val hasCredentials get() = baseUrl.isNotEmpty() && (oauthTokens.isNotEmpty() || (username.isNotEmpty() && password.isNotEmpty()))

    /** Signing out of the active dashboard keeps its address and username, drops secrets and session. */
    fun signOutActive() {
        prefs.edit().apply { listOf("pass", "cookies", "oauth").forEach { remove(k(it)) } }.apply()
    }

    private fun k(name: String) = "$activeId:$name"

    // Non-secret preferences, kept in plain text and across sign-outs.
    var profile: String
        get() = settings.getString("profile:$activeId", "default") ?: "default"
        set(v) = settings.edit().putString("profile:$activeId", v).apply()
    var chatScale: Float
        get() = settings.getFloat("chatScale", 1f)
        set(v) = settings.edit().putFloat("chatScale", v).apply()
    var haptics: Boolean
        get() = settings.getBoolean("haptics", true)
        set(v) = settings.edit().putBoolean("haptics", v).apply()
    /** Voice (Settings > Voice): dictation engine "phone" | "hermes", read-aloud voice "hermes" | "phone", read-aloud speed. */
    var dictation: String
        get() = settings.getString("dictation", "phone") ?: "phone"
        set(v) = settings.edit().putString("dictation", v).apply()
    /** The language dictation listens in and Gemini Live speaks (BCP 47, Settings > Voice); empty = the phone's. */
    var speechLanguage: String
        get() = settings.getString("speechLanguage", "") ?: ""
        set(v) = settings.edit().putString("speechLanguage", v).apply()
    /** [speechLanguage], or the phone's language when it follows the phone. */
    val speechTag: String get() = speechLanguage.ifBlank { java.util.Locale.getDefault().toLanguageTag() }
    /** Holding the power button: a Gemini Live call (true) or the listening panel. */
    var assistLive: Boolean
        get() = settings.getBoolean("assistLive", true)
        set(v) = settings.edit().putBoolean("assistLive", v).apply()
    var readAloud: String
        get() = settings.getString("readAloud", "hermes") ?: "hermes"
        set(v) = settings.edit().putString("readAloud", v).apply()
    var readSpeed: Float
        get() = settings.getFloat("readSpeed", 1f)
        set(v) = settings.edit().putFloat("readSpeed", v).apply()
    /** Gemini Live prebuilt voice (Algenib: low and calm, the user's pick for Hermes' TTS too). */
    var liveVoice: String
        get() = settings.getString("liveVoice", "Algenib") ?: "Algenib"
        set(v) = settings.edit().putString("liveVoice", v).apply()
    /** The default profile's display name (its SOUL.md name), per dashboard. */
    var defaultName: String
        get() = settings.getString("defaultName:$activeId", "") ?: ""
        set(v) = settings.edit().putString("defaultName:$activeId", v).apply()
    /** Save each Gemini Live call as a chat in Hermes. */
    var saveCalls: Boolean
        get() = settings.getBoolean("saveCalls", true)
        set(v) = settings.edit().putBoolean("saveCalls", v).apply()
    /** A call from Hermes that rang and wasn't answered yet (JSON), reported missed later. */
    var pendingCall: String
        get() = settings.getString("pendingCall", "") ?: ""
        set(v) = settings.edit().putString("pendingCall", v).apply()
    /** Scheduled jobs whose latest result hasn't been opened yet (tasks/TasksState.unread). */
    var unreadRuns: Set<String>
        get() = settings.getStringSet("unreadRuns", null)?.toSet().orEmpty()
        set(v) = settings.edit().putStringSet("unreadRuns", v).apply()
    /** Turns followed by the ongoing progress notification (push/Progress, JSON). */
    var activities: String
        get() = settings.getString("activities", "[]") ?: "[]"
        set(v) = settings.edit().putString("activities", v).apply()
    var geminiLive: Boolean
        get() = settings.getBoolean("geminiLive", true)
        set(v) = settings.edit().putBoolean("geminiLive", v).apply()
    var biometricLock: Boolean
        get() = settings.getBoolean("biometric", false)
        set(v) = settings.edit().putBoolean("biometric", v).apply()

    /** Push relay registration and E2E keys (PushState), encrypted; shared by every dashboard on this phone. */
    var push: String
        get() = read("push") ?: ""
        set(v) = write("push", v)

    /** Phone / location / health sharing with this dashboard (share/ShareChannel, JSON), encrypted: it holds the channel keys. */
    fun share(channel: String) = read("share:$activeId:$channel") ?: ""
    fun setShare(channel: String, v: String) = write("share:$activeId:$channel", v)

    private fun read(key: String) = prefs.getString(key, null)?.let { if (encrypted) decrypt(it) else it }
    private fun write(key: String, v: String) = prefs.edit().putString(key, if (encrypted) encrypt(v) else v).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    // A value that no longer decrypts (key lost, e.g. after a restore) reads as missing, which means "sign in again".
    private fun decrypt(s: String): String? = runCatching {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()

    private companion object { const val ALIAS = "hermes-vault" }
}

/** Keeps the dashboard's rotating session cookies (`hermes_session_at` / `_rt`, any `__Host-` prefix) exactly as received. */
class PersistentCookieJar(private val store: Store) : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    init { reload() }

    /** Re-reads the active dashboard's cookies (after switching dashboards). */
    @Synchronized
    fun reload() {
        cookies.clear()
        store.baseUrl.toHttpUrlOrNull()?.let { url ->
            store.cookies.lineSequence().mapNotNull { Cookie.parse(url, it) }.forEach { cookies[keyOf(it)] = it }
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies[keyOf(it)] = it }
        val now = System.currentTimeMillis()
        this.cookies.values.removeAll { it.expiresAt < now }
        store.cookies = this.cookies.values.joinToString("\n")
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies.values.filter { it.expiresAt >= now && it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        store.cookies = ""
    }

    private fun keyOf(c: Cookie) = "${c.name}|${c.domain}|${c.path}"
}
