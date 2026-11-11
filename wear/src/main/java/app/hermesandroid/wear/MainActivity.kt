package app.hermesandroid.wear

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.launch

// The watch app: tap the gold mic, speak, and Allfather answers on the wrist (read aloud when that's on).
// Speech recognition runs on the watch; the question and answer travel through the phone.

private val Gold = Color(0xFFE2B048)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("watch", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                var speak by remember { mutableStateOf(prefs.getBoolean("speak", true)) }
                val scope = rememberCoroutineScope()
                fun send(text: String) {
                    WatchState.question = text; WatchState.reply = ""; WatchState.error = null; WatchState.busy = true
                    Speaker.reset()
                    scope.launch {
                        if (!Phone.ask(this@MainActivity, text, speak)) { WatchState.busy = false; WatchState.error = getString(R.string.mainactivity_phone_not_connected) }
                    }
                }
                val listen = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                    val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                        ?: android.app.RemoteInput.getResultsFromIntent(r.data ?: Intent())?.getCharSequence("q")?.toString()
                    if (r.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) send(text)
                }
                fun ask() {
                    val voice = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.mainactivity_ask_prompt, WatchState.name))
                    // Google's recognizer straight away (a Galaxy Watch otherwise asks every time, next to the keyboard).
                    val google = Intent(voice).setPackage("com.google.android.tts")
                    if (google.resolveActivity(packageManager) != null) listen.launch(google)
                    else if (voice.resolveActivity(packageManager) != null) listen.launch(voice)
                    else listen.launch(androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent().also {
                        androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(android.app.RemoteInput.Builder("q").setLabel(getString(R.string.mainactivity_question)).build()))
                    })
                }
                LaunchedEffect(Unit) { Phone.loadStatus(this@MainActivity) }
                // Each launch intent once — also when the app was already open (the tile's mic, a typed question).
                LaunchedEffect(launches) {
                    // Opened from the tile's mic: listen right away.
                    if (intent.getBooleanExtra(EXTRA_LISTEN, false)) { intent.removeExtra(EXTRA_LISTEN); ask() }
                    // A typed question (shortcuts, and `adb shell am start … --es ask "…"` to test without speaking).
                    intent.getStringExtra(EXTRA_ASK)?.takeIf { it.isNotBlank() }?.let { intent.removeExtra(EXTRA_ASK); send(it) }
                }
                LaunchedEffect(WatchState.finished) {
                    // Hermes' voice comes from the phone (Speaker.expect); without it the watch reads it itself.
                    if (WatchState.finished > WatchState.spoken) {
                        WatchState.spoken = WatchState.finished
                        if (speak && !Speaker.expecting) Speaker.local(this@MainActivity, WatchState.reply)
                    }
                }
                val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                    if (ok) Live.start(this@MainActivity) else WatchState.error = getString(R.string.mainactivity_no_mic)
                }
                // A call keeps the screen on: the mic and speaker live while it's showing.
                LaunchedEffect(Live.state != null) {
                    if (Live.state != null) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                if (Live.state != null) CallScreen(onHangUp = { Live.hangUp(this@MainActivity) })
                else Screen(speak, onSpeak = { speak = it; prefs.edit().putBoolean("speak", it).apply(); if (!it) Speaker.cancel() }, onAsk = { Speaker.stop(); ask() },
                    onCall = { Speaker.stop(); if (Live.permitted(this@MainActivity)) Live.start(this@MainActivity) else micPermission.launch(android.Manifest.permission.RECORD_AUDIO) })
            }
        }
    }

    /** Bumped by every launch, so an intent that reaches an open app is acted on too. */
    private var launches by androidx.compose.runtime.mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); launches++ }

    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { super.onPause(); visible = false }

    companion object {
        @Volatile var visible = false
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_ASK = "ask"
    }
}

@Composable
private fun Screen(speak: Boolean, onSpeak: (Boolean) -> Unit, onAsk: () -> Unit, onCall: () -> Unit) {
    val list = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(Modifier.fillMaxWidth(), state = list, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text(WatchState.name, color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(top = 18.dp)) }
            item {
                Button(onClick = onAsk, modifier = Modifier.size(64.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Gold)) {
                    Icon(Icons.Filled.Mic, stringResource(R.string.mainactivity_ask), tint = Color.Black, modifier = Modifier.size(30.dp))
                }
            }
            item {
                androidx.wear.compose.material.Chip(onClick = onCall, label = { Text(stringResource(R.string.mainactivity_talk_live), fontSize = 13.sp) },
                    icon = { Icon(Icons.Filled.GraphicEq, null, tint = Gold) },
                    colors = androidx.wear.compose.material.ChipDefaults.secondaryChipColors(), modifier = Modifier.padding(horizontal = 16.dp))
            }
            Live.ended?.let { e -> item { Text(e, fontSize = 12.sp, color = Color(0xFFFF6B6B), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) } }
            WatchState.question?.let { q -> item { Text(stringResource(R.string.mainactivity_heard, q), fontSize = 12.sp, color = Color.LightGray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) } }
            if (WatchState.busy && WatchState.reply.isEmpty()) item { CircularProgressIndicator(indicatorColor = Gold, modifier = Modifier.size(24.dp)) }
            if (WatchState.reply.isNotEmpty()) item { Text(WatchState.reply, fontSize = 14.sp, textAlign = TextAlign.Start, modifier = Modifier.padding(horizontal = 14.dp)) }
            WatchState.error?.let { e -> item { Text(e, fontSize = 12.sp, color = Color(0xFFFF6B6B), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) } }
            if (WatchState.question == null) WatchState.nextTitle?.let { t ->
                item { Text(stringResource(R.string.mainactivity_next, "$t · ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(WatchState.nextAt))}"), fontSize = 12.sp, color = Color.LightGray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.wear.compose.material.ToggleChip(checked = speak, onCheckedChange = onSpeak, label = { Text(stringResource(R.string.mainactivity_read_aloud), fontSize = 12.sp) },
                        toggleControl = { androidx.wear.compose.material.Switch(checked = speak) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/** A finished answer while the app is closed. */
object Notify {
    fun answer(context: Context, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("answers", context.getString(R.string.mainactivity_answers_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(1, NotificationCompat.Builder(context, "answers").setSmallIcon(R.drawable.ic_notification).setContentTitle(WatchState.name)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
    }
}

/** The call: who, what it's doing, mute and hang up. */
@Composable
private fun CallScreen(onHangUp: () -> Unit) {
    Scaffold(timeText = { TimeText() }) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(WatchState.name, color = Gold, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(stringResource(when (Live.state) { "connecting" -> R.string.mainactivity_connecting; "speaking" -> R.string.mainactivity_speaking; else -> R.string.mainactivity_listening }), fontSize = 13.sp, color = Color.LightGray)
            Icon(Icons.Filled.GraphicEq, null, tint = if (Live.state == "speaking") Gold else Color.Gray, modifier = Modifier.size(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = { Live.muted = !Live.muted }, modifier = Modifier.size(48.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = if (Live.muted) Color.White else Color(0xFF303030))) {
                    Icon(if (Live.muted) Icons.Filled.MicOff else Icons.Filled.Mic, stringResource(if (Live.muted) R.string.mainactivity_unmute else R.string.mainactivity_mute), tint = if (Live.muted) Color.Black else Color.White)
                }
                Button(onClick = onHangUp, modifier = Modifier.size(48.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFE5484D))) {
                    Icon(Icons.Filled.CallEnd, stringResource(R.string.mainactivity_hang_up), tint = Color.White)
                }
            }
        }
    }
}
