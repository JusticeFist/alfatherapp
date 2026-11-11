package app.hermesandroid.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TildeTest {
    @Test fun loneTildesStayTildes() {
        assertEquals("""a \~$1.35, b \~$0.05 ~~old~~ `~/.hermes`""", escapeTildes("a ~$1.35, b ~$0.05 ~~old~~ `~/.hermes`"))
        assertEquals("```\ncd ~\n```\n\\~2 min", escapeTildes("```\ncd ~\n```\n~2 min"))
    }
}
