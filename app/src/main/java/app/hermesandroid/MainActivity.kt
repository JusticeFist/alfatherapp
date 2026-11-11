package app.hermesandroid

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import app.hermesandroid.ui.shell.PredictiveBackLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import app.hermesandroid.chat.ChatScreen
import app.hermesandroid.login.LoginScreen
import app.hermesandroid.sessions.ArtifactsPage
import app.hermesandroid.sessions.ProjectsScreen
import app.hermesandroid.sessions.Sidebar
import app.hermesandroid.ui.shell.PageBar
import app.hermesandroid.ui.theme.frosted
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import app.hermesandroid.settings.SettingsScreen
import app.hermesandroid.ui.components.AppIconArt
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.components.Wordmark
import app.hermesandroid.ui.theme.Backdrop
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.AfTheme
import app.hermesandroid.ui.theme.LocalHaze
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import app.hermesandroid.ui.theme.Motion
import app.hermesandroid.ui.icons.AF

// FragmentActivity: BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
        val graph = graph
        setContent {
            AfTheme {
                if (graph.locked && graph.store.biometricLock) LockScreen(::authenticate) else Root(graph)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        app.hermesandroid.push.AppVisibility.foreground = false
        app.hermesandroid.push.Progress.appHidden(this, graph.store)
        graph.wake.hidden()
    }

    override fun onStart() {
        super.onStart()
        app.hermesandroid.push.AppVisibility.foreground = true
        app.hermesandroid.push.Progress.appShown(this, graph.store)
        graph.wake.shown()
        graph.phone.sync("foreground")
        graph.location.onAppOpen()
        graph.health.sync("foreground")
        // Lock again after five minutes away.
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 5 * 60_000) graph.locked = true
    }

    private fun authenticate() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = graph.unlock()
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.mainactivity_unlock_title))
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** allfather:// (or conduit://, hermes://) connect?url=…&username=…&password=…. Only prefills a new dashboard. */
    private val micPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) talk() else android.widget.Toast.makeText(this, getString(R.string.mainactivity_mic_needed, app.hermesandroid.ui.shell.defaultProfileTitle), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** "Talk" from a shortcut, the widget or the tile: a Gemini Live call once the mic is allowed. */
    private fun talk() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        graph.talkWhenReady { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun handleLink(intent: Intent?) {
        when (intent?.getStringExtra(app.hermesandroid.launcher.EXTRA_LAUNCH)) {
            app.hermesandroid.launcher.LAUNCH_NEW_CHAT -> {
                intent.removeExtra(app.hermesandroid.launcher.EXTRA_LAUNCH)
                if (graph.signedIn) graph.chat.openNew()
                return
            }
            app.hermesandroid.launcher.LAUNCH_TALK -> { intent.removeExtra(app.hermesandroid.launcher.EXTRA_LAUNCH); talk(); return }
        }
        if (intent?.getBooleanExtra(app.hermesandroid.sharesheet.EXTRA_SHARED, false) == true) {
            intent.removeExtra(app.hermesandroid.sharesheet.EXTRA_SHARED)
            graph.openShared()
            return
        }
        app.hermesandroid.voice.HermesCalls.decode(intent?.getStringExtra(app.hermesandroid.voice.HermesCalls.EXTRA_CALL))?.let { call ->
            // Answered from the lock screen: show over it and wake the screen.
            setShowWhenLocked(true); setTurnScreenOn(true)
            app.hermesandroid.voice.HermesCalls.dismiss(this)
            intent?.removeExtra(app.hermesandroid.voice.HermesCalls.EXTRA_CALL)
            graph.answerIncoming(call)
            return
        }
        val session = intent?.getStringExtra(app.hermesandroid.push.EXTRA_SESSION)
        val pulse = intent?.getBooleanExtra(app.hermesandroid.push.EXTRA_PULSE, false) == true
        if (session != null || pulse) {
            graph.openFromPush(intent.getStringExtra(app.hermesandroid.push.EXTRA_DASHBOARD), intent.getStringExtra(app.hermesandroid.push.EXTRA_PROFILE), session, intent.getStringExtra(app.hermesandroid.push.EXTRA_TITLE), pulse)
            intent.removeExtra(app.hermesandroid.push.EXTRA_SESSION)
            intent.removeExtra(app.hermesandroid.push.EXTRA_PULSE)
            return
        }
        val uri = intent?.data ?: return
        if (uri.host != "connect") return
        if (graph.signedIn) graph.beginAddDashboard()
        graph.login.prefill(uri.toString())
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val c = C.colors
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize()) {
        Backdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIconArt(96.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(stringResource(R.string.mainactivity_locked))
                Wordmark(stringResource(R.string.app_name))
            }
            SettingsButton(stringResource(R.string.mainactivity_unlock), AF.Fingerprint, prominent = true, onClick = onUnlock)
        }
    }
}

/**
 * RootView → LoginView or the home: the chat (Claude's structure), the sidebar on the left with the chats and every
 * other section, and those sections as pages over the chat.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Root(graph: AppGraph) {
    if (!graph.signedIn) {
        LoginScreen(graph)
        return
    }
    val chat = graph.chat
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Home always holds a chat: a fresh one when none is open (first start, after a profile switch).
    LaunchedEffect(chat.isOpen) { if (!chat.isOpen) chat.openNew() }
    // Whatever covers the chat (a page, the sidebar, a bot room, a call) takes the composer's keyboard with it.
    val focus = LocalFocusManager.current
    // targetValue: the keyboard goes as the sidebar starts opening, not after (isOpen flips only once it has settled).
    val covered = graph.page != null || drawer.targetValue == DrawerValue.Open || graph.bots.room != null || graph.showCall
    LaunchedEffect(covered) { if (covered) focus.clearFocus() }
    // Back with the keyboard up closes the keyboard first. Android hands back to the newest handler, and the chat's
    // or a page's (registered after the keyboard's) used to win: the chat reset or the page closed under the keyboard.
    if (WindowInsets.isImeVisible) BackHandler { focus.clearFocus() }
    // What the sidebar's frosted glass blurs: the chat, or a page just picked in the sidebar as it closes over it.
    val haze = rememberHazeState()
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = graph.page == null || drawer.isOpen,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        drawerContent = {
            val sheet = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
            // With its state the sheet handles back itself, predictive gesture included.
            ModalDrawerSheet(
                drawerState = drawer,
                drawerContainerColor = Color.Transparent,
                drawerShape = sheet,
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            ) {
                CompositionLocalProvider(LocalHaze provides haze) {
                    Box(Modifier.fillMaxSize().frosted(sheet)) {
                        // A frame first: the picked chat or page composes while nothing moves, then the sidebar closes over it.
                        Sidebar(graph) { scope.launch { withFrameNanos {}; drawer.close() } }
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
            ChatScreen(graph, onMenu = { scope.launch { drawer.open() } })
            // Keep the last page on screen while it slides away.
            var shown by remember { mutableStateOf(graph.page) }
            if (graph.page != null) shown = graph.page
            AnimatedVisibility(
                graph.page != null,
                // Picked in the sidebar it is already there under it (one motion: the sidebar closing); else it slides in.
                enter = if (drawer.isOpen) EnterTransition.None else slideInHorizontally(Motion.page()) { it },
                exit = slideOutHorizontally(Motion.page()) { it },
            ) {
                PredictiveBackLayer(graph.page != null, onBack = { graph.page = null }) {
                    Backdrop(Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        val back = { graph.page = null }
                        when (shown) {
                            Page.Tasks -> app.hermesandroid.tasks.TasksScreen(graph, bottomInset = 0.dp, onBack = back) { id, title, profile -> chat.open(id, title, profile) }
                            Page.Bots -> app.hermesandroid.bots.BotsScreen(graph, bottomInset = 0.dp, onBack = back)
                            Page.Settings -> SettingsScreen(graph, bottomInset = 0.dp, onBack = back)
                            Page.Projects -> ProjectsScreen(graph, onBack = back)
                            Page.Pulse -> app.hermesandroid.settings.PulseScreen(graph, onBack = back) { id -> chat.open(id, "Pulse") }
                            Page.Artifacts -> Column(Modifier.fillMaxSize()) {
                                PageBar(back)
                                ArtifactsPage(graph, 0.dp) { id, title -> chat.open(id, title) }
                            }
                            null -> {}
                        }
                    }
                }
            }
            var shownRoom by remember { mutableStateOf(graph.bots.room) }
            graph.bots.room?.let { shownRoom = it }
            AnimatedVisibility(
                graph.bots.room != null,
                enter = slideInHorizontally(Motion.page()) { it },
                exit = slideOutHorizontally(Motion.page()) { it },
            ) {
                PredictiveBackLayer(graph.bots.room != null, onBack = graph.bots::closeRoom) { shownRoom?.let { app.hermesandroid.bots.RoomScreen(graph, it) } }
            }
            AnimatedVisibility(
                graph.showCall,
                enter = slideInHorizontally(Motion.page()) { it },
                exit = slideOutHorizontally(Motion.page()) { it },
                modifier = Modifier.zIndex(2f),
            ) {
                val hide = { graph.showCall = false }
                PredictiveBackLayer(graph.showCall, onBack = hide) { app.hermesandroid.voice.LiveCallScreen(graph, hide) }
            }
        }
    }
}
