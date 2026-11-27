package app.hermesandroid.settings

import app.hermesandroid.ui.shell.ScrollRule
import app.hermesandroid.ui.shell.rememberPageList
import app.hermesandroid.ui.components.EmptyState
import app.hermesandroid.ui.components.Skeleton
import app.hermesandroid.ui.components.Refreshable
import app.hermesandroid.ui.theme.row
import app.hermesandroid.ui.theme.rows
import app.hermesandroid.ui.components.ErrorState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.chat.AfMarkdown
import app.hermesandroid.net.HttpError
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import app.hermesandroid.ui.components.Eyebrow
import app.hermesandroid.ui.components.ago
import app.hermesandroid.ui.components.ScreenHeader
import app.hermesandroid.ui.components.SectionHeader
import app.hermesandroid.ui.components.SettingsButton
import app.hermesandroid.ui.icons.AF
import app.hermesandroid.ui.shell.CapsuleIcon
import app.hermesandroid.ui.shell.PageBar
import app.hermesandroid.ui.shell.defaultProfileTitle
import app.hermesandroid.ui.theme.C
import app.hermesandroid.ui.theme.CType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// The Pulse page (sidebar): the plugin's Pulse screen (conduit_push /pulse/feed). Since plugin 0.66 each report item is
// its own card and is rated here; a typed "1 yes, 2 no" in the Pulse chat no longer counts.

data class PulseCard(
    val id: Int, val kind: String, val topic: String, val text: String, val chatTitle: String,
    val createdAt: Long, val linked: Boolean, val rating: String?, val done: Boolean, val dismissed: Boolean,
) {
    /** Report items and follow-ups take thumbs; a whole report or brief doesn't. */
    val ratable get() = kind == "noticed" || kind == "followup"
    /** Done needs a remembered item behind the card (its promise is what closes). */
    val doable get() = ratable && linked
    /** Still waiting on the user: rateable and not yet rated, done or put away. */
    val pending get() = ratable && rating == null && !done && !dismissed
}

fun parsePulseFeed(o: JsonObject?): List<PulseCard> = (o?.get("cards") as? JsonArray).orEmpty().mapNotNull { e ->
    val c = e.obj() ?: return@mapNotNull null
    val id = (c["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
    PulseCard(
        id, c.str("kind").orEmpty(), c.str("topic").orEmpty(),
        c.str("news")?.takeIf { it.isNotBlank() } ?: c.str("title").orEmpty(),
        c.str("chat_title").orEmpty(), c.num("created_at")?.toLong() ?: 0, c.str("item_id") != null,
        c.str("rating")?.takeIf { it.isNotEmpty() }, c.num("done_at") != null, c.num("dismissed_at") != null,
    )
}

fun List<PulseCard>.pending() = filter { it.pending }

private val TOPICS = mapOf(
    "commitment" to R.string.pulsepage_topic_commitment, "promise" to R.string.pulsepage_topic_promise, "date" to R.string.pulsepage_topic_date,
    "follow-up" to R.string.pulsepage_followup, "reminder" to R.string.pulsepage_topic_reminder, "watch" to R.string.pulsepage_topic_watch,
    "intention" to R.string.pulsepage_topic_intention, "interest" to R.string.pulsepage_topic_interest, "fact" to R.string.pulsepage_topic_fact,
    "other" to R.string.pulsepage_topic_other,
)

@Composable
private fun PulseCard.label() = when (kind) {
    "noticed" -> stringResource(TOPICS[topic] ?: R.string.pulsepage_noticed)
    "followup" -> stringResource(R.string.pulsepage_followup)
    "report" -> stringResource(R.string.pulsepage_report)
    "brief" -> stringResource(R.string.pulsepage_brief)
    else -> kind
}

class PulseFeed(private val graph: AppGraph) {
    var cards by mutableStateOf<List<PulseCard>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** The Pulse chat's stored id ("" from the host when Pulse is off). */
    var homeChatId by mutableStateOf<String?>(null)
        private set
    /** Cards waiting for a thumb (the sidebar row's count, the page's first section). */
    val pending get() = cards.orEmpty().pending()
    private val client get() = graph.client

    suspend fun load() {
        try {
            val o = client.rest("GET", "$BASE/feed").obj()
            cards = parsePulseFeed(o)
            homeChatId = o?.str("home_chat_id")?.takeIf { it.isNotEmpty() }
            error = null
        } catch (e: Exception) {
            error = message(e)
        }
    }

    /** Marks every card seen (the page is open). */
    suspend fun seeAll() {
        runCatching { client.rest("POST", "$BASE/feed/seen", buildJsonObject { put("ids", JsonNull) }) }
    }

    /** The thumb lights up at once; the host confirms behind it. */
    fun rate(card: PulseCard, up: Boolean) {
        val rating = if (up) "up" else "down"
        show(card) { it.copy(rating = rating) }
        graph.scope.launch { act("rate", card) { put("rating", rating) } }
    }

    fun done(card: PulseCard) = later(card, app.hermesandroid.tr(if (card.done) R.string.pulsepage_back_to_pending else R.string.pulsepage_marked_done), { it.copy(done = !card.done) }) {
        act("done", card) { put("done", !card.done) }
    }

    fun dismiss(card: PulseCard) = later(card, app.hermesandroid.tr(R.string.pulsepage_card_put_away), { it.copy(dismissed = true) }) { act("dismiss", card) { put("dismissed", true) } }

    private fun show(card: PulseCard, change: (PulseCard) -> PulseCard) {
        cards = cards?.map { if (it.id == card.id) change(it) else it }
    }

    /** Shown at once (the card moves or leaves); the notice sends it once its Undo has gone. */
    private fun later(card: PulseCard, text: String, change: (PulseCard) -> PulseCard, send: suspend () -> Unit) {
        show(card, change)
        graph.notify(text, undo = { show(card) { card } }, commit = send)
    }

    /** A refusal reloads, so the card goes back to what the host has. */
    private suspend fun act(path: String, card: PulseCard, body: JsonObjectBuilder.() -> Unit) {
        try {
            client.rest("POST", "$BASE/$path", buildJsonObject { put("card", card.id); body() })
        } catch (e: Exception) {
            error = message(e)
            load()
        }
    }

    private fun message(e: Exception) = when {
        e is HttpError && e.code == 404 -> tr(R.string.pulsepage_update_plugin)
        else -> tr(R.string.pulsepage_failed, e.message.orEmpty())
    }

    companion object {
        const val BASE = "/api/plugins/conduit_push/pulse"
    }
}

/**
 * Pulse as its own page (the Pulse chat stays behind it): what's waiting for a thumb on top,
 * the rest folded below, and the chat itself one tap away in the bar.
 */
@Composable
fun PulseScreen(graph: AppGraph, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val c = C.colors
    val feed = graph.pulse
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        feed.load()
        if (!feed.cards.isNullOrEmpty()) feed.seeAll()
    }
    val cards = feed.cards
    val (pending, history) = cards.orEmpty().filterNot { it.dismissed }.partition { it.pending }
    // A new chat with the card quoted in the composer: the user adds the question, nothing is sent.
    val talk = { card: PulseCard -> graph.chat.openNew(); graph.chat.quote(card.text) }
    // Folded while cards wait for a thumb, open once there's nothing to rate.
    var historyOpen by rememberSaveable(pending.isEmpty()) { mutableStateOf(pending.isEmpty()) }
    Column(Modifier.fillMaxSize()) {
        PageBar(onBack, trailing = feed.homeChatId?.let { id -> { CapsuleIcon(AF.Chats, stringResource(R.string.pulsepage_chat)) { onOpenChat(id) } } })
        // The header scrolls with the cards, so the rule sits under the bar.
        ScrollRule()
        Refreshable({ feed.load() }) {
            val pageList = rememberPageList()
            LazyColumn(state = pageList, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                row("head") {
                    // A row lays its content out in a Box, and a Box stacks its children: these three
                    // painted on top of one another. The head is a Column, like every other page's.
                    Column {
                        ScreenHeader(stringResource(R.string.pulsepage_eyebrow), "Pulse", "%02d".format(pending.size))
                        Help(
                            stringResource(R.string.pulsepage_help, defaultProfileTitle),
                            Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                        )
                        feed.error?.let { ErrorState(it) { scope.launch { feed.load() } } }
                    }
                }
                when {
                    cards == null -> row("wait") { Skeleton() }
                    pending.isEmpty() -> row("rated") { EmptyState(AF.Pulse, stringResource(if (history.isEmpty()) R.string.pulsepage_no_cards else R.string.pulsepage_nothing_to_rate)) }
                }
                if (pending.isNotEmpty()) {
                    row("h-pending") { SectionHeader(stringResource(R.string.pulsepage_to_rate), pending.size, collapsed = false) {} }
                    rows(pending, key = { it.id }) { PulseCardView(it, feed, talk) }
                }
                if (history.isNotEmpty()) {
                    row("h-history") { SectionHeader(stringResource(R.string.pulsepage_earlier), history.size, collapsed = !historyOpen) { historyOpen = !historyOpen } }
                    if (historyOpen) rows(history, key = { it.id }) { PulseCardView(it, feed, talk) }
                }
            }
        }
    }
}

@Composable
private fun PulseCardView(card: PulseCard, feed: PulseFeed, onTalk: (PulseCard) -> Unit) {
    val c = C.colors
    Card(null, AF.AutoAwesome, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        val meta = listOfNotNull(card.label(), card.chatTitle.takeIf { card.linked && it.isNotBlank() },
            card.createdAt.takeIf { it > 0 }?.let { ago(it.toDouble()) })
        Text(meta.joinToString(" · "), style = CType.data, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        AfMarkdown(card.text)
        if (card.done) Eyebrow(stringResource(R.string.pulsepage_done))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.ratable) {
                // Thumbs as quiet icons: the chosen one lights up in a raised well. The API has no "unrate": tapping it again does nothing.
                @Composable fun thumb(mine: String, up: Boolean) = CapsuleIcon(
                    if (up) AF.ThumbUp else AF.ThumbDown, stringResource(if (up) R.string.pulsepage_useful else R.string.pulsepage_not_useful), selected = card.rating == mine,
                    tint = when (card.rating) { null -> c.secondary; mine -> c.text; else -> c.secondary },
                ) { if (card.rating != mine) feed.rate(card, up) }
                thumb("up", true)
                thumb("down", false)
            }
            CapsuleIcon(AF.ChatBubble, stringResource(R.string.pulsepage_talk), tint = c.secondary) { onTalk(card) }
            Spacer(Modifier.weight(1f))
            if (card.doable) SettingsButton(stringResource(if (card.done) R.string.pulsepage_undo else R.string.pulsepage_done), AF.Check) { feed.done(card) }
            SettingsButton(stringResource(R.string.pulsepage_not_now)) { feed.dismiss(card) }
        }
    }
}
