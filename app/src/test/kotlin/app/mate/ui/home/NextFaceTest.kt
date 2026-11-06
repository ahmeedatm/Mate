package app.mate.ui.home

import app.mate.net.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NextFaceTest {
    private val minute = 60_000L
    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 6, 14, 32, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun event(fromMin: Long, toMin: Long) = CalendarEvent("e", "Point", "", now + fromMin * minute, now + toMin * minute, false, joinable = true)

    @Test
    fun `pastille et ligne mettent l'anneau a cote du titre, sans details`() {
        for (size in listOf(WidgetSize.S, WidgetSize.M, WidgetSize.M3, WidgetSize.W)) {
            val face = NextFace.of(size)
            assertTrue(size.name, face.across)
            assertFalse(size.name, face.details)
        }
        assertEquals(0, NextFace.of(WidgetSize.S).following)
        assertEquals(0, NextFace.of(WidgetSize.M).following)
        // 4 × 1 : la place pour « Ensuite … » à droite.
        assertEquals(1, NextFace.of(WidgetSize.W).following)
        assertTrue(NextFace.of(WidgetSize.W).followBeside)
    }

    @Test
    fun `colonne empile l'anneau, le titre et les suivants`() {
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) {
            val face = NextFace.of(size)
            assertFalse(size.name, face.across)
            assertFalse(size.name, face.followBeside)
            assertTrue(size.name, face.following >= 3)
        }
    }

    @Test
    fun `plus grand, plus de details et de suivants`() {
        assertTrue(NextFace.of(WidgetSize.L).details)
        assertEquals(0, NextFace.of(WidgetSize.L).following)
        assertTrue(NextFace.of(WidgetSize.L33).following >= 3)
        assertFalse(NextFace.of(WidgetSize.L33).followBeside)
        // Large et basse : les suivants à côté ; haute : dessous.
        assertTrue(NextFace.of(WidgetSize.XL).followBeside)
        assertFalse(NextFace.of(WidgetSize.XL3).followBeside)
        assertFalse(NextFace.of(WidgetSize.T).across)
        assertTrue(NextFace.of(WidgetSize.FULL).following > NextFace.of(WidgetSize.L33).following)
    }

    @Test
    fun `dans moins d'une heure, l'anneau montre le temps qui reste sur l'heure`() {
        assertEquals(15f / 60f, NextRing.fraction(event(15, 45), now), 0.001f)
        assertEquals(59f / 60f, NextRing.fraction(event(59, 90), now), 0.001f)
        // Une heure ou plus : « plus tard », pas de compte à rebours.
        assertEquals(0f, NextRing.fraction(event(60, 90), now), 0f)
    }

    @Test
    fun `en cours, l'anneau montre le temps passe de la reunion`() {
        assertEquals(0.25f, NextRing.fraction(event(-15, 45), now), 0.001f)
    }

    @Test
    fun `plus tard, ou rien, l'anneau est vide`() {
        assertEquals(0f, NextRing.fraction(event(120, 180), now), 0f)
        assertEquals(0f, NextRing.fraction(null, now), 0f)
    }
}
