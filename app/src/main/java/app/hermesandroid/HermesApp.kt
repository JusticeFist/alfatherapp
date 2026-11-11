package app.hermesandroid

import android.app.Application
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.chat.ChatState
import app.hermesandroid.login.LoginState
import app.hermesandroid.net.EMPTY_OBJECT
import app.hermesandroid.net.HermesClient
import app.hermesandroid.net.HermesEvent
import app.hermesandroid.net.ServerRequest
import app.hermesandroid.net.Store
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.sessions.ChatsState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class HermesApp : Application() {
    val graph by lazy { AppGraph(applicationContext) }

    init { instance = this }

    companion object { lateinit var instance: HermesApp; private set }

    override fun onCreate() {
        super.onCreate()
        app.hermesandroid.push.ensureFirebase(this)
    }
}

val Context.graph get() = (applicationContext as HermesApp).graph

/** UI text outside Compose (state, notifications, services), in the phone's language. Compose uses stringResource. */
fun tr(@StringRes id: Int, vararg args: Any): String = HermesApp.instance.getString(id, *args)

fun trPlural(@PluralsRes id: Int, n: Int, vararg args: Any): String = HermesApp.instance.resources.getQuantityString(id, n, *args)

/** Screens pushed over the home chat from the sidebar. */
enum class Page { Tasks, Bots, Settings, Projects, Artifacts, Pulse }

/**
 * Process-wide app state, split by feature. Screens read Compose state from here and
 * from the feature states it owns; it outlives activities, so nothing is lost on rotation.
 */
class AppGraph(private val context: Context) {
    val scope = MainScope()
    val store = Store(context)
    val client = HermesClient(store, scope)

    var signedIn by mutableStateOf(store.hasCredentials)
        private set
    val profiles = mutableStateListOf("default")
    var profile by mutableStateOf(client.profile)
        private set
    /** The page over the home chat, null when the chat itself shows. */
    var page by mutableStateOf<Page?>(null)
    /** Bumped after every (re)connect so features can re-attach (resume the open chat, reload lists). */
    var connectedEpoch by mutableIntStateOf(0)
        private set

    /** Approval / clarify / secret prompts from any session, until answered or withdrawn. */
    val requests = mutableStateListOf<ServerRequest>()

    /** Saved dashboards (SavedDashboardRegistry) and the active one. */
    val dashboards = mutableStateListOf<app.hermesandroid.net.DashboardInfo>().apply { addAll(store.dashboards()) }
    var activeDashboard by mutableStateOf(store.activeId)
        private set
    /** Login screen shows "add a dashboard" (back to the previous one on cancel). */
    var addingFrom by mutableStateOf<String?>(null)
        private set
    /** Profile avatars (profiles.get_asset) as PNG bytes, per profile name. */
    val avatars = androidx.compose.runtime.mutableStateMapOf<String, ByteArray>()
    var locked by mutableStateOf(store.biometricLock)

    private val listeners = CopyOnWriteArrayList<(HermesEvent) -> Unit>()

    val login by lazy { LoginState(this) }
    val chats by lazy { ChatsState(this, context) }
    val chat by lazy { ChatState(this, context) }
    val tasks by lazy { app.hermesandroid.tasks.TasksState(this) }
    val bots by lazy { app.hermesandroid.bots.BotsState(this, context) }
    val hostConfig by lazy { app.hermesandroid.settings.HostConfig(this) }
    val capabilities by lazy { app.hermesandroid.settings.CapabilitiesState(this) }
    val hostPlugin by lazy { app.hermesandroid.settings.HostPlugin(this) }
    /** Pulse's cards and its chat's id: the sidebar row, the page, and the chat list (which hides that chat). */
    val pulse by lazy { app.hermesandroid.settings.PulseFeed(this) }
    val push by lazy { app.hermesandroid.push.PushState(this, context) }
    val dictation by lazy { app.hermesandroid.voice.Dictation(this, context) }
    val readAloud by lazy { app.hermesandroid.voice.ReadAloud(this, context) }
    // Geofences don't survive a reboot or an update: creating it re-registers them.
    val location by lazy { app.hermesandroid.location.LocationSync(this, context).also { it.watch() } }
    // Sleep-segment registration doesn't survive a reboot or an update: creating it renews it.
    val watch by lazy { app.hermesandroid.watch.WatchBridge(this, context) }
    /** The phone assistant's own chat (power-button panel). */
    // Sonnet 5.5 thinking high (the user's pick for the assistant and the watch).
    val assist by lazy { app.hermesandroid.chat.QuickChat(this, tr(R.string.hermesapp_assistant_chat), reasoning = "high") }
    val watchLive by lazy { app.hermesandroid.watch.WatchLive(this, context) }
    val health by lazy { app.hermesandroid.health.HealthSync(this, context).also { h -> if (h.prefs.enabled && h.prefs.phoneSleep) app.hermesandroid.health.PhoneSleep.start(context) } }
    val alerts by lazy { app.hermesandroid.alerts.Alerts(this, context) }
    val phone by lazy { app.hermesandroid.phone.PhoneSync(this, context).also { it.watchCalendar() } }
    val live by lazy { app.hermesandroid.voice.GeminiLive(this, context) }
    val wake by lazy { app.hermesandroid.voice.Wake(this, context) }
    val liveCall by lazy { app.hermesandroid.voice.LiveCall(this, context) }
    /** The live call screen is showing (it can be closed while the call goes on). */
    var showCall by mutableStateOf(false)

    /** Answers a call from Hermes: its dashboard and profile, then a Live call that starts with why it called. */
    fun answerIncoming(call: app.hermesandroid.voice.IncomingCall) {
        store.pendingCall = ""
        val target = dashboards.firstOrNull { it.id == call.dashboard || app.hermesandroid.push.dashboardUuid(it.id) == call.dashboard }?.id
        if (target != null && target != store.activeId) switchDashboard(target)
        if (!signedIn) return
        call.profile?.let { p -> if (p == "default" || p in profiles) switchProfile(p) }
        showCall = true
        liveCall.start(call)
    }

    /** A call that rang out while nobody answered (and the app wasn't there to say so). */
    private suspend fun reportMissedCall() {
        // No pending call means any ringing notification is left over from a process that died mid-ring.
        val call = app.hermesandroid.voice.HermesCalls.decode(store.pendingCall) ?: return app.hermesandroid.voice.HermesCalls.dismiss(context)
        if (System.currentTimeMillis() - call.at < app.hermesandroid.voice.HermesCalls.RING_MS) return
        store.pendingCall = ""
        app.hermesandroid.voice.HermesCalls.dismiss(context)
        app.hermesandroid.voice.HermesCalls.report(this, call, "missed")
    }

    /** A Live call asked for from outside the app (widget, shortcut, tile): the process may have just started, so the
     *  connection and Gemini Live's status come first. */
    /** `show` = false: the call is drawn elsewhere (the assistant's window), not as the app's call screen. */
    fun talkWhenReady(show: Boolean = true, onProblem: (String) -> Unit) {
        if (!signedIn) return onProblem(tr(R.string.hermesapp_sign_in_first))
        if (liveCall.active) { if (show) showCall = true; return }
        scope.launch {
            if (!live.available) {
                kotlinx.coroutines.withTimeoutOrNull(20_000) { client.connected.first { it } }
                runCatching { live.refresh() }
            }
            startVoice(show)?.let(onProblem)
        }
    }

    /** The voice button: a Gemini Live call when the host offers one, else why not. */
    fun startVoice(show: Boolean = true): String? {
        if (!live.available) return live.statusText()
        if (!live.enabled) return tr(R.string.hermesapp_live_off)
        if (show) showCall = true
        liveCall.start()
        return null
    }

    init {
        app.hermesandroid.ui.theme.DisplayPrefs.chatScale = store.chatScale
        app.hermesandroid.ui.theme.DisplayPrefs.haptics = store.haptics
        store.defaultName.takeIf { it.isNotBlank() }?.let { app.hermesandroid.ui.shell.defaultProfileTitle = it }
        // A ring left by a process that died mid-call (Android doesn't time out call notifications); reported on connect.
        app.hermesandroid.voice.HermesCalls.decode(store.pendingCall).let { call ->
            if (call == null || System.currentTimeMillis() - call.at >= app.hermesandroid.voice.HermesCalls.RING_MS) app.hermesandroid.voice.HermesCalls.dismiss(context)
        }
        // Proactive notices schedule their own passes; a process start (reboot, push, update) renews the alarm.
        alerts.check("start")
        scope.launch { runCatching { app.hermesandroid.phone.PhoneJobs.schedule(context) } }
        client.onConnected = {
            connectedEpoch++
            loadProfiles()
            hostPlugin.refresh()
            if (hostPlugin.reports("pulse")) scope.launch { pulse.load() }
            loadDefaultName()
            reportMissedCall()
            push.sync()
            live.refresh()
            phone.sync("connected", force = true)
            location.onAppOpen()
            health.sync("connected")
            watch.pushStatus()
        }
        scope.launch {
            for (item in client.inbox) when (item) {
                is HermesEvent -> onEvent(item)
                is ServerRequest -> onServerRequest(item)
            }
        }
        if (signedIn) client.start()
    }

    fun listen(listener: (HermesEvent) -> Unit) {
        listeners += listener
    }

    suspend fun signIn(url: String, username: String, password: String) {
        client.login(url, username, password)
        afterSignIn()
    }

    suspend fun signInOAuth(url: String, code: String, verifier: String) {
        client.completeOAuth(url, code, verifier)
        afterSignIn()
    }

    private fun afterSignIn() {
        addingFrom = null
        dashboards.clear()
        dashboards.addAll(store.dashboards())
        activeDashboard = store.activeId
        resetSession()
        signedIn = true
        client.start()
    }

    /** Signs out of the active dashboard; it stays in the saved list. */
    fun signOut() {
        client.logout()
        resetSession()
        signedIn = false
    }

    /** Opens the login screen for a new dashboard, keeping the current one to return to. */
    fun beginAddDashboard() {
        val from = store.activeId
        client.switchDashboard()
        store.addDashboard()
        addingFrom = from
        resetSession()
        login.reset()
        signedIn = false
    }

    fun cancelAddDashboard() {
        val from = addingFrom ?: return
        store.removeDashboard(store.activeId)
        switchDashboard(from)
    }

    fun switchDashboard(id: String) {
        addingFrom = null
        store.activeId = id
        activeDashboard = id
        client.switchDashboard()
        resetSession()
        login.reset()
        signedIn = store.hasCredentials
        if (signedIn) client.start()
    }

    fun removeDashboard(id: String) {
        if (id == store.activeId) client.logout()
        store.removeDashboard(id)
        dashboards.clear()
        dashboards.addAll(store.dashboards())
        if (store.activeId.isEmpty() || id == activeDashboard) {
            store.dashboards().firstOrNull()?.let { switchDashboard(it.id) } ?: run { resetSession(); signedIn = false }
        }
    }

    private fun resetSession() {
        requests.clear()
        chat.close()
        chats.clear()
        avatars.clear()
        profiles.clear()
        profiles.add("default")
        profile = client.profile
        app.hermesandroid.ui.shell.defaultProfileTitle = store.defaultName.ifBlank { "AllFather" }
        page = null
    }

    fun unlock() { locked = false }
    fun setBiometric(on: Boolean) { store.biometricLock = on }

    /** Text and files shared from another app (sharesheet/ShareActivity), until the app opens their chat. */
    var sharedDraft: app.hermesandroid.sharesheet.SharedDraft? = null

    /** Puts a shared draft in its chat's composer (a new chat unless one was picked in the share sheet). Nothing is sent. */
    fun openShared() {
        val d = sharedDraft ?: return
        if (!signedIn) return
        sharedDraft = null
        openFromPush(d.dashboard, d.profile, d.chat, d.title)
        if (d.chat == null) chat.openNew()
        chat.draft = if (chat.draft.isBlank()) d.text else chat.draft + "\n" + d.text
        chat.attachments.addAll(d.files)
    }

    /** A tapped notification: its dashboard, then its profile, then its chat (or, for Pulse, the Pulse page). */
    fun openFromPush(dashboard: String?, profileName: String?, session: String?, title: String?, pulse: Boolean = false) {
        // A push names its dashboard by our id (E2E record) or by the UUID the relay knows it as.
        val target = dashboards.firstOrNull { it.id == dashboard || app.hermesandroid.push.dashboardUuid(it.id) == dashboard }?.id
        if (target != null && target != store.activeId) switchDashboard(target)
        if (!signedIn) return
        if (pulse) {
            if (profileName != null && (profileName == "default" || profileName in profiles)) switchProfile(profileName)
            // Already on screen, the page wouldn't reload by itself: fetch the cards this push announced.
            if (page == Page.Pulse) scope.launch { this@AppGraph.pulse.load() }
            page = Page.Pulse
            return
        }
        // A scheduled run (cron_<job>_<date>_<time>): its job's sheet, which opens on the newest run's result.
        // A helper bot's run or chat doesn't move the whole app to the bot: the default profile lists every
        // profile's jobs, and a chat opens in its own profile (this also works on a cold start, before the profile list loads).
        app.hermesandroid.tasks.cronJobOf(session)?.let {
            if (profileName != null && profileName != profile) switchProfile("default")
            tasks.page = app.hermesandroid.tasks.TasksPage.Scheduled
            tasks.showJob = it
            page = Page.Tasks
            return
        }
        page = null
        if (session != null) chat.open(session, title ?: "Chat", profileName)
    }

    /** The default profile shows the agent's own name (SOUL.md), remembered per dashboard for offline starts. */
    private suspend fun loadDefaultName() {
        val soul = runCatching { client.rest("GET", "/api/plugins/conduit_push/personality").obj() }.getOrNull()?.takeIf { it.str("text") != null }?.str("text")
        val name = soul?.let { app.hermesandroid.ui.shell.soulName(it) }
        if (name != null) { store.defaultName = name; app.hermesandroid.ui.shell.defaultProfileTitle = name }
    }

    fun switchProfile(name: String) {
        if (name == profile) return
        client.profile = name
        profile = name
        chat.close()
        chats.profileChanged()
    }

    fun setChatScale(v: Float) { store.chatScale = v; app.hermesandroid.ui.theme.DisplayPrefs.chatScale = v }
    fun setHaptics(on: Boolean) { store.haptics = on; app.hermesandroid.ui.theme.DisplayPrefs.haptics = on }

    private suspend fun loadProfiles() {
        runCatching {
            val r = client.rest("GET", "/api/profiles").obj()
            val names = (r?.get("profiles") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: it.obj()?.str("name") }
            // An empty list is a degraded reply (Hermes always has `default`): keep what we know.
            if (names.isNotEmpty()) {
                val ordered = listOf("default") + names.filter { it != "default" }.sorted()
                profiles.clear()
                profiles.addAll(ordered)
                if (profile !in ordered) switchProfile("default")
            }
        }
        profiles.forEach { name ->
            if (name in avatars) return@forEach
            runCatching {
                val r = client.call("profiles.get_asset", kotlinx.serialization.json.buildJsonObject {
                    put("name", kotlinx.serialization.json.JsonPrimitive(name)); put("asset", kotlinx.serialization.json.JsonPrimitive("avatar"))
                }, scoped = false).obj()
                if (r?.get("found")?.let { (it as? JsonPrimitive)?.contentOrNull } == "true") {
                    r.str("data")?.substringAfter("base64,")?.let { avatars[name] = android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
                }
            }
        }
    }

    private fun onEvent(e: HermesEvent) {
        when (e.type) {
            "request.cancel" -> requests.removeAll { it.id == e.payload.str("id") }
            "approval.cancelled" -> requests.removeAll { it.method == "approval" && it.params.str("session_id") == e.sessionId }
        }
        listeners.forEach { it(e) }
    }

    private fun onServerRequest(r: ServerRequest) {
        if (r.method == "approval") r.params.str("session_id")?.let { sid -> liveCall.approvalArrived(sid, r.params.str("description") ?: r.params.str("command") ?: "${app.hermesandroid.ui.shell.defaultProfileTitle} is asking for permission.") }
        when {
            r.method in ANSWERED -> if (requests.none { it.id == r.id }) requests += r
            r.method in WINDOW_METHODS -> if (client.declinesNotShown) client.replyError(r.id, 4404, "not shown on this client")
            // Anything unanswered makes the agent wait out its deadline, so decline at once.
            else -> client.replyError(r.id, -32601, "no handler for server request: ${r.method}")
        }
    }

    fun answer(r: ServerRequest, result: kotlinx.serialization.json.JsonObject) {
        client.reply(r.id, result)
        requests.remove(r)
    }

    /** Re-deliver requests a resumed session reports as still open. */
    fun adoptOpenRequests(open: JsonArray?) {
        open.orEmpty().mapNotNull { it.obj() }.forEach { req ->
            val id = req.str("id") ?: return@forEach
            onServerRequest(ServerRequest(id, req.str("method").orEmpty(), req["params"].obj() ?: EMPTY_OBJECT))
        }
    }

    private companion object {
        val ANSWERED = setOf("approval", "clarify", "sudo", "secret", "vault.unlock_prompt")
        val WINDOW_METHODS = setOf("preview.read", "preview.act", "terminal.read", "window.read", "tour")
    }
}
