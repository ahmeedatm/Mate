package app.mate.ui.home

import app.mate.R
import org.junit.Assert.assertEquals
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
        assertEquals(R.attr.c_accent, rankHue(27))
    }
}
