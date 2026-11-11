package app.hermesandroid.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Tool calls an orchestrator makes to ask its helper bots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConsultTest {
    private val profiles = listOf("default", "critic", "ledger", "scout")

    @Test
    fun oneShotCounselFromTheTerminal() {
        val args = """{"command": "timeout 280 hermes -p critic chat -Q --oneshot -q \"Review this plan in 5 lines.\""}"""
        assertEquals(Consult(listOf("critic"), task = false), consultOf(args, profiles))
        assertEquals("Asked Critic", consultOf(args, profiles)!!.title)
    }

    @Test
    fun severalAtOnceFromExecuteCode() {
        val args = """{"code": "import subprocess\nfor p in [\"scout\", \"ledger\"]:\n    subprocess.run([\"hermes\", \"-p\", p, \"chat\", \"-Q\", \"--oneshot\", \"-q\", q])"}"""
        assertEquals("Asked Ledger and Scout", consultOf(args, profiles)!!.title)
    }

    @Test
    fun boardTask() {
        val args = """{"command": "cd ~ && hermes kanban create \"Daylight saving end date\" --assignee scout --skill grounded-citations --created-by default --body \"…\""}"""
        assertEquals(Consult(listOf("scout"), task = true), consultOf(args, profiles))
        assertEquals("Assigned a task to Scout", consultOf(args, profiles)!!.title)
    }

    @Test
    fun otherHermesCommandsAreNotConsults() {
        // Moving a bot's cron isn't asking it anything.
        assertNull(consultOf("""{"command": "hermes -p ledger cron edit a1b2c3d4e5f6 --remove-skill google-workspace"}""", profiles))
        assertNull(consultOf("""{"command": "ls ~/.hermes/profiles"}""", profiles))
    }
}
