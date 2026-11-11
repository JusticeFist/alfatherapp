package app.hermesandroid.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsTest {
    @Test
    fun parsesStepsAndWords() {
        val r = parseReply("Abro WhatsApp.\n```acciones\n[{\"accion\":\"abrir\",\"app\":\"WhatsApp\"},{\"accion\":\"tocar\",\"n\":4},{\"accion\":\"escribir\",\"n\":7,\"texto\":\"Llego tarde\"},{\"accion\":\"nada\"}]\n```")
        assertEquals(listOf(Action.Open("WhatsApp"), Action.Tap(4), Action.Type(7, "Llego tarde")), r.actions)
        assertEquals("Abro WhatsApp.", r.say)
        // No block: it's the final answer.
        assertEquals(Reply(emptyList(), "Listo, señor."), parseReply(" Listo, señor. "))
        // A broken block doesn't act.
        assertTrue(parseReply("```acciones\n[{oops\n```").actions.isEmpty())
    }

    @Test
    fun parsesTheWiderSteps() {
        val r = parseReply("```acciones\n[" +
            "{\"accion\":\"tocar\",\"texto\":\"Buscar\"}," +
            "{\"accion\":\"escribir\",\"texto\":\"Sam\",\"enviar\":true}," +
            "{\"accion\":\"tocar\",\"x\":120,\"y\":640}," +
            "{\"accion\":\"mantener\",\"n\":3}," +
            "{\"accion\":\"desplazar\",\"dir\":\"arriba\",\"n\":9}," +
            "{\"accion\":\"enlace\",\"url\":\"tel:5551234\"}," +
            "{\"accion\":\"notificaciones\"}," +
            "{\"accion\":\"ver\"}," +
            "{\"accion\":\"atras\"}]\n```")
        assertEquals(listOf(
            Action.Tap(text = "Buscar"), Action.Type(null, "Sam", submit = true), Action.Tap(x = 120, y = 640),
            Action.Tap(3, long = true), Action.Scroll(false, 9), Action.Link("tel:5551234"), Action.Notifications, Action.Look,
        ), r.actions)   // 9 steps asked, MAX_ACTIONS kept
        // A tap with nothing to aim at, or half a point, is dropped.
        assertTrue(parseReply("```acciones\n[{\"accion\":\"tocar\"},{\"accion\":\"tocar\",\"x\":5}]\n```").actions.isEmpty())
    }

    @Test
    fun riskAndBlockedApps() {
        assertTrue(risky("Enviar"))
        assertTrue(risky("Send message"))
        assertTrue(risky("Pagar $12.00"))
        assertTrue(risky("Eliminar chat"))
        assertFalse(risky("Buscar"))
        assertFalse(risky("Enviado"))   // a label saying it was sent isn't an action
        assertFalse(risky(null))
        // A chat row mentioning "send" isn't a Send button.
        assertFalse(risky("Sam · ok, I'll send you the file tomorrow"))
        assertTrue(riskyNode(null, "com.whatsapp:id/send"))
        assertTrue(riskyNode(null, "com.app:id/btn_delete"))
        assertFalse(riskyNode(null, "com.whatsapp:id/sender_name"))
        assertFalse(riskySubmit("Search mail", null))
        assertFalse(riskySubmit(null, "com.google.android.youtube:id/search_edit_text"))
        assertTrue(riskySubmit("Message", "com.whatsapp:id/entry"))
        assertTrue(blockedApp("com.sofi.mobile"))
        assertTrue(blockedApp("com.x8bit.bitwarden"))
        assertFalse(blockedApp("com.whatsapp"))
    }

    @Test
    fun matchesTheTappedWords() {
        val labels = listOf(null, "Wi-Fi · Connected", "Network & internet", "Wi-Fi")
        assertEquals(3, bestMatch(labels, "wi-fi"))       // exact beats the row that starts with it
        assertEquals(1, bestMatch(labels.take(3), "Wi-Fi"))
        assertEquals(2, bestMatch(labels, "internet"))
        assertEquals(-1, bestMatch(labels, "Bluetooth"))
    }
}
