package app.mate.ui.home

import app.mate.net.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DayLineFaceTest {
    private val hour = 3_600_000L
    private val day = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 6, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun h(value: Double) = day + (value * hour).toLong()
    private fun event(title: String, from: Double, to: Double, allDay: Boolean = false) = CalendarEvent(title, title, "", h(from), h(to), allDay, joinable = false)

    @Test
    fun `en ligne, la ligne animee d'aujourd'hui, ailleurs l'en-tete maintenant`() {
        for (size in listOf(WidgetSize.M, WidgetSize.M3, WidgetSize.W)) {
            assertTrue(size.name, DayLineFace.of(size).band)
        }
        for (size in WidgetSize.entries.filter { it.family != ShapeFamily.ROW }) {
            assertFalse(size.name, DayLineFace.of(size).band)
        }
    }

    @Test
    fun `pastille = petite ligne sans titres, colonne et grand etroit = ligne debout, bloc et grand large = ligne couchee`() {
        assertEquals(DayLineFace.Line.MINI, DayLineFace.of(WidgetSize.S).line)
        for (size in listOf(WidgetSize.V, WidgetSize.V4, WidgetSize.T, WidgetSize.T3, WidgetSize.FULL)) {
            assertEquals(size.name, DayLineFace.Line.DOWN, DayLineFace.of(size).line)
        }
        for (size in listOf(WidgetSize.L, WidgetSize.L3, WidgetSize.XL, WidgetSize.XL3)) {
            assertEquals(size.name, DayLineFace.Line.ACROSS, DayLineFace.of(size).line)
        }
    }

    @Test
    fun `maintenant, en rendez-vous jusqu'a sa fin`() {
        val now = DayNow.of(listOf(event("Point", 10.0, 11.0), event("Revue", 14.0, 15.0)), h(10.5))
        assertEquals(DayNow.Busy("Point", h(11.0)), now)
    }

    @Test
    fun `maintenant, libre jusqu'au prochain du jour, ou pour le reste de la journee`() {
        val events = listOf(event("Point", 10.0, 11.0), event("Revue", 14.0, 15.0), event("Demain", 33.0, 34.0))
        assertEquals(DayNow.Free(h(14.0)), DayNow.of(events, h(12.0)))
        assertNull((DayNow.of(events, h(16.0)) as DayNow.Free).untilMs)
    }

    @Test
    fun `une journee entiere ne rend pas occupe`() {
        val now = DayNow.of(listOf(event("Congé", 0.0, 24.0, allDay = true)), h(12.0))
        assertEquals(DayNow.Free(null), now)
    }
}
