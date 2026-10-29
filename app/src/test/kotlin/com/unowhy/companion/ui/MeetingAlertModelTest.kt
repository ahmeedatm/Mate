package com.unowhy.companion.ui

import com.unowhy.companion.FakeContext
import com.unowhy.companion.Prefs
import com.unowhy.companion.net.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingAlertModelTest {
    private val now = 1_000_000_000_000L
    private val min = 60_000L

    private fun event(startIn: Long, id: String = "e", title: String = "Point client", allDay: Boolean = false) =
        CalendarEvent(id, title, "", now + startIn, now + startIn + 30 * min, allDay, false)

    @Test
    fun `l'alerte part 5 minutes avant le debut, pas avant`() {
        assertNull(MeetingAlertModel.due(listOf(event(6 * min)), now, emptySet()))
        assertNull(MeetingAlertModel.due(listOf(event(5 * min + 1)), now, emptySet()))
        assertEquals("e", MeetingAlertModel.due(listOf(event(5 * min)), now, emptySet())?.id)
        assertEquals("e", MeetingAlertModel.due(listOf(event(1)), now, emptySet())?.id)
    }

    @Test
    fun `pas d'alerte pour une reunion commencee ou passee`() {
        assertNull(MeetingAlertModel.due(listOf(event(0)), now, emptySet()))
        assertNull(MeetingAlertModel.due(listOf(event(-2 * min)), now, emptySet()))
        assertNull(MeetingAlertModel.due(listOf(event(-60 * min)), now, emptySet()))
    }

    @Test
    fun `pas d'alerte pour un evenement sur la journee entiere`() {
        assertNull(MeetingAlertModel.due(listOf(event(3 * min, allDay = true)), now, emptySet()))
    }

    @Test
    fun `une reunion n'est annoncee qu'une fois`() {
        val meeting = event(4 * min)
        val seen = MeetingAlertModel.remember(emptySet(), MeetingAlertModel.keyOf(meeting), now)
        assertNull(MeetingAlertModel.due(listOf(meeting), now, seen))
        // Même id déplacé ou renommé : c'est une autre réunion.
        assertEquals("e", MeetingAlertModel.due(listOf(event(4 * min + 60_000)), now, seen)?.id)
        assertEquals("e", MeetingAlertModel.due(listOf(event(4 * min, title = "Autre")), now, seen)?.id)
    }

    @Test
    fun `la plus proche passe d'abord, une reunion deja annoncee laisse la suivante`() {
        val first = event(2 * min, id = "a", title = "A")
        val second = event(4 * min, id = "b", title = "B")
        assertEquals("a", MeetingAlertModel.due(listOf(second, first), now, emptySet())?.id)
        val seen = setOf(MeetingAlertModel.keyOf(first))
        assertEquals("b", MeetingAlertModel.due(listOf(second, first), now, seen)?.id)
    }

    @Test
    fun `les reunions finies depuis plus d'un jour sortent de la memoire`() {
        val old = "${now - 2 * 86_400_000L}|x|Vieille"
        val recent = "${now - 3_600_000L}|y|Récente"
        assertEquals(setOf(recent, "k"), MeetingAlertModel.remember(setOf(old, recent), "k", now))
    }

    @Test
    fun `pas d'alerte en appel, dans la camera ni pendant une saisie`() {
        assertFalse(MeetingAlertModel.blocked(inCall = false, camera = false, typing = false))
        assertTrue(MeetingAlertModel.blocked(inCall = true, camera = false, typing = false))
        assertTrue(MeetingAlertModel.blocked(inCall = false, camera = true, typing = false))
        assertTrue(MeetingAlertModel.blocked(inCall = false, camera = false, typing = true))
    }

    @Test
    fun `le compte a rebours passe au rouge sous une minute`() {
        assertFalse(MeetingAlertModel.urgent(5 * min))
        assertFalse(MeetingAlertModel.urgent(60_000))
        assertTrue(MeetingAlertModel.urgent(59_999))
        assertTrue(MeetingAlertModel.urgent(0))
    }

    @Test
    fun `texte du compte a rebours en mm ss, arrondi au-dessus`() {
        assertEquals("05:00", MeetingAlertModel.countdown(5 * min))
        assertEquals("04:59", MeetingAlertModel.countdown(5 * min - 1000))
        assertEquals("05:00", MeetingAlertModel.countdown(5 * min - 1))
        assertEquals("01:00", MeetingAlertModel.countdown(60_000))
        assertEquals("00:59", MeetingAlertModel.countdown(59_000))
        assertEquals("00:01", MeetingAlertModel.countdown(1))
        assertEquals("00:00", MeetingAlertModel.countdown(0))
        assertEquals("00:00", MeetingAlertModel.countdown(-5))
        assertEquals(0L, MeetingAlertModel.remainingMs(event(-min), now))
        assertEquals(2 * min, MeetingAlertModel.remainingMs(event(2 * min), now))
    }

    @Test
    fun `minutes restantes pour l'annonce parlee`() {
        assertEquals(5, MeetingAlertModel.minutesLeft(5 * min))
        assertEquals(5, MeetingAlertModel.minutesLeft(4 * min + 1))
        assertEquals(1, MeetingAlertModel.minutesLeft(1))
        assertEquals(0, MeetingAlertModel.minutesLeft(0))
    }

    @Test
    fun `reglage coupe par defaut, reunions annoncees gardees`() {
        val context = FakeContext()
        assertFalse(Prefs.meetingAlert(context))
        Prefs.setMeetingAlert(context, true)
        assertTrue(Prefs.meetingAlert(context))
        Prefs.setMeetingAlertSeen(context, setOf("k"))
        assertEquals(setOf("k"), Prefs.meetingAlertSeen(context))
    }
}
