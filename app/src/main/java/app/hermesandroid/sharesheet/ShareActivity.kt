package app.hermesandroid.sharesheet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.hermesandroid.MainActivity
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.chat.Attachment
import app.hermesandroid.chat.ChatState
import app.hermesandroid.graph
import app.hermesandroid.push.EXTRA_DASHBOARD
import app.hermesandroid.push.EXTRA_PROFILE
import app.hermesandroid.push.EXTRA_SESSION
import app.hermesandroid.push.EXTRA_TITLE
import app.hermesandroid.sessions.SessionRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Sharing into Allfather from other apps: the text and files land in a chat's
// composer and nothing is sent; the user reviews and sends. "Allfather" in the share sheet starts a new chat; the
// recent chats published below appear there too (Direct Share) and on the launcher icon's long-press.

/** What was shared, waiting for the app to open its chat. */
class SharedDraft(val text: String, val files: List<Attachment>, val chat: String?, val title: String?, val profile: String?, val dashboard: String?)

const val EXTRA_SHARED = "shared_draft"
private const val SHARE_CATEGORY = "app.hermesandroid.category.SHARE_CHAT"

class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = graph
        val text = listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            .filter { it.isNotBlank() }.distinct().let { parts ->
                // Browsers send the page title as the subject and the link as the text: keep both unless one holds the other.
                if (parts.size == 2 && parts[1].contains(parts[0])) listOf(parts[1]) else parts
            }.joinToString("\n")
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        // A chat picked in the share sheet (Direct Share) carries its shortcut id; its intent says which chat.
        val target = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let { id -> ShortcutManagerCompat.getDynamicShortcuts(this).firstOrNull { it.id == id }?.intent }
        graph.scope.launch(Dispatchers.IO) {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3 * 86_400_000L }?.forEach { it.delete() }
            val skipped = mutableListOf<String>()
            val files = uris.take(10).mapNotNull { copy(it, dir, skipped) }
            withContext(Dispatchers.Main) {
                if (skipped.isNotEmpty()) Toast.makeText(this@ShareActivity, tr(R.string.shareactivity_not_added, skipped.joinToString(), ChatState.MAX_ATTACHMENT_BYTES / 1_048_576), Toast.LENGTH_LONG).show()
                if (text.isNotBlank() || files.isNotEmpty()) {
                    graph.sharedDraft = SharedDraft(text, files, target?.getStringExtra(EXTRA_SESSION), target?.getStringExtra(EXTRA_TITLE),
                        target?.getStringExtra(EXTRA_PROFILE), target?.getStringExtra(EXTRA_DASHBOARD))
                    startActivity(Intent(this@ShareActivity, MainActivity::class.java).putExtra(EXTRA_SHARED, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                finish()
            }
        }
    }

    /** A private copy (the sender's read grant ends with this activity); null when unreadable or too big. */
    private fun copy(uri: Uri, dir: File, skipped: MutableList<String>): Attachment? = runCatching {
        var name = uri.lastPathSegment ?: tr(R.string.shareactivity_file)
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
        val f = File(dir, "${System.nanoTime()}-${name.replace('/', '_')}")
        val ok = contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > ChatState.MAX_ATTACHMENT_BYTES) return@use false
                    out.write(buf, 0, n)
                }
                true
            }
        } ?: false
        if (!ok) { f.delete(); skipped += name; return null }
        Attachment(Uri.fromFile(f), name, contentResolver.getType(uri) ?: "application/octet-stream", f.length())
    }.getOrElse { skipped += uri.lastPathSegment.orEmpty(); null }
}

/** Recent chats as Direct Share targets and launcher shortcuts (the watch's and the assistant's own chats aside). */
fun publishChatShortcuts(context: Context, rows: List<SessionRow>, profile: String, dashboard: String?) {
    // Compared with the labels ChatsState.readablePreview gave those rows.
    val own = setOf(tr(R.string.chatsstate_from_watch), tr(R.string.chatsstate_from_assistant))
    val recent = rows.filter { !it.archived && it.preview !in own }.take(3)
    recent.firstOrNull().let { app.hermesandroid.launcher.LauncherWidget.remember(context, it?.id, it?.title, profile, dashboard) }
    val icon = IconCompat.createWithResource(context, app.hermesandroid.R.mipmap.ic_launcher)
    val shortcuts = recent.mapIndexed { i, r ->
        ShortcutInfoCompat.Builder(context, "chat:${r.id}")
            .setShortLabel(r.title.take(25)).setLongLabel(r.title.take(60)).setIcon(icon).setRank(i)
            .setCategories(setOf(SHARE_CATEGORY)).setLongLived(true)
            .setIntent(Intent(Intent.ACTION_VIEW, null, context, MainActivity::class.java)
                .putExtra(EXTRA_SESSION, r.id).putExtra(EXTRA_TITLE, r.title).putExtra(EXTRA_PROFILE, profile).putExtra(EXTRA_DASHBOARD, dashboard))
            .build()
    }
    runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
}
