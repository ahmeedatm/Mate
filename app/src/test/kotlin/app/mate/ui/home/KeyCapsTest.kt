package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyCapsTest {
    @Test
    fun `les capuchons sont numerotes sur deux chiffres a partir de 01`() {
        assertEquals("01", KeyCaps.number(0))
        assertEquals("09", KeyCaps.number(8))
        assertEquals("10", KeyCaps.number(9))
        assertEquals("120", KeyCaps.number(119))
    }

    @Test
    fun `seuls les themes a fenetre LCD ont des capuchons`() {
        assertFalse(KeyCaps.applies(0))
        assertTrue(KeyCaps.applies(42))
    }

    @Test
    fun `en Ligne un capuchon prend plus de place qu'une icone seule`() {
        val density = 1f
        assertEquals(8, TileShapes.stripFit(ShapeFamily.ROW, 600, 90, density))
        assertEquals(6, KeyCaps.stripFit(ShapeFamily.ROW, 600, 90, density))
        assertEquals(1, KeyCaps.stripFit(ShapeFamily.ROW, 40, 90, density))
        assertEquals(TileShapes.stripFit(ShapeFamily.COLUMN, 200, 400, density, panel = true), KeyCaps.stripFit(ShapeFamily.COLUMN, 200, 400, density))
        assertEquals(1, KeyCaps.stripFit(ShapeFamily.PILL, 200, 200, density))
    }

    @Test
    fun `la fenetre LCD des Touches ne se pose que sur la grille`() {
        for (size in WidgetSize.entries) assertEquals("$size", TileShapes.usesGrid(size), KeyCaps.lcd(size))
    }
}
