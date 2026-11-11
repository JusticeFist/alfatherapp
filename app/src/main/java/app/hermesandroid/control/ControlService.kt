package app.hermesandroid.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import app.hermesandroid.R
import app.hermesandroid.tr
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// Phone control (Settings > Accessibility > Allfather): reads the app on screen as numbered elements and performs
// the assistant's steps on them. It only acts while the user is driving the assistant; it never reads password fields
// and never acts inside banks, wallets or password managers.

class ControlService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; bubble?.close(); return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Our own windows (the bubble, the assistant's card) change as Allfather works: they mustn't hold up [settle].
        if (event == null || event.packageName == packageName) return
        lastEvent = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) frontApp = event.packageName?.toString()
    }
    override fun onInterrupt() {}

    private var bubble: ControlBubble? = null

    /** Shows the bubble over every app (it then follows [PhoneAgent.bubble] by itself). */
    fun showBubble() = mainExecutor.execute { (bubble ?: ControlBubble(this).also { bubble = it }).show() }

    /** Steps run here, off the main thread: every node read is a call into the app on screen, which answers slowly when
     *  busy. One thread, so [elements] and [shotScale] need no locks. */
    val worker = Dispatchers.Default.limitedParallelism(1)

    /** When the screen last changed (content changes are only heard while a batch runs, see [watchChanges]). */
    @Volatile private var lastEvent = 0L
    /** The app whose window came up last (window-state events are heard all the time). */
    @Volatile private var frontApp: String? = null
    private var watchers = 0

    /** While a batch runs: hear every change on screen, so [settle] knows when the app stopped moving. Off the rest of the
     *  time, so the service isn't woken by every redraw all day. Counted: two batches at once (a call's job and a chat)
     *  don't switch it off for each other. On [worker]. */
    fun watchChanges(on: Boolean) {
        watchers += if (on) 1 else -1
        val all = watchers > 0
        serviceInfo = (serviceInfo ?: return).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or if (!all) 0 else
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED
            notificationTimeout = if (all) 100 else 200
        }
    }

    private fun now() = SystemClock.uptimeMillis()

    /**
     * Waits until the screen stops changing (no event for 350 ms), at most `max` ms after `from` (when the step was sent);
     * with `pkg`, also until that app is in front. A fixed pause read the screen mid-transition (recents, the launcher, an
     * empty list) and cost a turn each time.
     */
    private suspend fun settle(from: Long, max: Long, pkg: String? = null) {
        while (now() - from < max) {
            val quiet = now() - lastEvent
            // A tap's new screen can take a moment to start drawing: until its first event, give it 300 ms.
            val started = lastEvent > from || now() - from >= 300
            if (started && quiet >= 350 && (pkg == null || frontApp == pkg)) return
            delay((350 - quiet).coerceIn(50, 350))
        }
    }

    /** A node worth showing: its words, or for a row without any, the words inside it; `shown` is false for text already
     *  folded into its row's label. */
    private class Item(val node: AccessibilityNodeInfo, val label: String?, val actionable: Boolean, val shown: Boolean)

    /** The numbered elements of the last screen read, to act on by number. */
    private var elements = listOf<Item>()

    /** Screen pixels per pixel of the last capture, for taps on it. */
    private var shotScale = 1f

    /** The window to act on and its root: the active one unless it's ours (the assistant sits on top), so the notification
     *  shade counts too; else the topmost app. */
    private class Front(val window: AccessibilityWindowInfo?, val root: AccessibilityNodeInfo)

    private fun front(): Front? {
        val ws = windows.filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        fun AccessibilityWindowInfo.front() = root?.takeIf { it.packageName != packageName }?.let { Front(this, it) }
        return ws.filter { it.isActive }.firstNotNullOfOrNull { it.front() }
            ?: ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }.firstNotNullOfOrNull { it.front() }
            ?: rootInActiveWindow?.takeIf { it.packageName != packageName }?.let { Front(null, it) }
    }

    private fun appRoot() = front()?.root

    private fun label(n: AccessibilityNodeInfo): String? =
        if (n.isPassword) "(password field)"
        else (n.text ?: n.contentDescription ?: n.hintText)?.toString()?.replace('\n', ' ')?.trim()?.take(100)?.ifBlank { null }

    private fun actionable(n: AccessibilityNodeInfo) = n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable || n.isScrollable

    /** One pass over the tree. An actionable element without words of its own (a list row) is labeled by the plain text
     *  inside it, which isn't listed again; actionable elements inside it are listed on their own. */
    private fun read(root: AccessibilityNodeInfo): List<Item> {
        val out = mutableListOf<Item>()
        /** `row`: the words of the nearest actionable ancestor without a label of its own, where this node's text goes. */
        fun walk(n: AccessibilityNodeInfo, depth: Int, row: MutableList<String>?) {
            if (depth > 40 || out.size >= 400 || !n.isVisibleToUser) return
            val own = label(n)
            if (!actionable(n)) {
                if (own != null) { row?.add(own); out += Item(n, own, actionable = false, shown = row == null) }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, row) }
                return
            }
            val at = out.size
            out += Item(n, own, actionable = true, shown = true)
            val inner = if (own == null) mutableListOf<String>() else null
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, inner) }
            if (!inner.isNullOrEmpty()) out[at] = Item(n, inner.joinToString(" · ").take(100), actionable = true, shown = true)
        }
        walk(root, 0, null)
        return out
    }

    private fun kind(n: AccessibilityNodeInfo) = when {
        n.isEditable -> "field"
        n.isCheckable -> "checkbox"
        n.isClickable || n.isLongClickable -> "button"
        else -> "list"
    }

    private fun states(n: AccessibilityNodeInfo) = buildList {
        if (n.isCheckable && n.isChecked) add("checked")
        if (n.isSelected) add("selected")
        if (!n.isEnabled) add("disabled")
        if (n.isEditable && n.isFocused) add("focused")
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") ?: ""

    /** The screen for Allfather: the window's title, then one line per useful element ("[12] button «Send»"). */
    fun screen(): Screen {
        val f = front() ?: return Screen(null, "No app is open.", false)
        val pkg = f.root.packageName?.toString()
        if (blockedApp(pkg)) return Screen(pkg, "A banking, payment or password app: phone control doesn't act here.", true)
        val list = mutableListOf<Item>()
        val body = StringBuilder()
        var unlabeled = 0
        for (item in read(f.root)) {
            if (!item.shown) continue
            if (!item.actionable) { body.append("· ").append(item.label).append('\n'); continue }
            if (list.size >= 200) continue
            list += item
            if (item.label == null && !item.node.isScrollable) unlabeled++
            body.append("[${list.size}] ${kind(item.node)}").append(item.label?.let { " «$it»" } ?: "").append(states(item.node)).append('\n')
        }
        elements = list
        val head = buildString {
            f.window?.title?.let { append("Window «").append(it).append("»\n") }
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) append("(keyboard open)\n")
            if (unlabeled >= 3) append("($unlabeled buttons without text: to know what they are, use look)\n")
        }
        return Screen(pkg, (head + body).take(6000).ifBlank { "(screen without text)" }, false)
    }

    /** The label of element `n` of the last screen, for the progress line. */
    fun labelOf(n: Int): String? = elements.getOrNull(n - 1)?.label

    /** What a step aims at: a node with its label (a tap on a capture's point has the node under it), or why it can't. */
    private class Aim(val node: AccessibilityNodeInfo? = null, val label: String? = null, val point: PointF? = null, val miss: String? = null)

    /** Element `n` of the last screen, if it's still there: a number only holds for the screen Allfather saw, and tapping a
     *  stale one would hit whatever is there now. */
    private fun byNumber(n: Int): Aim {
        val e = elements.getOrNull(n - 1) ?: return Aim(miss = "There's no element $n.")
        return if (e.node.refresh() && e.node.isVisibleToUser) Aim(e.node, e.label) else Aim(miss = "Element $n is gone: the screen changed.")
    }

    private fun aim(a: Action.Tap): Aim = when {
        a.n != null -> byNumber(a.n)
        // Words are looked up on the screen as it is now, so they work on a screen Allfather hasn't seen yet.
        a.text != null -> {
            val items = appRoot()?.let(::read).orEmpty()
            items.getOrNull(bestMatch(items.map { it.label }, a.text))?.let { Aim(it.node, it.label) } ?: Aim(miss = "«${a.text}» isn't on the screen.")
        }
        a.x != null && a.y != null -> PointF(a.x * shotScale, a.y * shotScale).let { p -> nodeAt(p).let { Aim(it, it?.let(::label), p) } }
        else -> Aim(miss = "Nothing to tap: give n, text or x,y.")
    }

    /** The deepest element under a point, to judge a tap on a capture. */
    private fun nodeAt(p: PointF): AccessibilityNodeInfo? {
        val r = Rect()
        var hit: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || !n.isVisibleToUser) return
            n.getBoundsInScreen(r)
            if (!r.contains(p.x.toInt(), p.y.toInt())) return
            hit = n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        appRoot()?.let { walk(it, 0) }
        return hit
    }

    /** The field to type into: element `n`, or the one with input focus, or the only one on screen. */
    private fun field(n: Int?): Aim =
        if (n != null) byNumber(n)
        else appRoot()?.let { root -> root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: read(root).map { it.node }.singleOrNull { it.isEditable } }
            ?.let { Aim(it) } ?: Aim(miss = "No field has focus: tap the field first.")

    /** What a step did, in a few words for Allfather; `ok` false ends the batch (Allfather sees the screen before trying
     *  something else); `shot`, the capture `ver` asked for. */
    class Outcome(val said: String, val ok: Boolean = true, val shot: ByteArray? = null)

    /** Performs one step on [worker]. `ask` gets the user's OK first for a risky one: a send/pay/delete-like button, or
     *  Enter anywhere but a search box. The target is found once, so the OK is for what gets tapped. */
    suspend fun perform(a: Action, ask: suspend (String) -> Boolean): Outcome {
        /** Null when it may go on. The OK can take a while: if the screen changed meanwhile, it's not the same button. */
        suspend fun refused(question: String, what: String, target: AccessibilityNodeInfo): Outcome? = when {
            !ask(question) -> Outcome("The user said no to: $what. Don't do it or try it another way.", ok = false)
            !target.refresh() -> Outcome("The screen changed while waiting for the OK: not done.", ok = false)
            else -> null
        }
        return when (a) {
            is Action.Open -> open(a.app)
            is Action.Link -> link(a.url)
            is Action.Tap -> {
                val aim = aim(a)
                aim.miss?.let { return Outcome(it, ok = false) }
                val node = aim.node
                if (node != null && !node.isEditable && riskyNode(aim.label, node.viewIdResourceName)) {
                    val target = (aim.label ?: node.viewIdResourceName?.substringAfter('/')).orEmpty()
                    refused(tr(R.string.controlservice_ask_tap, target), "tap «$target»", node)?.let { return it }
                }
                val from = now()
                val said = when {
                    aim.point != null -> if (gesture(aim.point, a.long)) "Tapped the point." else return Outcome("The tap didn't reach the screen.", ok = false)
                    else -> tap(node!!, a.long)
                }
                settle(from, 2500)
                Outcome(said)
            }
            is Action.Type -> {
                val f = field(a.n)
                val node = f.node ?: return Outcome(f.miss ?: "There's no field.", ok = false)
                if (node.isPassword) return Outcome("Passwords are never typed.", ok = false)
                if (a.submit && riskySubmit(node.hintText?.toString(), node.viewIdResourceName))
                    refused(tr(R.string.controlservice_ask_type_enter, a.text.take(60)), "type «${a.text.take(60)}» and press Enter", node)?.let { return it }
                type(node, a.text, a.submit)
            }
            is Action.Scroll -> {
                val list = if (a.n == null) appRoot()?.let(::read)?.map { it.node }?.filter { it.isScrollable }?.maxByOrNull(::area)
                    else byNumber(a.n).node?.let { n -> generateSequence(n) { it.parent }.firstOrNull { it.isScrollable } }
                val from = now()
                if (list?.performAction(if (a.down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true)
                    return Outcome("Nothing to scroll there.", ok = false)
                settle(from, 1200)
                Outcome("Scrolled.")
            }
            Action.Back -> global(GLOBAL_ACTION_BACK, "Went back.", "Couldn't go back.")
            Action.Home -> global(GLOBAL_ACTION_HOME, "Went home.", "Couldn't go home.")
            Action.Notifications -> global(GLOBAL_ACTION_NOTIFICATIONS, "Opened the notifications.", "Couldn't open the notifications.")
            is Action.Wait -> { delay(a.ms); Outcome("Waited.") }
            Action.Look -> capture()
        }
    }

    private suspend fun global(action: Int, ok: String, failed: String): Outcome {
        val from = now()
        if (!performGlobalAction(action)) return Outcome(failed, ok = false)
        settle(from, 2500)
        return Outcome(ok)
    }

    private fun area(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }.let { it.width() * it.height() }

    private suspend fun type(f: AccessibilityNodeInfo, text: String, submit: Boolean): Outcome {
        val from = now()
        // Enter goes to the field with input focus.
        if (submit && !f.isFocused) f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return Outcome("Couldn't type there.", ok = false)
        if (!submit) { settle(from, 800); return Outcome("Typed.") }
        val entered = Build.VERSION.SDK_INT >= 30 && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (!entered) return Outcome("Typed, but Enter does nothing here: tap the send or search button.", ok = false)
        settle(from, 2500)
        return Outcome("Typed and pressed Enter.")
    }

    /** A click on the element, or on its nearest clickable parent, or a tap gesture at its centre. */
    private suspend fun tap(n: AccessibilityNodeInfo, long: Boolean): String {
        var node: AccessibilityNodeInfo? = n
        while (node != null && !(if (long) node.isLongClickable else node.isClickable)) node = node.parent
        if (node?.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) == true)
            return if (long) "Long-pressed." else "Tapped."
        val r = Rect().also { n.getBoundsInScreen(it) }
        gesture(PointF(r.exactCenterX(), r.exactCenterY()), long)
        return if (long) "Long-pressed (gesture)." else "Tapped (gesture)."
    }

    /** A tap (or a long press) at a point, with the bubble out of the way; true once the system played it. */
    private suspend fun gesture(p: PointF, long: Boolean): Boolean = aside {
        suspendCancellableCoroutine { c ->
            val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y) }, 0, if (long) 700 else 60)
            val sent = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { c.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { c.resume(false) }
            }, null)
            if (!sent) c.resume(false)
        }
    }

    /** Runs `block` with the bubble out of the way (invisible, letting touches through): a capture or a gesture under it. */
    private suspend fun <T> aside(block: suspend () -> T): T {
        val b = bubble ?: return block()
        withContext(Dispatchers.Main) { b.aside(true) }
        try {
            delay(80)   // a frame or two without it
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { b.aside(false) }
        }
    }

    /** Opens an app by its label or package, and waits for it to come up. */
    private suspend fun open(name: String): Outcome {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = name.trim()
        fun score(info: ActivityInfo): Int {
            val label = info.loadLabel(pm).toString()
            return when {
                info.packageName.equals(q, true) -> 4
                label.equals(q, true) -> 3
                label.startsWith(q, true) -> 2
                label.contains(q, true) || info.packageName.contains(q.replace(" ", ""), true) -> 1
                else -> 0
            }
        }
        val scored = pm.queryIntentActivities(launcher, 0).map { it.activityInfo to score(it.activityInfo) }.filter { it.second > 0 }
        val top = scored.maxOfOrNull { it.second } ?: return Outcome("No app matches «$name»: try another name or its package.", ok = false)
        val best = scored.filter { it.second == top }.map { it.first }.distinctBy { it.packageName }
        // Opening the wrong one of two costs more than asking: Allfather picks by package.
        if (best.size > 1) return Outcome("Several apps match «$name»: ${best.take(5).joinToString { "${it.loadLabel(pm)} (${it.packageName})" }}. None opened: use the package.", ok = false)
        val match = best.single()
        if (blockedApp(match.packageName)) return Outcome("A banking, payment or password app: not opening it.", ok = false)
        val from = now()
        startActivity(Intent(launcher).setClassName(match.packageName, match.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        settle(from, 4000, match.packageName)
        return Outcome("Opened ${match.loadLabel(pm)}.")
    }

    /** Opens a link or deep link, and waits for the app handling it (not when Android asks which one). */
    private suspend fun link(url: String): Outcome {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName ?: return Outcome("No app opens that link.", ok = false)
        if (blockedApp(pkg)) return Outcome("That link opens a banking, payment or password app: not opening it.", ok = false)
        val from = now()
        if (runCatching { startActivity(intent) }.isFailure) return Outcome("Couldn't open the link.", ok = false)
        settle(from, 4000, pkg.takeIf { it != "android" })
        return Outcome("Opened the link.")
    }

    /** A JPEG of the screen (shorter side 720 px) for Allfather's next turn. */
    private suspend fun capture(): Outcome {
        if (Build.VERSION.SDK_INT < 30) return Outcome("This Android version can't take screenshots.", ok = false)
        if (blockedApp(appRoot()?.packageName?.toString())) return Outcome("A banking, payment or password app: no screenshot.", ok = false)
        // ponytail: the assistant's card stays in the capture (bottom of the screen); hide it for a moment if it gets in the way.
        val bitmap = aside { screenshot() } ?: return Outcome("Couldn't take the screenshot (this screen doesn't allow it).", ok = false)
        val scale = minOf(1f, 720f / minOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        shotScale = 1f / scale
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
        return Outcome("Screenshot attached, ${small.width}×${small.height}: to tap something only visible in it, use tap with x,y in its pixels.", shot = jpeg)
    }

    @RequiresApi(30)
    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine<ScreenshotResult?> { c ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) { c.resume(result) }
            override fun onFailure(errorCode: Int) { c.resume(null) }
        })
    }?.let { shot -> shot.hardwareBuffer.use { Bitmap.wrapHardwareBuffer(it, shot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) } }

    companion object {
        /** The running service, when the user turned it on. */
        @Volatile var instance: ControlService? = null
    }
}

/** What Allfather sees: the app's package, its numbered elements, and whether it's an app it won't touch. */
data class Screen(val app: String?, val text: String, val blocked: Boolean)
