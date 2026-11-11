package app.hermesandroid.launcher

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.assist.AllfatherAssistService
import app.hermesandroid.push.EXTRA_DASHBOARD
import app.hermesandroid.push.EXTRA_PROFILE
import app.hermesandroid.push.EXTRA_SESSION
import app.hermesandroid.push.EXTRA_TITLE
import app.hermesandroid.ui.shell.defaultProfileTitle

// Ways into Allfather from outside the app (widgets and App Shortcuts): launcher shortcuts
// (res/xml/shortcuts.xml), a Quick Settings tile and a home-screen widget. Each opens MainActivity with EXTRA_LAUNCH.

const val EXTRA_LAUNCH = "launch"
const val LAUNCH_NEW_CHAT = "new_chat"
/** A Gemini Live call, like the voice button. */
const val LAUNCH_TALK = "talk"

fun launchIntent(context: Context, what: String): Intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_LAUNCH, what)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** The tile: the assistant panel when Allfather is the phone's assistant (like holding the power button), else a Live call. */
class AllfatherTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = defaultProfileTitle
            subtitle = tr(if (AllfatherAssistService.instance != null) R.string.launcher_assistant else R.string.launcher_talk)
            updateTile()
        }
    }

    override fun onClick() {
        val go = {
            // The activity collapses the shade first; it then opens the panel or the call.
            val i = Intent(this, TileLaunchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
        if (isLocked) unlockAndRun { go() } else go()
    }
}

class TileLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val assistant = AllfatherAssistService.instance
        if (assistant != null) assistant.showSession(Bundle(), 0) else startActivity(launchIntent(this, LAUNCH_TALK))
        finish()
    }
}

/** Home-screen widget : the agent's name, the last chat to carry on, New chat and Talk. */
class LauncherWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { manager.updateAppWidget(it, views(context)) }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("widget", Context.MODE_PRIVATE)

        /** The latest chat, from the chat list; redraws every widget. */
        fun remember(context: Context, id: String?, title: String?, profile: String, dashboard: String?) {
            prefs(context).edit().putString("id", id).putString("title", title).putString("profile", profile).putString("dashboard", dashboard)
                .putString("name", defaultProfileTitle).apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LauncherWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val p = prefs(context)
            val title = p.getString("title", null)
            fun pending(code: Int, i: Intent) = PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val resume = if (p.getString("id", null) == null) Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SESSION, p.getString("id", null)).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_PROFILE, p.getString("profile", null)).putExtra(EXTRA_DASHBOARD, p.getString("dashboard", null))
            return RemoteViews(context.packageName, R.layout.widget_launcher).apply {
                setTextViewText(R.id.widget_name, p.getString("name", null) ?: defaultProfileTitle)
                setTextViewText(R.id.widget_title, title?.let { tr(R.string.launcher_continue, it) } ?: tr(R.string.launcher_open_chats))
                setOnClickPendingIntent(R.id.widget_head, pending(1, resume))
                setOnClickPendingIntent(R.id.widget_new, pending(2, launchIntent(context, LAUNCH_NEW_CHAT)))
                setOnClickPendingIntent(R.id.widget_talk, pending(3, launchIntent(context, LAUNCH_TALK)))
            }
        }
    }
}
