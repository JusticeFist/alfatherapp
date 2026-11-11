package app.hermesandroid.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Start-up on the home chat, the sidebar, the Pulse page and its chat, then the other pages: what the user goes
 * through every day. Runs on the signed-in app and only reads (opening Pulse marks its cards seen).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "app.allfather", maxIterations = 8) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.desc("Menu")), 15_000)
        // The sidebar: its nav rows are plain text, the profile row at its foot says "settings".
        fun open(row: String) {
            device.findObject(By.desc("Menu"))?.click()
            device.wait(Until.hasObject(By.text(row)), 5_000)
            device.findObject(By.text(row))?.click()
            device.waitForIdle()
        }

        open("Pulse")
        device.wait(Until.hasObject(By.scrollable(true)), 10_000)
        device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
        // The Pulse chat: real messages, markdown and the composer.
        device.findObject(By.desc("Pulse conversation"))?.click()
        device.wait(Until.hasObject(By.scrollable(true)), 10_000)
        device.waitForIdle()
        device.findObject(By.scrollable(true))?.fling(Direction.UP)

        for (page in listOf("Tasks", "Bots", "Projects", "settings")) {
            open(page)
            device.pressBack()
        }
    }
}
