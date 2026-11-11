package app.hermesandroid.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun hears() {
        assertTrue(WakePhrase.matches("oye Allfather qué hora es", "Oye Allfather"))
        assertTrue(WakePhrase.matches("Oye, all father", "Oye Allfather")) // spaces moved
        assertTrue(WakePhrase.matches("oye alfather", "Oye Allfather")) // one slip
        assertTrue(WakePhrase.matches("Óye ALLFATHER!", "oye allfather"))
    }

    @Test fun ignores() {
        assertFalse(WakePhrase.matches("oye que tal el partido", "Oye Allfather"))
        assertFalse(WakePhrase.matches("allfather", "Oye Allfather"))
        assertFalse(WakePhrase.usable("Allfather"))
    }
}
