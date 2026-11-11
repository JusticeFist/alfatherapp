package app.hermesandroid.net

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadersTest {
    @Test
    fun keepsValidHeadersAndDropsReservedOnes() {
        val text = """
            X-Pangolin-Token: abc123
            Cookie: stolen=1
            Authorization: Bearer nope
            Sec-Fetch-Mode: cors
            bad header: value
            x-pangolin-token: second spelling ignored
            X-Empty:
            X-Team: hermes
        """.trimIndent()
        assertEquals(listOf("X-Pangolin-Token" to "abc123", "X-Team" to "hermes"), parseHeaders(text))
    }
}
