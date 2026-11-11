package app.hermesandroid.chat

import app.hermesandroid.control.CONTROL_RULES
import org.junit.Assert.assertEquals
import org.junit.Test

class UserWordsTest {
    private val screen = "[Screen (Home):\n[1] Clock\n[2] Search\n]"

    @Test fun dropsTheControlBlock() {
        assertEquals("Find ginger ale", ChatState.userWords("<system-reminder>\n$CONTROL_RULES\n$screen\n</system-reminder>\nFind ginger ale"))
        assertEquals("Hola [x]", ChatState.userWords("Hola [x]"))
    }
}
