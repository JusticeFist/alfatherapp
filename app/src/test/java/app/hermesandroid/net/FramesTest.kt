package app.hermesandroid.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame shapes from docs/protocol.md (server tui_gateway/ws.py + server_requests.py). */
class FramesTest {
    @Test
    fun batchedEventsRequestsAndReplies() {
        val frame = listOf(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"rt1","payload":{"text":"Hel"}}}""",
            "",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","session_id":""}}""",
            """{"jsonrpc":"2.0","id":"srq-1a2b3c4d5e6f","method":"approval","params":{"session_id":"rt1","command":"rm -rf build/"}}""",
            """{"jsonrpc":"2.0","id":7,"result":{"status":"streaming"}}""",
            """{"jsonrpc":"2.0","id":8,"error":{"code":4009,"message":"session busy"}}""",
            """{"jsonrpc":"2.0","id":9,"method":"ignored.int.id.request"}""",
            "not json",
        ).joinToString("\n")

        val out = parseFrames(frame)
        assertEquals(5, out.size)

        val delta = out[0] as HermesEvent
        assertEquals("message.delta", delta.type)
        assertEquals("rt1", delta.sessionId)
        assertEquals("Hel", delta.payload.str("text"))

        val ready = out[1] as HermesEvent
        assertEquals("gateway.ready", ready.type)
        assertTrue(ready.payload.isEmpty())

        val req = out[2] as ServerRequest
        assertEquals("srq-1a2b3c4d5e6f", req.id)
        assertEquals("approval", req.method)
        assertEquals("rm -rf build/", req.params.str("command"))

        val ok = out[3] as RpcReply
        assertEquals(7, ok.id)
        assertNull(ok.error)
        assertEquals("streaming", ok.result.obj()?.str("status"))

        val busy = out[4] as RpcReply
        assertEquals(4009, busy.error?.code)
        assertEquals("session busy", busy.error?.message)
    }
}
