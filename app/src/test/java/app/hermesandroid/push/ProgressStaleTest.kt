package app.hermesandroid.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressStaleTest {
    @Test fun staleAfterSilence() {
        val a = Activity("x", "Chat", "s", null, null, startedAt = 0)
        assertFalse(progressStale(a, PROGRESS_STALE_MS - 1))
        assertTrue(progressStale(a, 5 * 3600_000L)) // the turn cut off by a dashboard restart
        assertFalse(progressStale(a.copy(updatedAt = 5 * 3600_000L - 60_000), 5 * 3600_000L)) // still reporting
    }
}
