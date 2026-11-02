package app.mate.ui.home

import app.mate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorantRankTest {
    @Test
    fun eachDivisionHasItsHue() {
        assertEquals(R.attr.c_text_faint, rankHue(null))
        assertEquals(R.attr.c_text_faint, rankHue(0))
        assertEquals(R.attr.c_text_dim, rankHue(3))
        assertEquals(R.attr.c_hue_orange, rankHue(6))
        assertEquals(R.attr.c_text, rankHue(11))
        assertEquals(R.attr.c_hue_yellow, rankHue(12))
        assertEquals(R.attr.c_hue_cyan, rankHue(15))
        assertEquals(R.attr.c_hue_violet, rankHue(20))
        assertEquals(R.attr.c_hue_green, rankHue(21))
        assertEquals(R.attr.c_hue_red, rankHue(26))
        // Pas l'accent : dans Console, il a la valeur du vert d'Ascendant.
        assertEquals(R.attr.c_hue_pink, rankHue(27))
    }

    /** Non classé en anneau creux : l'état ne repose pas sur la seule teinte (Fer, Argent et non classé sont gris). */
    @Test
    fun unrankedIsHollow() {
        assertTrue(rankHollow(null))
        assertTrue(rankHollow(2))
        assertFalse(rankHollow(3))
        assertFalse(rankHollow(27))
    }
}
