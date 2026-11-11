package app.hermesandroid.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentsTest {
    @Test
    fun splitsSpecialBlocksAndKeepsCodeFences() {
        val reply = """
            Intro **bold**
            ```kotlin
            val x = "MEDIA:/not/a/tag"
            $$
            ```
            $$
            E = mc^2
            $$
            ```mermaid
            graph TD; A-->B
            ```
            MEDIA:/home/me/out/chart.png
            [informe](MEDIA:/tmp/r.pdf)
            Mira esto ::preview{file="/tmp/page.html"}
            ${'$'}${'$'}x^2${'$'}${'$'}
            Fin
        """.trimIndent()

        val s = segments(reply)
        assertEquals(
            listOf(
                Seg.Md("Intro **bold**\n```kotlin\nval x = \"MEDIA:/not/a/tag\"\n$$\n```"),
                Seg.Math("E = mc^2"),
                Seg.Mermaid("graph TD; A-->B"),
                Seg.Media("/home/me/out/chart.png"),
                Seg.Media("/tmp/r.pdf"),
                Seg.Md("Mira esto"),
                Seg.Preview("/tmp/page.html"),
                Seg.Math("x^2"),
                Seg.Md("Fin"),
            ),
            s,
        )
    }

    @Test
    fun proseMentioningMediaStaysText() {
        assertEquals(listOf(Seg.Md("MEDIA: is where results go")), segments("MEDIA: is where results go"))
    }
}
