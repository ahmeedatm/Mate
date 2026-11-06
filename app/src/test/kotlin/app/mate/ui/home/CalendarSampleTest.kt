package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSampleTest {
    private val now = 1_800_000_000_000L
    private val events = CalendarSample.events(now) { "t$it" }

    @Test
    fun `les rendez-vous d'exemple sont dans l'ordre du temps`() {
        assertEquals(events.sortedBy { it.startMs }, events)
    }

    @Test
    fun `le prochain est une reunion a rejoindre dans le quart d'heure`() {
        val next = nextEvent(events, now)!!
        assertTrue(next.joinable)
        assertTrue(next.startMs - now in 1..15 * 60_000L)
    }

    @Test
    fun `il y a un passe, des suivants et d'autres jours`() {
        assertTrue(events.any { it.endMs <= now })
        assertTrue(events.count { it.startMs > now } >= 4)
        assertTrue(events.any { it.startMs - now > 24 * 3_600_000L })
    }
}
