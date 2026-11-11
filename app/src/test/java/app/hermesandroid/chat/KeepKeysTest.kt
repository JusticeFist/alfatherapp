package app.hermesandroid.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepKeysTest {
    @Test fun serverCopyKeepsTheCachedKeys() {
        val cached = listOf(
            ChatMsg(1, Role.User, "hola", rowId = 10),
            ChatMsg(2, Role.Assistant, "hola", rowId = 11),
            ChatMsg(3, Role.Tool, toolId = "call_a"),
        )
        val server = listOf(
            ChatMsg(4, Role.User, "hola", rowId = 10),
            ChatMsg(5, Role.Assistant, "hola", rowId = 11),
            ChatMsg(6, Role.Tool, toolId = "call_a"),
            ChatMsg(7, Role.System, "nota"), // nothing to match: keeps its new key
            ChatMsg(8, Role.User, "otra", rowId = 12),
        )
        assertEquals(listOf(1L, 2L, 3L, 7L, 8L), keepKeys(cached, server).map { it.key })
    }

    @Test fun aKeyIsNeverUsedTwice() {
        val cached = listOf(ChatMsg(1, Role.Tool, toolId = "call_a"))
        val server = listOf(ChatMsg(2, Role.Tool, toolId = "call_a"), ChatMsg(3, Role.Tool, toolId = "call_a"))
        assertEquals(listOf(1L, 3L), keepKeys(cached, server).map { it.key })
    }
}
