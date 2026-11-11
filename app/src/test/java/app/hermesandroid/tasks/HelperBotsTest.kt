package app.hermesandroid.tasks

import app.hermesandroid.sessions.ChatsState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The default profile's cross-profile lists (`profile=all`) stamp each row with its owner: a helper bot's job or chat.
 *  Robolectric: `jobOf` words the cron schedule from resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class HelperBotsTest {
    @Test
    fun cronJobKeepsItsProfile() {
        val job = TasksState.jobOf(Json.parseToJsonElement(
            """{"id":"a1b2c3d4e5f6","name":"daily-budget","prompt":"p","schedule":{"kind":"cron","expr":"15 11 * * *"},"enabled":true,"deliver":"local","profile":"ledger"}""",
        ).jsonObject)!!
        assertEquals("ledger", job.profile)
        assertNull(TasksState.jobOf(Json.parseToJsonElement("""{"id":"a","name":"x","schedule":"30m"}""").jsonObject)!!.profile)
    }

    @Test
    fun chatRowKeepsItsProfile() {
        val row = ChatsState.rowOf(Json.parseToJsonElement(
            """{"id":"20261031_1","title":"Budget","source":"discord","last_active":1793500000,"profile":"ledger","is_default_profile":false}""",
        ).jsonObject)!!
        assertEquals("ledger", row.profile)
    }
}
