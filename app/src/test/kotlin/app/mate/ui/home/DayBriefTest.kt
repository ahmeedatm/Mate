package app.mate.ui.home

import app.mate.focus.FocusTimer
import app.mate.net.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayBriefTest {

    private fun at(hour: Int, minute: Int, days: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 29, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    private fun event(title: String, start: Long, end: Long, allDay: Boolean = false, joinable: Boolean = false) =
        CalendarEvent(title, title, "", start, end, allDay, joinable)

    @Test
    fun `salutation selon l'heure, bonjour jusqu'a midi, bon apres-midi jusqu'a 18 h, bonsoir ensuite`() {
        assertEquals(DayBrief.Greeting.MORNING, DayBrief.greeting(5))
        assertEquals(DayBrief.Greeting.MORNING, DayBrief.greeting(11))
        assertEquals(DayBrief.Greeting.AFTERNOON, DayBrief.greeting(12))
        assertEquals(DayBrief.Greeting.AFTERNOON, DayBrief.greeting(17))
        assertEquals(DayBrief.Greeting.EVENING, DayBrief.greeting(18))
        assertEquals(DayBrief.Greeting.EVENING, DayBrief.greeting(2))
    }

    @Test
    fun `une reunion plus tard aujourd'hui ou en cours passe en grand`() {
        val point = event("Point équipe", at(16, 50), at(17, 20))
        assertEquals(DayBrief.Focus.Meeting(point, NextLine.InMinutes("Point équipe", 12)), DayBrief.focus(listOf(point), at(16, 38)))
        assertEquals(DayBrief.Focus.Meeting(point, NextLine.At("Point équipe", at(16, 50))), DayBrief.focus(listOf(point), at(14, 0)))
        assertEquals(DayBrief.Focus.Meeting(point, NextLine.Now("Point équipe", at(17, 20))), DayBrief.focus(listOf(point), at(17, 0)))
    }

    @Test
    fun `aucune reunion aujourd'hui, sans rien apres`() {
        assertEquals(DayBrief.Focus.Free(hadMeetings = false, later = null), DayBrief.focus(emptyList(), at(10, 0)))
    }

    @Test
    fun `plus de reunion aujourd'hui, celles du matin sont finies`() {
        val daily = event("Daily", at(9, 30), at(10, 0))
        assertEquals(DayBrief.Focus.Free(hadMeetings = true, later = null), DayBrief.focus(listOf(daily), at(16, 0)))
    }

    @Test
    fun `rien d'autre aujourd'hui, la prochaine demain est donnee en plus`() {
        val daily = event("Daily", at(9, 30, 1), at(10, 0, 1))
        assertEquals(
            DayBrief.Focus.Free(hadMeetings = false, later = NextLine.Tomorrow("Daily", at(9, 30, 1))),
            DayBrief.focus(listOf(daily), at(16, 0)),
        )
    }

    @Test
    fun `un evenement sur la journee entiere n'est ni une reunion ni une reunion passee`() {
        val off = event("Férié", at(0, 0), at(0, 0, 1), allDay = true)
        assertEquals(DayBrief.Focus.Free(hadMeetings = false, later = null), DayBrief.focus(listOf(off), at(10, 0)))
    }

    @Test
    fun `Rejoindre, une reunion en ligne a partir de 15 min avant, ou tout de suite si elle a ete choisie`() {
        val online = event("Point", at(16, 50), at(17, 20), joinable = true)
        assertTrue(DayBrief.canJoin(online, at(16, 35), chosen = false, meetings = true))
        assertFalse(DayBrief.canJoin(online, at(16, 30), chosen = false, meetings = true))
        assertTrue(DayBrief.canJoin(online, at(16, 30), chosen = true, meetings = true))
        assertFalse(DayBrief.canJoin(online, at(16, 45), chosen = false, meetings = false))
        assertFalse(DayBrief.canJoin(online.copy(joinable = false), at(16, 45), chosen = false, meetings = true))
    }

    private fun timer(phase: FocusTimer.Phase, durationMin: Int, remainingMs: Long, running: Boolean) =
        FocusTimer.State(phase, durationMin * 60_000L, remainingMs, running, 0)

    @Test
    fun `pastille Focus, au repos la duree, en cours ou en pause les minutes restantes arrondies au-dessus`() {
        assertEquals(DayBrief.FocusPill(DayBrief.FocusPill.Kind.IDLE, 25), DayBrief.focusPill(timer(FocusTimer.Phase.FOCUS, 25, 25 * 60_000L, false)))
        assertEquals(DayBrief.FocusPill(DayBrief.FocusPill.Kind.RUNNING, 18), DayBrief.focusPill(timer(FocusTimer.Phase.FOCUS, 25, 17 * 60_000L + 1, true)))
        assertEquals(DayBrief.FocusPill(DayBrief.FocusPill.Kind.PAUSED, 10), DayBrief.focusPill(timer(FocusTimer.Phase.FOCUS, 25, 10 * 60_000L, false)))
        assertEquals(DayBrief.FocusPill(DayBrief.FocusPill.Kind.BREAK, 4), DayBrief.focusPill(timer(FocusTimer.Phase.BREAK, 5, 3 * 60_000L + 30_000, true)))
    }

    @Test
    fun `pastille Focus, la derniere seconde compte encore une minute`() {
        assertEquals(1, DayBrief.focusPill(timer(FocusTimer.Phase.FOCUS, 25, 1_000L, true)).minutes)
    }
}
