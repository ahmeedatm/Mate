package app.mate.ui.home

import app.mate.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorantTableTest {
    @Test
    fun smallShapesShowOnlyMyRankAndTheScore() {
        for (size in WidgetSize.entries) {
            val small = size.family == ShapeFamily.PILL || size.family == ShapeFamily.ROW || size.family == ShapeFamily.COLUMN
            assertEquals(size.name, small, ValorantShape.of(size).summary)
        }
    }

    @Test
    fun statColumnsAndSidesFollowWidthAndHeight() {
        assertEquals(ValorantShape(summary = false, detail = true, side = true), ValorantShape.of(WidgetSize.XL))
        assertEquals(ValorantShape(summary = false, detail = true, side = true), ValorantShape.of(WidgetSize.FULL))
        // 3 × 2 : trop bas pour dix lignes l'une sous l'autre, les équipes côte à côte avec l'essentiel.
        assertEquals(ValorantShape(summary = false, detail = false, side = true), ValorantShape.of(WidgetSize.L3))
        assertEquals(ValorantShape(summary = false, detail = true, side = false), ValorantShape.of(WidgetSize.L33))
        assertEquals(ValorantShape(summary = false, detail = true, side = false), ValorantShape.of(WidgetSize.T3))
        assertEquals(ValorantShape(summary = false, detail = false, side = false), ValorantShape.of(WidgetSize.L))
        // Haut et étroit (2 × 4) : l'essentiel, les équipes l'une sous l'autre.
        assertEquals(ValorantShape(summary = false, detail = false, side = false), ValorantShape.of(WidgetSize.T))
    }

    @Test
    fun numbersAreWrittenTheFrenchWay() {
        assertEquals("1,62", ValorantKit.kd(1.62))
        assertEquals("0,80", ValorantKit.kd(0.8))
        assertEquals("—", ValorantKit.kd(null))
        assertEquals("31 %", ValorantKit.pct(31))
        assertEquals("—", ValorantKit.pct(null))
        assertEquals("+21", ValorantKit.signed(21))
        assertEquals("−17", ValorantKit.signed(-17))
        assertEquals("0", ValorantKit.signed(0))
    }

    @Test
    fun rankNamesFollowTheCompanion() {
        val divisions = listOf("Fer", "Bronze", "Argent", "Or", "Platine", "Diamant", "Ascendant", "Immortel")
        assertEquals("Fer 1", ValorantKit.valorantRankName(divisions, 3))
        assertEquals("Or 3", ValorantKit.valorantRankName(divisions, 14))
        assertEquals("Immortel 3", ValorantKit.valorantRankName(divisions, 26))
        assertNull(ValorantKit.valorantRankName(divisions, 2))
        assertNull(ValorantKit.valorantRankName(divisions, 27))
        assertNull(ValorantKit.valorantRankName(divisions, null))
    }

    @Test
    fun rrBarStopsAtImmortal() {
        assertEquals(0.42f, ValorantKit.rrProgress(14, 42)!!, 0.001f)
        assertEquals(1f, ValorantKit.rrProgress(14, 140)!!, 0.001f)
        assertNull(ValorantKit.rrProgress(24, 180))
        assertNull(ValorantKit.rrProgress(27, 400))
        assertNull(ValorantKit.rrProgress(14, null))
    }

    @Test
    fun eachPartyHasItsHueAndASoloPlayerNone() {
        assertNull(ValorantKit.partyHue(null))
        assertEquals(R.attr.c_hue_cyan, ValorantKit.partyHue(1))
        assertEquals(R.attr.c_hue_orange, ValorantKit.partyHue(2))
        assertEquals(R.attr.c_hue_cyan, ValorantKit.partyHue(5))
        assertTrue(ValorantKit.partyHue(1) != ValorantKit.partyHue(2))
        assertFalse(ValorantKit.partyHue(3) == null)
    }
}
