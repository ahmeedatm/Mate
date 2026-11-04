package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePatternTest {
    private fun PatternTile.at(x: Int, y: Int) = pixels[y * w + x]

    @Test
    fun `aucun motif ni forme d'accent, pas de tuile`() {
        assertNull(ThemePattern.tile(0, 2f))
        assertNull(ThemePattern.tile(2, 2f))
    }

    @Test
    fun `registre, un filet en bas d'une tuile de 32 dp`() {
        val tile = ThemePattern.tile(1, 1.5f)!!
        assertEquals(48, tile.h)
        assertTrue((0 until tile.w).all { tile.at(it, tile.h - 1) == -1 })
        assertEquals(tile.w, tile.pixels.count { it != 0 })
    }

    @Test
    fun `tableau, un filet a droite et en bas d'une cellule de 48 dp`() {
        val tile = ThemePattern.tile(3, 1f)!!
        assertEquals(48, tile.w)
        assertEquals(48, tile.h)
        assertEquals(48 + 48 - 1, tile.pixels.count { it != 0 })
        assertEquals(-1, tile.at(47, 10))
        assertEquals(-1, tile.at(10, 47))
        assertEquals(0, tile.at(10, 10))
    }

    @Test
    fun `terminal, une ligne de scan tous les 4 pixels, quelle que soit la densite`() {
        val tile = ThemePattern.tile(4, 2.5f)!!
        assertEquals(4, tile.h)
        assertEquals(tile.w, tile.pixels.count { it != 0 })
        assertEquals(-1, tile.at(0, 3))
    }
}
