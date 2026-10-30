package com.unowhy.unomate.ui.home

import com.unowhy.unomate.net.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayLineModelTest {
    private val hour = 3_600_000L
    private val today = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 26, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    /** 8 h : début de la ligne. */
    private val start = DayLineModel.dayStart(today)

    /** `h` heures de la journée (8.0 = 8 h, 14.5 = 14 h 30). */
    private fun h(h: Double) = start + ((h - 8) * hour).toLong()

    private fun event(title: String, from: Double, to: Double, allDay: Boolean = false) =
        CalendarEvent(title, title, "", h(from), h(to), allDay, joinable = false)

    @Test
    fun `la ligne va de 8 h a 20 h`() {
        assertEquals(0f, DayLineModel.fraction(h(8.0), start), 0f)
        assertEquals(0.5f, DayLineModel.fraction(h(14.0), start), 0.0001f)
        assertEquals(1f, DayLineModel.fraction(h(20.0), start), 0f)
    }

    @Test
    fun `avant 8 h et apres 20 h la fraction est bornee`() {
        assertEquals(0f, DayLineModel.fraction(h(7.0), start), 0f)
        assertEquals(1f, DayLineModel.fraction(h(21.0), start), 0f)
    }

    @Test
    fun `le debut de ligne est 8 h du meme jour`() {
        val c = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(8, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MINUTE))
        assertEquals(26, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `maintenant n'est dans la fenetre qu'entre 8 h et 20 h`() {
        assertTrue(DayLineModel.inWindow(h(14.0), start))
        assertTrue(DayLineModel.inWindow(h(8.0), start))
        assertFalse(DayLineModel.inWindow(h(7.5), start))
        assertFalse(DayLineModel.inWindow(h(20.5), start))
    }

    @Test
    fun `un evenement de 7 h 30 a 8 h 30 est rogne a la fenetre`() {
        val bar = DayLineModel.bars(listOf(event("Tôt", 7.5, 8.5)), start, h(14.0)).single()
        assertEquals(0f, bar.from, 0f)
        assertEquals(0.5f / 12, bar.to, 0.0001f)
    }

    @Test
    fun `journee entiere et hors fenetre exclus`() {
        val events = listOf(event("Férié", 0.0, 24.0, allDay = true), event("Matin", 5.0, 7.0), event("Soir", 21.0, 22.0), event("Daily", 9.5, 10.5))
        assertEquals(listOf("Daily"), DayLineModel.bars(events, start, h(14.0)).map { it.event.title })
    }

    private val day = listOf(
        event("Daily", 9.5, 10.5), event("Déjeuner", 12.0, 13.25), event("Démo", 14.5, 15.5), event("Point", 17.0, 18.0),
    )

    @Test
    fun `passe, prochain, suite`() {
        val bars = DayLineModel.bars(day, start, h(14.0 + 7 / 60.0))
        assertEquals(
            listOf(DayLineModel.State.PAST, DayLineModel.State.PAST, DayLineModel.State.NEXT, DayLineModel.State.LATER),
            bars.map { it.state },
        )
    }

    @Test
    fun `un evenement en cours est le prochain`() {
        val bars = DayLineModel.bars(day, start, h(15.0))
        assertEquals("Démo", bars.single { it.state == DayLineModel.State.NEXT }.event.title)
    }

    @Test
    fun `apres le dernier evenement tout est passe`() {
        val bars = DayLineModel.bars(day, start, h(19.0))
        assertTrue(bars.all { it.state == DayLineModel.State.PAST })
    }

    @Test
    fun `barres dans l'ordre du temps meme si l'agenda ne l'est pas`() {
        val bars = DayLineModel.bars(day.reversed(), start, h(14.0))
        assertEquals(listOf("Daily", "Déjeuner", "Démo", "Point"), bars.map { it.event.title })
    }

    @Test
    fun `un titre trop long s'arrete avant la barre suivante`() {
        val bars = DayLineModel.bars(listOf(event("A", 9.0, 10.0), event("B", 10.5, 11.5)), start, h(14.0))
        val room = DayLineModel.labelRoom(bars, 1200f, 8f)
        // A part de 1/12 et B de 2,5/12 : 1,5/12 de 1200 = 150, moins la marge.
        assertEquals(142f, room[0], 0.01f)
        // B va jusqu'au bord droit : de 250 a 1200.
        assertEquals(1200f - 250f, room[1], 0.01f)
    }

    @Test
    fun `evenements superposes, jamais de place negative`() {
        val bars = DayLineModel.bars(listOf(event("A", 9.0, 11.0), event("B", 9.0, 10.0)), start, h(14.0))
        assertTrue(DayLineModel.labelRoom(bars, 1200f, 8f).all { it >= 0f })
    }
}
