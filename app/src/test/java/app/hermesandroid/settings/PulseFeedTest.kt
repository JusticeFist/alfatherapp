package app.hermesandroid.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseFeedTest {
    @Test
    fun readsCardsAsPulseControlFeedSendsThem() {
        val feed = Json.parseToJsonElement("""{"cards":[
            {"id":7,"kind":"noticed","item_id":12,"title":"t","news":"Promesa pendiente","chat_title":"Compras","topic":"promise",
             "created_at":1761300000.5,"rating":"up","dismissed_at":null,"done_at":1761300100.0},
            {"id":6,"kind":"noticed","item_id":null,"title":"Solo título","news":"","chat_title":"Pulse","topic":"date",
             "created_at":1761200000,"rating":null,"dismissed_at":1761200500,"done_at":null},
            {"id":5,"kind":"report","item_id":null,"news":"1. [date] algo","created_at":1761100000},
            {"kind":"noticed"}
        ],"unseen":2}""") as JsonObject
        val (a, b, r) = parsePulseFeed(feed).also { assertEquals(3, it.size) }

        assertEquals("Promesa pendiente", a.text)
        assertTrue(a.linked && a.doable && a.done)
        assertEquals("up", a.rating)
        assertEquals(1761300000L, a.createdAt)

        assertEquals("Solo título", b.text)
        assertFalse(b.linked || b.doable || b.done)
        assertTrue(b.ratable && b.dismissed)
        assertNull(b.rating)

        assertFalse(r.ratable || r.doable)
    }

    @Test
    fun pendingIsRateableAndUntouched() {
        fun card(id: Int, kind: String = "noticed", rating: String? = null, done: Boolean = false, dismissed: Boolean = false) =
            PulseCard(id, kind, "promise", "t", "", 0, true, rating, done, dismissed)
        val cards = listOf(card(1), card(2, rating = "up"), card(3, done = true), card(4, dismissed = true), card(5, kind = "report"), card(6, kind = "followup"))
        assertEquals(listOf(1, 6), cards.pending().map { it.id })
    }

    @Test
    fun emptyOrMissingFeed() {
        assertEquals(emptyList<PulseCard>(), parsePulseFeed(null))
        assertEquals(emptyList<PulseCard>(), parsePulseFeed(Json.parseToJsonElement("""{"cards":[]}""") as JsonObject))
    }
}
