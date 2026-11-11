package app.hermesandroid.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.graph
import app.hermesandroid.push.newNotification

/** Keeps the call alive with the screen off or the app in the background, with a Hang up action. A call from the watch
 *  uses the watch's microphone: it runs as a connected-device service (a microphone one can't start from the background). */
class LiveCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) { applicationContext.graph.liveCall.hangUp(); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.livecallservice_channel), NotificationManager.IMPORTANCE_LOW))
        val watch = intent?.getBooleanExtra(EXTRA_WATCH, false) == true
        val name = app.hermesandroid.ui.shell.defaultProfileTitle
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val hangUp = PendingIntent.getService(this, 1, Intent(this, LiveCallService::class.java).setAction(ACTION_HANG_UP), PendingIntent.FLAG_IMMUTABLE)
        val n = newNotification(this, CHANNEL).setContentTitle(tr(R.string.livecallservice_talking_to, name)).setContentText(if (watch) tr(R.string.livecallservice_from_watch) else "Gemini Live")
            .setOngoing(true).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, tr(R.string.livecallservice_hang_up), hangUp).build()
        val type = if (watch) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        // Refused (e.g. no while-in-use access): the call goes on without the service rather than crashing the app.
        runCatching { ServiceCompat.startForeground(this, 1001, n, type) }.onFailure { android.util.Log.w("LiveCall", "foreground: ${it.message}"); stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "live_call"
        private const val ACTION_HANG_UP = "hang_up"
        private const val EXTRA_WATCH = "watch"
        fun start(context: Context, watch: Boolean = false) { context.startForegroundService(Intent(context, LiveCallService::class.java).putExtra(EXTRA_WATCH, watch)) }
        fun stop(context: Context) { context.stopService(Intent(context, LiveCallService::class.java)) }
    }
}
