package app.hermesandroid.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakableClipsTest {
    @Test
    fun cutsWholeSentencesAsTheyArrive() {
        val partial = "Buenas tardes, señor. Su próximo evento es la reunión del equipo a las cinco. Despu"
        // The first clip needs only 15 characters (the voice starts sooner); later ones 40.
        val (first, at) = speakableClips(partial, 0, final = false)
        assertEquals(listOf("Buenas tardes, señor.", "Su próximo evento es la reunión del equipo a las cinco."), first)
        // A short sentence after the first waits for the next one.
        assertEquals(listOf("Buenas tardes, señor.", "Sí. Su próximo evento es la reunión del equipo a las cinco."),
            speakableClips("Buenas tardes, señor. Sí. Su próximo evento es la reunión del equipo a las cinco. x", 0, final = false).first)
        val full = partial + "és tiene la tarde libre."
        val (rest, end) = speakableClips(full, at, final = true)
        assertEquals(listOf("Después tiene la tarde libre."), rest)
        assertEquals(full.length, end)
    }

    @Test
    fun decimalsAndShortAnswers() {
        // A dot inside "3.5" isn't an end of sentence; a short whole answer is flushed at the end.
        assertEquals(emptyList<String>(), speakableClips("La temperatura es de 3.5 grados", 0, final = false).first)
        assertEquals(listOf("Hola."), speakableClips("Hola.", 0, final = true).first)
    }
}
