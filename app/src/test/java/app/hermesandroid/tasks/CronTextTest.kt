package app.hermesandroid.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CronTextTest {
    @Test
    fun commonShapes() {
        assertEquals("Every day at 11:30", cronToWords("30 11 * * *"))
        assertEquals("Weekdays at 7:00", cronToWords("0 7 * * 1-5"))
        assertEquals("Weekends at 10:00", cronToWords("0 10 * * 0,6"))
        assertEquals("Every Sunday at 18:52", cronToWords("52 18 * * 0"))
        assertEquals("Every Monday and Thursday at 9:05", cronToWords("5 9 * * 1,4"))
        assertEquals("Every Monday, Wednesday and Friday at 8:00", cronToWords("0 8 * * 1,3,5"))
        assertEquals("On day 15 of every month at 3:00", cronToWords("0 3 15 * *"))
        assertEquals("Every 30 minutes", cronToWords("*/30 * * * *"))
        assertEquals("Every minute", cronToWords("*/1 * * * *"))
        assertEquals("Every 6 hours", cronToWords("0 */6 * * *"))
    }

    @Test
    fun leavesTheRestAlone() {
        assertNull(cronToWords("every day at 9am"))
        assertNull(cronToWords("0 9 * 1 *"))
        assertNull(cronToWords("0 9 * * MON"))
    }
}
