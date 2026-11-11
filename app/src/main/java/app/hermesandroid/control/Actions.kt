package app.hermesandroid.control

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// Phone control's wire with Allfather (in the assistant's chat): the screen goes as numbered elements, Allfather
// answers with a ```acciones block of steps; anything else in the reply is what it says to the user. Pure, for tests.

/** One step Allfather asks for. `n` is an element number from the last screen. */
sealed class Action {
    data class Open(val app: String) : Action()
    /** A link or deep link (https, tel:, mailto:, geo:, market://…): the shortest way to a given screen. */
    data class Link(val url: String) : Action()
    /** An element by number (last screen), by its words (found on the screen at that moment, so it chains across
     *  screens), or a point on the last capture. `long`: a long press. */
    data class Tap(val n: Int? = null, val text: String? = null, val x: Int? = null, val y: Int? = null, val long: Boolean = false) : Action()
    /** Into element `n`, or the focused field when there's none; `submit` presses the keyboard's Enter after. */
    data class Type(val n: Int?, val text: String, val submit: Boolean = false) : Action()
    /** `n` picks the list; without it, the biggest one on screen. */
    data class Scroll(val down: Boolean, val n: Int? = null) : Action()
    object Back : Action()
    object Home : Action()
    object Notifications : Action()
    /** A capture of the screen for Allfather's next turn, for what the element list can't tell. */
    object Look : Action()
    data class Wait(val ms: Long) : Action()
}

/** Steps one reply may carry. */
const val MAX_ACTIONS = 8

/** Allfather's reply split: the steps (empty when it's done) and the words for the user. */
data class Reply(val actions: List<Action>, val say: String)

private val BLOCK = Regex("```(?:acciones|actions)\\s*\\n([\\s\\S]*?)```")

fun parseReply(text: String): Reply {
    val m = BLOCK.find(text) ?: return Reply(emptyList(), text.trim())
    val say = text.removeRange(m.range).trim()
    val arr = runCatching { Json.parseToJsonElement(m.groupValues[1].trim()) as? JsonArray }.getOrNull() ?: return Reply(emptyList(), say)
    val actions = arr.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
        when (val a = s("accion") ?: s("action")) {
            "abrir", "open" -> s("app")?.let { Action.Open(it) }
            "enlace", "link", "open_url" -> s("url")?.let { Action.Link(it) }
            "tocar", "tap", "mantener", "long_press" -> {
                val n = i("n"); val t = (s("texto") ?: s("text"))?.takeIf { it.isNotBlank() }; val x = i("x"); val y = i("y")
                val point = x != null && y != null
                if (n == null && t == null && !point) null
                else Action.Tap(n, t, x.takeIf { point }, y.takeIf { point }, long = a == "mantener" || a == "long_press")
            }
            "escribir", "type" -> (s("texto") ?: s("text"))?.let { Action.Type(i("n"), it, b("enviar") ?: b("submit") ?: false) }
            "desplazar", "scroll" -> Action.Scroll((s("dir") ?: "abajo") in setOf("abajo", "down"), i("n"))
            "atras", "atrás", "back" -> Action.Back
            "inicio", "home" -> Action.Home
            "notificaciones", "notifications" -> Action.Notifications
            "ver", "look", "screenshot" -> Action.Look
            "esperar", "wait" -> Action.Wait((i("ms") ?: 1000).toLong().coerceIn(200, 5000))
            else -> null
        }
    }.take(MAX_ACTIONS)
    return Reply(actions, say)
}

/** Words on a button that make tapping it something to confirm first: sending, paying, buying, deleting, publishing. */
private val RISKY = Regex(
    "\\b(enviar|env[ií]a|send|mandar|pagar|pago|pay|comprar|compra|buy|checkout|ordenar|order|transferir|transfer|" +
        "borrar|eliminar|delete|remove|vaciar|publicar|publish|post|compartir|share|confirmar|confirm|aceptar|accept|" +
        "suscribir|subscribe|llamar|call)\\b", RegexOption.IGNORE_CASE)

/** Only a button-sized label counts (≤ 30 chars, ≤ 4 words): a chat preview that mentions "send" isn't a Send button. */
fun risky(label: String?): Boolean =
    label != null && label.length <= 30 && label.trim().split(Regex("\\s+")).size <= 4 && RISKY.containsMatchIn(label)

/** Icon-only buttons give themselves away by their resource id ("com.whatsapp:id/send", "btn_delete"). */
private val RISKY_ID = Regex("(^|[/_:.])(send|delete|remove|pay|checkout|buy|purchase|add_to_cart|call)([_.]|$)", RegexOption.IGNORE_CASE)

fun riskyNode(label: String?, viewId: String?): Boolean = risky(label) || (viewId != null && RISKY_ID.containsMatchIn(viewId))

private val SEARCH = Regex("search|buscar|busca|query|find", RegexOption.IGNORE_CASE)

/** Enter in a search box only searches; anywhere else (a chat, a mail) it may send. */
fun riskySubmit(hint: String?, viewId: String?): Boolean = !SEARCH.containsMatchIn(listOfNotNull(hint, viewId).joinToString(" "))

/** The element `tocar(texto)` means: the exact label first, then one starting with it, then one containing it; ties go to
 *  the first on screen. -1 when none matches. */
fun bestMatch(labels: List<String?>, query: String): Int {
    val q = query.trim()
    for (rank in 0..2) {
        val i = labels.indexOfFirst { l ->
            val t = l?.trim() ?: return@indexOfFirst false
            when (rank) { 0 -> t.equals(q, true); 1 -> t.startsWith(q, true); else -> t.contains(q, true) }
        }
        if (i >= 0) return i
    }
    return -1
}

/** Apps it never acts in: banks, wallets, payment and password managers. */
private val BLOCKED = Regex("bank|banco|banca|bbva|chase|wellsfargo|citi|capitalone|sofi|paypal|venmo|cashapp|zelle|wallet|" +
    "coinbase|binance|1password|bitwarden|lastpass|keepass|dashlane|authenticator|\\.otp", RegexOption.IGNORE_CASE)

fun blockedApp(packageName: String?): Boolean = packageName != null && BLOCKED.containsMatchIn(packageName)
