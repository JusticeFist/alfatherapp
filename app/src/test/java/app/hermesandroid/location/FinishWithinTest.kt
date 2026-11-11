package app.hermesandroid.location

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import java.time.Duration
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult

/** A slow upload must not hold the broadcast past Android's ANR window (the MOVED ANRs of 0.23.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FinishWithinTest {
    @Test fun finishesAfterEightSecondsWhileTheWorkGoesOn() {
        val ctx = RuntimeEnvironment.getApplication()
        var pending: BroadcastReceiver.PendingResult? = null
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { pending = goAsync() }
        }, IntentFilter("test.MOVED"), Context.RECEIVER_NOT_EXPORTED)
        ctx.sendBroadcast(Intent("test.MOVED").setPackage(ctx.packageName))
        val main = shadowOf(Looper.getMainLooper())
        main.idle()
        val finished = Shadow.extract<ShadowBroadcastPendingResult>(pending!!).future

        val scope = MainScope()
        val work = scope.launch { delay(60_000) }
        pending!!.finishWithin(scope, work)
        main.idleFor(Duration.ofSeconds(7))
        assertFalse(finished.isDone)
        main.idleFor(Duration.ofSeconds(2))
        assertTrue(finished.isDone)
        assertTrue(work.isActive)
        scope.cancel()
    }
}
