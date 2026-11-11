package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LcdStyleTest {
    @Test
    fun `six widgets prennent le style LCD, les autres restent des tuiles`() {
        val lcd = WidgetType.entries.filter { LcdStyle.applies(it, overlay = 1) }
        assertEquals(
            setOf(WidgetType.CLOCK, WidgetType.NEXT, WidgetType.AGENDA, WidgetType.FOCUS, WidgetType.WATER, WidgetType.WEATHER),
            lcd.toSet(),
        )
    }

    @Test
    fun `sans style dans le theme, aucun widget n'est touche`() {
        assertTrue(WidgetType.entries.none { LcdStyle.applies(it, overlay = 0) })
    }

    @Test
    fun `les widgets nus et ceux de l'ordinateur sans donnee gardent leur look`() {
        assertFalse(LcdStyle.applies(WidgetType.HERO, overlay = 1))
        assertFalse(LcdStyle.applies(WidgetType.KEYS, overlay = 1))
    }
}
