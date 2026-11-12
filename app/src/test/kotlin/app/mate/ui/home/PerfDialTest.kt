package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfDialTest {
    @Test
    fun `l'aiguille va de -90 degres a 0 % a +90 degres a 100 %, bornee`() {
        assertEquals(-90f, dialAngle(0), 0.01f)
        assertEquals(0f, dialAngle(50), 0.01f)
        assertEquals(90f, dialAngle(100), 0.01f)
        assertEquals(-90f, dialAngle(-5), 0.01f)
        assertEquals(90f, dialAngle(140), 0.01f)
    }

    @Test
    fun `la zone rouge du cadran commence au seuil d'alerte`() {
        assertEquals(PerfLevel.NORMAL, usageLevel(DIAL_ALERT_FROM - 1))
        assertEquals(PerfLevel.WARN, usageLevel(DIAL_ALERT_FROM))
    }

    @Test
    fun `Colonne empile les cadrans, Ligne met les infos a droite`() {
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) assertTrue("$size", perfDialPlan(size).stacked)
        for (size in listOf(WidgetSize.M, WidgetSize.M3, WidgetSize.W)) {
            assertTrue("$size", perfDialPlan(size).extrasBeside)
            assertFalse("$size", perfDialPlan(size).stacked)
        }
        assertFalse(perfDialPlan(WidgetSize.L).extrasBeside)
    }

    @Test
    fun `Grand et Plein ecran gardent la courbe et la liste des applis`() {
        for (size in listOf(WidgetSize.T, WidgetSize.T3, WidgetSize.XL, WidgetSize.XL3, WidgetSize.FULL)) {
            assertTrue("$size", perfDialPlan(size).curve)
            assertTrue("$size", perfDialPlan(size).apps)
        }
        for (size in listOf(WidgetSize.S, WidgetSize.M, WidgetSize.L, WidgetSize.V, WidgetSize.L33)) {
            assertFalse("$size", perfDialPlan(size).curve)
            assertFalse("$size", perfDialPlan(size).apps)
        }
    }
}
