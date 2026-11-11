package app.hermesandroid.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = app.hermesandroid.HermesApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ComposerLabelsTest {
    @Test
    fun modelLabelNamesClaudeModelsLikeClaudesApp() {
        assertEquals("Opus 5.5", modelLabel("claude-opus-5-5"))
        assertEquals("Sonnet 5.5", modelLabel("anthropic/claude-sonnet-5-5"))
        assertEquals("Haiku 4.5", modelLabel("claude-haiku-4-5-20251001"))
        assertEquals("Fable 5.1", modelLabel("claude-fable-5.1"))
        assertEquals("Opus 5", modelLabel("claude-opus-5"))
        assertEquals("Opus 5", modelLabel("claude-opus-5-20260101"))
        assertEquals("gpt-5.4", modelLabel("openai/gpt-5.4"))
        assertEquals("Model", modelLabel(null))
    }

    @Test
    fun contextPercentIsAPercentNotAFraction() {
        assertEquals(1, contextPercentOf(buildJsonObject { put("context_percent", 1) }))
        assertEquals(42, contextPercentOf(buildJsonObject { put("context_percent", 42) }))
        assertEquals(25, contextPercentOf(buildJsonObject { put("context_used", 50_000); put("context_max", 200_000) }))
        assertNull(contextPercentOf(buildJsonObject { put("context_used", 10) }))
    }
}
