package app.mate.ui.home

import app.mate.net.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AgendaFaceTest {
    private val hour = 3_600_000L
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 6, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Rendez-vous [days] jours après aujourd'hui, de [from] à [to] heures. */
    private fun event(title: String, days: Int, from: Double, to: Double, allDay: Boolean = false): CalendarEvent {
        val day = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, days)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        return CalendarEvent(title, title, "", day + (from * hour).toLong(), day + (to * hour).toLong(), allDay, joinable = false)
    }

    @Test
    fun `pastille et ligne montrent la journee en ligne de temps, les titres des 3 x 1`() {
        assertEquals(AgendaFace.Mode.TRACK, AgendaFace.of(WidgetSize.S).mode)
        assertFalse(AgendaFace.of(WidgetSize.S).titles)
        assertFalse(AgendaFace.of(WidgetSize.M).titles)
        assertTrue(AgendaFace.of(WidgetSize.M3).titles)
        assertTrue(AgendaFace.of(WidgetSize.W).titles)
    }

    @Test
    fun `colonne en ligne de temps debout, avec les titres`() {
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) {
            assertEquals(size.name, AgendaFace.Mode.TRACK_DOWN, AgendaFace.of(size).mode)
            assertTrue(size.name, AgendaFace.of(size).titles)
        }
    }

    @Test
    fun `bloc et grand etroit en liste, grand large et plein ecran en semaine`() {
        for (size in listOf(WidgetSize.L, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33, WidgetSize.T, WidgetSize.T3)) {
            assertEquals(size.name, AgendaFace.Mode.LIST, AgendaFace.of(size).mode)
        }
        for (size in listOf(WidgetSize.XL, WidgetSize.XL3, WidgetSize.FULL)) {
            assertEquals(size.name, AgendaFace.Mode.WEEK, AgendaFace.of(size).mode)
        }
    }

    @Test
    fun `restants = les rendez-vous horaires d'aujourd'hui pas encore finis`() {
        val events = listOf(
            event("passé", 0, 9.0, 10.0),
            event("en cours", 0, 14.0, 15.0),
            event("tard", 0, 18.0, 19.0),
            event("journée", 0, 0.0, 24.0, allDay = true),
            event("demain", 1, 9.0, 10.0),
        )
        assertEquals(2, AgendaPlan.remaining(events, now))
    }

    @Test
    fun `la semaine range chaque rendez-vous dans son jour, sept colonnes`() {
        val events = listOf(
            event("hier et aujourd'hui", -1, 0.0, 48.0, allDay = true),
            event("auj", 0, 9.0, 10.0),
            event("j2", 2, 9.0, 10.0),
            event("j6", 6, 9.0, 10.0),
            event("j7", 7, 9.0, 10.0),
        )
        val week = AgendaPlan.week(events, now)
        assertEquals(7, week.size)
        assertEquals(listOf("hier et aujourd'hui", "auj"), week[0].map { it.title })
        assertEquals(listOf("j2"), week[2].map { it.title })
        assertEquals(listOf("j6"), week[6].map { it.title })
        assertTrue(week[1].isEmpty())
    }
}
