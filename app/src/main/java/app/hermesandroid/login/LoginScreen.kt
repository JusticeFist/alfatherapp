package app.hermesandroid.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.chat.AfSheet
import app.hermesandroid.net.bool
import app.hermesandroid.net.str
import app.hermesandroid.ui.components.AfField
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.PrimaryButton
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.components.SettingsRow
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.theme.Backdrop
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import app.hermesandroid.ui.theme.Radii
import app.hermesandroid.ui.theme.medium
import app.hermesandroid.ui.theme.pressable
import app.hermesandroid.ui.theme.settingsCard
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import app.hermesandroid.ui.theme.well
import app.hermesandroid.ui.theme.fadeToCanvas
import app.hermesandroid.ui.shell.PageBar
import app.hermesandroid.ui.shell.CapsuleIcon
import app.hermesandroid.ui.components.Wordmark

data class OAuthProvider(val name: String, val title: String)

/** Sign-in form state; allfather://connect links and QR codes prefill it. */
class LoginState(private val graph: AppGraph) {
    var url by mutableStateOf(graph.store.baseUrl)
    var user by mutableStateOf(graph.store.username)
    var pass by mutableStateOf("")
    var cfId by mutableStateOf("")
    var cfSecret by mutableStateOf("")
    var headers by mutableStateOf(graph.store.customHeaders)
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    val oauth = mutableStateListOf<OAuthProvider>()
    private var loopback: OAuthLoopback? = null

    init { readProxy() }

    fun reset() {
        url = graph.store.baseUrl
        user = graph.store.username
        pass = ""
        headers = graph.store.customHeaders
        readProxy()
        error = null
        oauth.clear()
    }

    private fun readProxy() {
        graph.store.cfAccess.split('\n').let { cfId = it.getOrNull(0).orEmpty(); cfSecret = it.getOrNull(1).orEmpty() }
    }

    /** Accepts allfather://connect?url=&username=&password= (also conduit:// and hermes://, which Hermes' own setup prompt makes) or a bare address. */
    fun prefill(link: String) {
        val uri = Uri.parse(link.trim())
        if (uri.host == "connect" && (uri.scheme == "allfather" || uri.scheme == "conduit" || uri.scheme == "hermes")) {
            uri.getQueryParameter("url")?.let { url = it }
            uri.getQueryParameter("username")?.let { user = it }
            uri.getQueryParameter("password")?.let { pass = it }
        } else if (link.isNotBlank()) {
            url = link.trim()
        }
    }

    private fun address() = url.trim().let { if (it.startsWith("http")) it else "http://$it" }.trimEnd('/')

    private fun saveProxy() {
        graph.store.baseUrl = address()
        graph.store.cfAccess = if (cfId.isNotBlank() && cfSecret.isNotBlank()) "${cfId.trim()}\n${cfSecret.trim()}" else ""
        graph.store.customHeaders = headers
    }

    fun submit() {
        if (user.isBlank() || pass.isEmpty()) {
            error = tr(R.string.loginscreen_need_credentials)
            return
        }
        run {
            saveProxy()
            graph.signIn(address(), user, pass)
            pass = ""
        }
    }

    /** Lists the dashboard's providers; anything without password support is an OAuth button. */
    fun discover() = run {
        saveProxy()
        val list = graph.client.providers(address())
        oauth.clear()
        oauth.addAll(list.filter { it.bool("supports_password") != true }.mapNotNull { p ->
            val name = p.str("name") ?: return@mapNotNull null
            OAuthProvider(name, p.str("display_name") ?: name)
        })
        if (oauth.isEmpty()) error = tr(R.string.loginscreen_password_only)
    }

    fun signInWith(ctx: Context, provider: OAuthProvider) = run {
        saveProxy()
        loopback?.cancel()
        val lb = OAuthLoopback(address(), provider.name).also { loopback = it }
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lb.authorizeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val code = lb.awaitCode()
        graph.signInOAuth(address(), code, lb.verifier)
    }

    private fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        graph.scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: tr(R.string.loginscreen_connect_failed)
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun LoginScreen(graph: AppGraph) {
    val s = graph.login
    val c = C.colors
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var reveal by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(s.cfId.isNotEmpty() || s.headers.isNotEmpty()) }
    var help by remember { mutableStateOf(false) }
    val others = graph.dashboards.filter { it.id != graph.activeDashboard }
    val returning = others.isNotEmpty() && graph.addingFrom == null
    Box(Modifier.fillMaxSize()) {
        Backdrop(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // AllFather walking in, fading into the canvas; the wordmark sits on the fade.
            Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(1.1f)) {
                Image(
                    painterResource(R.drawable.af_login), null,
                    contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize().fadeToCanvas(0.4f),
                )
                if (graph.addingFrom != null) Box(Modifier.statusBarsPadding()) { PageBar(graph::cancelAddDashboard) }
                Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow(stringResource(if (returning) R.string.loginscreen_welcome_back else R.string.loginscreen_connect_agent))
                    Wordmark(stringResource(R.string.app_name))
                }
            }
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (returning) Column(Modifier.fillMaxWidth().settingsCard(Radii.lg)) {
                    others.forEachIndexed { i, d ->
                        SettingsRow(AF.Dns, d.url.substringAfter("://"), detail = d.username.ifBlank { null }, first = i == 0) { graph.switchDashboard(d.id) }
                    }
                }
                AfField(
                    s.url, { s.url = it }, label = stringResource(R.string.loginscreen_server), placeholder = stringResource(R.string.loginscreen_server_hint),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailing = {
                        CapsuleIcon(AF.ContentPaste, stringResource(R.string.loginscreen_paste), tint = c.secondary) { clipboard.getText()?.text?.let(s::prefill) }
                        CapsuleIcon(AF.QrCodeScanner, stringResource(R.string.loginscreen_scan), tint = c.secondary) {
                            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                            GmsBarcodeScanning.getClient(ctx, opts).startScan().addOnSuccessListener { b -> b.rawValue?.let(s::prefill) }
                        }
                    },
                )
                AfField(s.user, { s.user = it }, label = stringResource(R.string.loginscreen_username), placeholder = stringResource(R.string.loginscreen_username_hint))
                AfField(
                    s.pass, { s.pass = it },
                    label = stringResource(R.string.loginscreen_password), placeholder = stringResource(R.string.loginscreen_password_hint),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visual = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        CapsuleIcon(if (reveal) AF.VisibilityOff else AF.Visibility, stringResource(if (reveal) R.string.loginscreen_hide else R.string.loginscreen_show), tint = c.secondary) { reveal = !reveal }
                    },
                )
                s.error?.let { ErrorCard(it) }
                PrimaryButton(stringResource(R.string.loginscreen_connect), enabled = s.url.isNotBlank(), busy = s.busy, onClick = s::submit)
                s.oauth.forEach { p -> SettingsButton(stringResource(R.string.loginscreen_continue_with, p.title), AF.Login, prominent = true) { s.signInWith(ctx, p) } }
                Column(Modifier.fillMaxWidth().animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (s.oauth.isEmpty()) TextLink(stringResource(R.string.loginscreen_other_methods), enabled = s.url.isNotBlank() && !s.busy, onClick = s::discover)
                    TextLink(stringResource(if (advanced) R.string.loginscreen_hide_advanced else R.string.loginscreen_advanced)) { advanced = !advanced }
                    // Advanced: Cloudflare Access service token and proxy headers (CloudflareAccess / CustomHeaders).
                    if (advanced) Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AfField(s.cfId, { s.cfId = it }, label = "CF-Access-Client-Id", placeholder = "xxxx.access")
                        AfField(s.cfSecret, { s.cfSecret = it }, label = "CF-Access-Client-Secret", placeholder = stringResource(R.string.loginscreen_secret_hint), visual = PasswordVisualTransformation())
                        Text(stringResource(R.string.loginscreen_cf_https), style = CType.caption, color = c.secondary)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow(stringResource(R.string.loginscreen_proxy_headers))
                            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp).well(Radii.md).padding(14.dp)) {
                                if (s.headers.isEmpty()) Text("X-Pangolin-Token: …", style = CType.data, color = c.tertiary)
                                BasicTextField(s.headers, { s.headers = it }, textStyle = CType.data.copy(color = c.text), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                            }
                            Text(stringResource(R.string.loginscreen_headers_hint), style = CType.caption, color = c.secondary)
                        }
                    }
                    TextLink(stringResource(R.string.loginscreen_need_help)) { help = true }
                }
                Text(stringResource(R.string.loginscreen_stays_here), style = CType.caption, color = c.tertiary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
    if (help) ConnectHelpSheet { help = false }
}

/** A quiet centered text action (the concept's "Advanced options"). */
@Composable
private fun TextLink(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Text(
        text, style = CType.callout, color = if (enabled) C.colors.text else C.colors.tertiary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().pressable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
    )

@Composable
private fun ErrorCard(text: String) {
    val c = C.colors
    Row(
        Modifier.fillMaxWidth().background(c.red.copy(alpha = 0.07f), Radii.md).border(Dp.Hairline, c.red.copy(alpha = 0.3f), Radii.md).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AF.Warning, null, tint = c.red, modifier = Modifier.size(18.dp))
        Text(text, style = CType.subheadline, color = c.text)
    }
}

/** The "Ask Hermes" setup prompt: Hermes sets up the dashboard and sends a sign-in link. */
private const val SETUP_PROMPT = """I want to use the AllFather Android app on my phone to talk to you. It signs in to the Hermes web dashboard (the one started by `hermes dashboard`, default port 9119). Please set everything up and then tell me what to enter in the app.

1. Check whether the Hermes dashboard is running and which port it uses. If it isn't, start it, and make sure it starts again after a reboot.
2. Make sure the dashboard requires a sign-in. Never turn authentication off. If no username and password are set up yet, create them (HERMES_DASHBOARD_BASIC_AUTH_USERNAME and HERMES_DASHBOARD_BASIC_AUTH_PASSWORD in the Hermes .env file), and set HERMES_DASHBOARD_BASIC_AUTH_SECRET to a long random value so I stay signed in when the dashboard restarts.
3. Set up remote access with Tailscale Serve: check that Tailscale is installed and signed in on this machine, then run `tailscale serve --bg 9119` (with the dashboard's real port) and set dashboard.public_url in config.yaml to the https://<machine>.<tailnet>.ts.net address that Serve gives you. Never open the dashboard port to the internet.
4. If something needs me, stop and tell me exactly what to do, then continue once I've done it.
5. When it's ready, tell me the address, the username and the password. Also make me a sign-in link: allfather://connect?url=ADDRESS&username=USERNAME&password=PASSWORD with each value percent-encoded and the full https:// address, never localhost. If we're talking in a terminal, show the link as a QR code there so the password stays out of chat history."""

@Composable
private fun ConnectHelpSheet(onDismiss: () -> Unit) {
    val c = C.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    AfSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.loginscreen_help_title), style = CType.headline, color = c.text)
            Text(stringResource(R.string.loginscreen_help_easy), style = CType.callout.medium(), color = c.text)
            listOf(
                stringResource(R.string.loginscreen_help_step1),
                stringResource(R.string.loginscreen_help_step2),
                stringResource(R.string.loginscreen_help_step3),
            ).forEachIndexed { i, t -> Text("${i + 1}. $t", style = CType.subheadline, color = c.text) }
            Text(SETUP_PROMPT, style = CType.data, color = c.text, modifier = Modifier.fillMaxWidth().well(Radii.md).padding(12.dp))
            SettingsButton(stringResource(if (copied) R.string.loginscreen_copied else R.string.loginscreen_copy_text), AF.ContentCopy, prominent = true) { clipboard.setText(AnnotatedString(SETUP_PROMPT)); copied = true }
            Text(stringResource(R.string.loginscreen_help_home), style = CType.footnote, color = c.secondary)
        }
    }
}
