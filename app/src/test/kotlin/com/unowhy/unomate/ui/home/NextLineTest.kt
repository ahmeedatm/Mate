package com.unowhy.unomate.ui.home

import com.unowhy.unomate.net.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class NextLineTest {

    private fun at(hour: Int, minute: Int, days: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 26, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis

    private fun event(title: String, start: Long, end: Long, allDay: Boolean = false) =
        CalendarEvent(title, title, "", start, end, allDay, joinable = false)

    @Test
    fun `dans 23 minutes`() {
        val line = nextLine(listOf(event("Démo", at(14, 30), at(15, 30))), at(14, 7))
        assertEquals(NextLine.InMinutes("Démo", 23), line)
    }

    @Test
    fun `dans 59 minutes encore en minutes`() {
        assertEquals(NextLine.InMinutes("Démo", 59), nextLine(listOf(event("Démo", at(17, 0), at(18, 0))), at(16, 1)))
    }

    @Test
    fun `dans 60 minutes devient une heure`() {
        assertEquals(NextLine.At("Démo", at(17, 0)), nextLine(listOf(event("Démo", at(17, 0), at(18, 0))), at(16, 0)))
    }

    @Test
    fun `evenement en cours`() {
        assertEquals(NextLine.Now("Démo", at(15, 30)), nextLine(listOf(event("Démo", at(14, 30), at(15, 30))), at(14, 40)))
    }

    @Test
    fun `demain`() {
        assertEquals(NextLine.Tomorrow("Daily", at(9, 30, 1)), nextLine(listOf(event("Daily", at(9, 30, 1), at(10, 0, 1))), at(14, 7)))
    }

    @Test
    fun `dans plusieurs jours`() {
        assertEquals(NextLine.Day("Revue", at(9, 30, 3), 3), nextLine(listOf(event("Revue", at(9, 30, 3), at(10, 0, 3))), at(14, 7)))
    }

    @Test
    fun `les evenements sur la journee sont ignores`() {
        val events = listOf(event("Férié", at(0, 0), at(23, 59), allDay = true), event("Démo", at(14, 30), at(15, 30)))
        assertEquals(NextLine.InMinutes("Démo", 23), nextLine(events, at(14, 7)))
    }

    @Test
    fun `un evenement termine est ignore`() {
        val events = listOf(event("Daily", at(9, 30), at(10, 0)), event("Point", at(17, 0), at(18, 0)))
        assertEquals(NextLine.At("Point", at(17, 0)), nextLine(events, at(14, 7)))
    }

    @Test
    fun `la reunion choisie passe devant`() {
        val events = listOf(event("Démo", at(14, 30), at(15, 30)), event("Point", at(17, 0), at(18, 0)))
        assertEquals(NextLine.At("Point", at(17, 0)), nextLine(events, at(14, 7), chosenId = "Point"))
    }

    @Test
    fun `liste vide ou tout termine`() {
        assertNull(nextLine(emptyList(), at(14, 7)))
        assertNull(nextLine(listOf(event("Daily", at(9, 30), at(10, 0))), at(14, 7)))
    }
}
