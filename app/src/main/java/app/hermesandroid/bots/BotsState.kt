package app.hermesandroid.bots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import app.hermesandroid.ui.shell.decodeImage
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.hermesandroid.AppGraph
import app.hermesandroid.R
import app.hermesandroid.tr
import app.hermesandroid.net.bool
import app.hermesandroid.net.num
import app.hermesandroid.net.obj
import app.hermesandroid.net.str
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Bots are Hermes profiles (BotModeModels / BotManagement); group chats are gateway-hosted rooms (`groups.*`).

data class Bot(
    val name: String,
    val title: String,
    val description: String,
    val color: String?,
    val pinned: Boolean,
    val isDefault: Boolean,
    val lastActive: Double?,
    val lastPreview: String?,
    val metaRevision: Int?,
)

data class Member(val id: String, val profile: String?, val handle: String, val display: String)
data class Room(val id: String, val name: String, val members: List<Member>, val updatedAt: Double, val latestSeq: Int?)
data class RoomEvent(val seq: Int, val kind: String, val actor: String, val actorProfile: String?, val text: String?, val payload: JsonObject, val createdAt: Double)
/** A file picked for the next room message (uploaded on send). */
data class RoomFile(val uri: Uri, val name: String, val size: Long)

data class RoomApproval(val memberId: String, val taskId: String, val generation: Int, val requestId: String, val command: String, val description: String, val choices: List<String>)

private const val ROOM_FILE_MAX = 50L * 1024 * 1024

class BotsState(private val graph: AppGraph, private val context: Context) {
    private val client = graph.client

    val bots = mutableStateListOf<Bot>()
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)

    val rooms = mutableStateListOf<Room>()
    var groupsAvailable by mutableStateOf(true)
        private set
    var room by mutableStateOf<Room?>(null)
        private set
    val events = mutableStateListOf<RoomEvent>()
    val approvals = mutableStateListOf<RoomApproval>()
    var roomWorking by mutableStateOf(false)
        private set
    private var cursor = 0

    fun load() {
        graph.scope.launch {
            try {
                // Gateway-wide listing: sent unscoped so the active profile never shrinks the roster.
                val r = client.call("profiles.list", null, scoped = false).obj()
                val list = (r?.get("profiles") as? JsonArray).orEmpty().mapNotNull { it.obj()?.let(::botOf) }
                bots.clear()
                // Hermes (default) always first: it's the orchestrator the other bots work under.
                bots.addAll(list.sortedWith(compareBy({ !it.isDefault }, { !it.pinned }, { -(it.lastActive ?: 0.0) })))
                error = null
                list.filter { it.name !in graph.avatars }.forEach { loadAvatar(it.name) }
            } catch (e: Exception) {
                error = if (e.message?.contains("method", true) == true) tr(R.string.botsstate_update_hermes) else e.message
            }
            loadRooms()
        }
    }

    private suspend fun loadAvatar(name: String) = runCatching {
        val r = client.call("profiles.get_asset", buildJsonObject { put("name", name); put("asset", "avatar") }, scoped = false).obj()
        if (r?.bool("found") == true) r.str("data")?.substringAfter("base64,")?.let { graph.avatars[name] = Base64.decode(it, Base64.DEFAULT) }
    }

    // ---- create / edit / delete ----

    suspend fun describe(name: String): Pair<String, String> {
        val r = client.call("profiles.describe", buildJsonObject { put("name", name) }, scoped = false).obj()
        return (r?.str("description").orEmpty()) to (r?.str("soul").orEmpty())
    }

    /** New bot = new profile (60 s: Hermes copies the profile skeleton), then its ui_meta title and colour. */
    fun create(title: String, description: String, soul: String, color: String?, photo: Uri?, onDone: () -> Unit) = act(tr(R.string.botsstate_create_failed)) {
        val name = slug(title)
        if (name.isEmpty()) throw IllegalArgumentException(tr(R.string.botsstate_name_needed))
        client.call("profiles.create", buildJsonObject { put("name", name); put("description", description); put("soul", soul) }, timeoutMs = 60_000, scoped = false)
        client.call("profiles.configure", buildJsonObject {
            put("name", name)
            put("ui_meta", buildJsonObject { put(META_KEY, buildJsonObject { put("title", title.trim()); color?.let { put("color", it) } }) })
        }, scoped = false)
        photo?.let { setPhoto(name, it) }
        onDone()
        load()
    }

    fun update(bot: Bot, title: String, description: String, soul: String, color: String?, photo: Uri?, onDone: () -> Unit) = act(tr(R.string.botsstate_save_failed)) {
        if (bot.isDefault) throw IllegalStateException(tr(R.string.botsstate_main_not_editable, bot.title))
        client.call("profiles.configure", buildJsonObject {
            put("name", bot.name)
            put("description", description)
            put("soul", soul)
            put("ui_meta", buildJsonObject { put(META_KEY, buildJsonObject { put("title", title.trim()); color?.let { put("color", it) }; put("pinned", bot.pinned) }) })
            bot.metaRevision?.let { put("ui_meta_expected_revisions", buildJsonObject { put(META_KEY, it) }) }
        }, scoped = false)
        photo?.let { setPhoto(bot.name, it) }
        onDone()
        load()
    }

    fun togglePin(bot: Bot) = act(tr(R.string.botsstate_pin_failed)) {
        client.call("profiles.configure", buildJsonObject {
            put("name", bot.name)
            put("ui_meta", buildJsonObject { put(META_KEY, buildJsonObject { put("title", bot.title); bot.color?.let { put("color", it) }; put("pinned", !bot.pinned) }) })
        }, scoped = false)
        load()
    }

    /** The default profile can't be deleted (Desktop and the gateway refuse it). */
    fun delete(bot: Bot) = act(tr(R.string.botsstate_delete_failed)) {
        // Hermes (the default profile) is the orchestrator and holds everything: never deletable from here.
        if (bot.isDefault) throw IllegalStateException(tr(R.string.botsstate_main_not_deletable, bot.title))
        client.rest("DELETE", "/api/profiles/${java.net.URLEncoder.encode(bot.name, "UTF-8")}")
        graph.avatars.remove(bot.name)
        load()
    }

    /** Avatars are stored as a 256 px square PNG data URL (BotManagement.avatarEdge). */
    private suspend fun setPhoto(name: String, uri: Uri) {
        val png = withContext(Dispatchers.IO) {
            val src = decodeImage(ImageDecoder.createSource(context.contentResolver, uri), 256, cover = true, software = true) ?: return@withContext null
            val edge = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - edge) / 2, (src.height - edge) / 2, edge, edge)
            val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } ?: return
        client.call("profiles.set_asset", buildJsonObject {
            put("name", name); put("asset", "avatar"); put("data", "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP))
        }, scoped = false)
        graph.avatars[name] = png
    }

    private fun act(failure: String, block: suspend () -> Unit) {
        graph.scope.launch {
            try { block() } catch (e: Exception) { notice = "$failure: ${e.message}" }
        }
    }

    // ---- group rooms ----

    private suspend fun loadRooms() {
        runCatching {
            val r = client.call("groups.list", buildJsonObject { put("include_disbanded", false) }, scoped = false).obj()
            rooms.clear()
            rooms.addAll((r?.get("rooms") as? JsonArray).orEmpty().mapNotNull { roomOf(it.obj()) }.sortedByDescending { it.updatedAt })
            groupsAvailable = true
        }.onFailure { groupsAvailable = false }
        loadRoomPictures()
    }

    // ---- room pictures and files (conduit_push /room-images, /room-files) ----

    /** Each room's custom picture (JPEG/PNG bytes), and the revision it came at so only new ones are fetched. */
    val roomPictures = androidx.compose.runtime.mutableStateMapOf<String, ByteArray>()
    private val pictureRevs = mutableMapOf<String, String>()
    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private suspend fun loadRoomPictures() {
        runCatching {
            val revs = client.rest("GET", "/api/plugins/conduit_push/room-images").obj()?.get("revisions").obj().orEmpty()
                .mapNotNull { (id, v) -> (v as? JsonPrimitive)?.content?.let { id to it } }.toMap()
            (roomPictures.keys - revs.keys).forEach { roomPictures.remove(it); pictureRevs.remove(it) }
            revs.forEach { (id, rev) ->
                if (pictureRevs[id] == rev) return@forEach
                client.rest("GET", "/api/plugins/conduit_push/room-images/" + enc(id)).obj()?.str("data_base64")?.let {
                    roomPictures[id] = Base64.decode(it, Base64.DEFAULT); pictureRevs[id] = rev
                }
            }
        }
    }

    /** A 512 px square JPEG (the host takes JPEG or PNG up to 512 KB). */
    fun setRoomPicture(r: Room, uri: Uri) = act(tr(R.string.botsstate_picture_failed)) {
        val jpeg = withContext(Dispatchers.IO) {
            val src = decodeImage(ImageDecoder.createSource(context.contentResolver, uri), 512, cover = true, software = true) ?: return@withContext null
            val edge = minOf(src.width, src.height)
            val square = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, (src.width - edge) / 2, (src.height - edge) / 2, edge, edge), 512, 512, true)
            ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } ?: return@act
        val res = client.rest("PUT", "/api/plugins/conduit_push/room-images/" + enc(r.id), buildJsonObject { put("data_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP)) }).obj()
        roomPictures[r.id] = jpeg
        res?.str("rev")?.let { pictureRevs[r.id] = it }
    }

    fun removeRoomPicture(r: Room) = act(tr(R.string.botsstate_remove_picture_failed)) {
        client.rest("DELETE", "/api/plugins/conduit_push/room-images/" + enc(r.id))
        roomPictures.remove(r.id); pictureRevs.remove(r.id)
    }

    /** Files for the next message: the room's bots run on the host and read them from where the upload left them. */
    val roomFiles = mutableStateListOf<RoomFile>()

    fun addRoomFiles(uris: List<Uri>) {
        uris.forEach { uri ->
            var name = uri.lastPathSegment ?: tr(R.string.botsstate_file)
            var size = 0L
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
            }
            if (size > ROOM_FILE_MAX) notice = tr(R.string.botsstate_file_too_big, name) else roomFiles += RoomFile(uri, name, size)
        }
    }

    /** Rooms of 2–6 bots; each gets an @handle from its title (BotMentions), never all/everyone/user. */
    fun createRoom(name: String, members: List<Bot>, onDone: () -> Unit) = act(tr(R.string.botsstate_create_room_failed)) {
        val used = mutableSetOf("all", "everyone", "user")
        val payload = buildJsonArray {
            members.forEach { b ->
                var handle = handleOf(b.title).ifEmpty { b.name }
                if (!used.add(handle.lowercase())) handle = b.name
                used += handle.lowercase()
                add(buildJsonObject { put("member_id", b.name); put("profile", b.name); put("handle", handle); put("display_name", b.title) })
            }
        }
        val r = client.call("groups.create", buildJsonObject { put("room_id", "room-" + UUID.randomUUID()); put("name", name.trim()); put("members", payload) }, scoped = false).obj()
        roomOf(r?.get("room").obj())?.let { openRoom(it) }
        onDone()
        loadRooms()
    }

    fun openRoom(r: Room) {
        room = r
        events.clear()
        approvals.clear()
        roomFiles.clear()
        cursor = 0
        poll()
    }

    /** Its events stay until the next room opens: the screen still shows them while it slides away. */
    fun closeRoom() { room = null }

    /** groups.log is cursor-paged by seq; the open room polls every 3 s. */
    private fun poll() {
        val id = room?.id ?: return
        graph.scope.launch {
            while (room?.id == id) {
                runCatching {
                    while (true) {
                        val page = client.call("groups.log", buildJsonObject { put("room_id", id); put("since_seq", cursor); put("limit", 200); put("include_disbanded", true) }, scoped = false).obj() ?: break
                        val evs = (page["events"] as? JsonArray).orEmpty().mapNotNull { eventOf(it.obj()) }
                        if (room?.id != id) return@runCatching
                        events.addAll(evs.filter { e -> events.none { it.seq == e.seq } })
                        cursor = page.num("cursor")?.toInt() ?: evs.lastOrNull()?.seq ?: cursor
                        if (page.bool("has_more") != true) break
                    }
                    val st = client.call("groups.state", buildJsonObject { put("room_id", id); put("include_disbanded", true) }, scoped = false).obj()
                    val driver = st?.get("driver_status").obj()
                    roomWorking = driver?.bool("running") == true || driver?.bool("working") == true
                    approvals.clear()
                    approvals.addAll((driver?.get("pending_actions") as? JsonArray).orEmpty().mapNotNull { approvalOf(it.obj()) })
                }
                delay(3_000)
            }
        }
    }

    fun send(text: String) {
        val id = room?.id ?: return
        val files = roomFiles.toList()
        roomFiles.clear()
        act(tr(R.string.botsstate_send_failed)) {
            // Each file goes up first; the message carries its @file: reference (a room isn't a session, so no file.attach).
            val refs = files.map { f ->
                val b64 = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(f.uri)?.use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) } }
                    ?: throw IllegalStateException(tr(R.string.botsstate_read_failed, f.name))
                client.rest("POST", "/api/plugins/conduit_push/room-files", buildJsonObject { put("name", f.name); put("data_base64", b64) }, timeoutSec = 180)
                    .obj()?.str("ref_text") ?: throw IllegalStateException(tr(R.string.botsstate_upload_failed, f.name))
            }
            client.call("groups.send", buildJsonObject {
                put("room_id", id); put("event_id", UUID.randomUUID().toString())
                put("payload", buildJsonObject { put("text", (listOf(text.trim()) + refs).filter { it.isNotEmpty() }.joinToString("\n")); put("thread_id", "main") })
            }, scoped = false)
        }
    }

    fun stop() {
        val id = room?.id ?: return
        act(tr(R.string.botsstate_stop_failed)) { client.call("groups.stop", buildJsonObject { put("room_id", id) }, scoped = false) }
    }

    fun disband() {
        val id = room?.id ?: return
        act(tr(R.string.botsstate_disband_failed)) {
            client.call("groups.disband", buildJsonObject { put("room_id", id) }, scoped = false)
            closeRoom()
            loadRooms()
        }
    }

    fun approve(a: RoomApproval, choice: String) {
        val id = room?.id ?: return
        act(tr(R.string.botsstate_approve_failed)) {
            client.call("groups.approve", buildJsonObject {
                put("room_id", id); put("member_id", a.memberId); put("task_id", a.taskId)
                put("execution_generation", a.generation); put("choice", choice); put("request_id", a.requestId)
            }, scoped = false)
            approvals.remove(a)
        }
    }

    companion object {
        const val META_KEY = "hermes-bots"
        /** BotAvatarColor swatches: hsl(i·30°, 68 %, 58 %). */
        val SWATCHES: List<String> = (0 until 12).map { i ->
            val c = android.graphics.Color.HSVToColor(hsvFromHsl(i * 30f, 0.68f, 0.58f))
            "#%06x".format(0xFFFFFF and c)
        }

        private fun hsvFromHsl(h: Float, s: Float, l: Float): FloatArray {
            val v = l + s * minOf(l, 1 - l)
            val sv = if (v == 0f) 0f else 2 * (1 - l / v)
            return floatArrayOf(h, sv, v)
        }

        /** Upstream slugifyProfileName: ASCII, lowercase, dashes; accents fold to their base letter. */
        fun slug(title: String): String =
            Normalizer.normalize(title.trim().lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)

        fun handleOf(title: String) = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^A-Za-z0-9_]"), "")

        private fun botOf(o: JsonObject): Bot? {
            val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val meta = o["ui_meta"].obj()?.get(META_KEY).obj()
            if (meta?.bool("hidden") == true) return null
            val last = o["last_session"].obj()
            return Bot(
                name, meta?.str("title")?.takeIf { it.isNotBlank() } ?: o.str("display_name")?.takeIf { it.isNotBlank() } ?: if (name == "default") app.hermesandroid.ui.shell.defaultProfileTitle else name,
                o.str("description").orEmpty(), meta?.str("color"), meta?.bool("pinned") == true,
                o.bool("is_default") == true || name == "default", last?.num("last_active"), last?.str("preview"),
                o["ui_meta_revisions"].obj()?.num(META_KEY)?.toInt(),
            )
        }

        private fun roomOf(o: JsonObject?): Room? {
            o ?: return null
            val id = o.str("room_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val members = (o["members"] as? JsonArray).orEmpty().mapNotNull { it.obj() }.mapNotNull { m ->
                val mid = m.str("member_id") ?: m.str("profile") ?: return@mapNotNull null
                Member(mid, m.str("profile"), m.str("handle") ?: mid, m.str("display_name") ?: mid)
            }
            return Room(id, o.str("name") ?: id, members, o.num("updated_at") ?: o.num("created_at") ?: 0.0, o.num("latest_seq")?.toInt())
        }

        private fun eventOf(o: JsonObject?): RoomEvent? {
            o ?: return null
            val seq = o.num("seq")?.toInt()?.takeIf { it >= 1 } ?: return null
            val actor = o["actor"].obj()
            val payload = o["payload"].obj() ?: JsonObject(emptyMap())
            return RoomEvent(seq, o.str("kind") ?: "unknown", actor?.str("display_name") ?: actor?.str("profile") ?: actor?.str("kind") ?: "", actor?.str("profile"),
                payload.str("text"), payload, o.num("created_at") ?: 0.0)
        }

        private fun approvalOf(a: JsonObject?): RoomApproval? {
            a ?: return null
            if (a.str("kind") != "approval") return null
            val approval = a["approval"].obj() ?: JsonObject(emptyMap())
            val choices = ((approval["choices"] ?: a["choices"]) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            return RoomApproval(
                a.str("member_id") ?: return null, a.str("task_id") ?: return null, a.num("execution_generation")?.toInt() ?: return null,
                a.str("request_id") ?: approval.str("request_id") ?: return null,
                approval.str("command") ?: a.str("command").orEmpty(), approval.str("description") ?: a.str("description").orEmpty(),
                choices.ifEmpty { listOf("once", "deny") },
            )
        }
    }
}
