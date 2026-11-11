package app.hermesandroid.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.HttpError
import app.hermesandroid.net.bool
import app.hermesandroid.net.obj
import app.hermesandroid.net.str

// Gemini Live (GeminiLiveTokenClient + GeminiLiveConversationController): the host plugin mints a short-lived
// token with the host's Gemini key; the phone talks to Google's Live API directly with it.

class GeminiLive(private val graph: AppGraph, private val context: Context) {
    var checked by mutableStateOf(false)
        private set
    var available by mutableStateOf(false)
        private set
    var reason by mutableStateOf<String?>(null)
        private set
    var model by mutableStateOf<String?>(null)
        private set
    var enabled by mutableStateOf(graph.store.geminiLive)
        private set

    fun choose(on: Boolean) { enabled = on; graph.store.geminiLive = on }

    suspend fun refresh() {
        val r = runCatching { graph.client.rest("GET", graph.client.withProfile("/api/plugins/conduit_push/gemini-live/status")).obj() }
        r.onSuccess { o ->
            available = o?.bool("ok") == true && o.bool("available") == true
            reason = o?.str("reason") ?: o?.str("error")
            model = o?.str("model")
        }.onFailure { e ->
            available = false
            reason = if (e is HttpError && (e.code == 404 || e.code == 410)) "plugin_missing" else e.message
        }
        checked = true
    }

    fun statusText(): String = when {
        !checked -> tr(R.string.geminilive_checking)
        available -> tr(R.string.geminilive_available, model ?: "Gemini Live")
        reason == "no_api_key" -> tr(R.string.geminilive_no_api_key)
        reason == "plugin_missing" -> tr(R.string.geminilive_plugin_missing)
        else -> reason?.let { tr(R.string.geminilive_unavailable_why, it) } ?: tr(R.string.geminilive_unavailable)
    }
}
