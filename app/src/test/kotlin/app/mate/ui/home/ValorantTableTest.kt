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
        assertEquals(ValorantShape(summary = false, detail = true, side = true, stretch = false), ValorantShape.of(WidgetSize.FULL))
        // 3 × 2 : trop bas pour dix lignes l'une sous l'autre, les équipes côte à côte avec l'essentiel.
        assertEquals(ValorantShape(summary = false, detail = false, side = true), ValorantShape.of(WidgetSize.L3))
        assertEquals(ValorantShape(summary = false, detail = true, side = false), ValorantShape.of(WidgetSize.L33))
        assertEquals(ValorantShape(summary = false, detail = true, side = false, stretch = false), ValorantShape.of(WidgetSize.T3))
        assertEquals(ValorantShape(summary = false, detail = false, side = false), ValorantShape.of(WidgetSize.L))
        // Haut et étroit (2 × 4) : l'essentiel, les équipes l'une sous l'autre.
        assertEquals(ValorantShape(summary = false, detail = false, side = false, stretch = false), ValorantShape.of(WidgetSize.T))
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
        // Jamais les couleurs d'équipe (turquoise, rouge) : un groupe n'est pas une équipe.
        val teams = listOf(R.attr.c_hue_cyan, R.attr.c_hue_red)
        for (n in 1..8) assertFalse("groupe $n", ValorantKit.partyHue(n) in teams)
        assertEquals(R.attr.c_hue_violet, ValorantKit.partyHue(1))
        assertEquals(R.attr.c_hue_violet, ValorantKit.partyHue(5))
        assertTrue(ValorantKit.partyHue(1) != ValorantKit.partyHue(2))
        assertFalse(ValorantKit.partyHue(3) == null)
    }

    @Test
    fun rowsStretchOnlyWhileTheyStayCompact() {
        // Cinq lignes dans 4 × 3 ou 4 × 4, dix dans 2 × 4 : étirées, elles feraient plus de 100 px de haut pour une icône de 30.
        for (size in listOf(WidgetSize.XL3, WidgetSize.FULL, WidgetSize.T, WidgetSize.T3)) assertFalse(size.name, ValorantShape.of(size).stretch)
        for (size in listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33)) assertTrue(size.name, ValorantShape.of(size).stretch)
    }

    @Test
    fun agentFaceFromIdOrNameOrEmpty() {
        assertEquals(ValorantKit.Face.IMAGE, ValorantKit.face("add6443a-41bd-e414-f6ad-e58d267f4e95", "Jett"))
        // Compagnon d'avant la refonte : le nom sans l'identifiant, l'initiale à la place de l'icône.
        assertEquals(ValorantKit.Face.NAME, ValorantKit.face(null, "Jett"))
        assertEquals(ValorantKit.Face.EMPTY, ValorantKit.face(null, null))
        assertEquals("Je", ValorantKit.initials("Jett"))
        assertEquals("KA", ValorantKit.initials("KAY/O"))
    }

    @Test
    fun statTonesFollowTheThresholds() {
        val t = ValorantArt.Tone.entries
        assertEquals(listOf(t[0], t[1], t[2], t[3]), listOf(1.2, 0.9, 0.89, null).map { ValorantArt.kdTone(it) })
        assertEquals(listOf(t[0], t[1], t[2], t[3]), listOf(25, 17, 16, null).map { ValorantArt.hsTone(it) })
        assertEquals(listOf(t[0], t[1], t[2], t[3]), listOf(55, 45, 44, null).map { ValorantArt.wrTone(it) })
    }

    @Test
    fun placeInTheMatchGivesTheTone() {
        // Dix joueurs : les 3 premiers bons, les 3 derniers faibles.
        assertEquals(ValorantArt.Tone.GOOD, ValorantArt.rankTone(2, 10))
        assertEquals(ValorantArt.Tone.MID, ValorantArt.rankTone(3, 10))
        assertEquals(ValorantArt.Tone.MID, ValorantArt.rankTone(6, 10))
        assertEquals(ValorantArt.Tone.LOW, ValorantArt.rankTone(7, 10))
        assertEquals(ValorantArt.Tone.MID, ValorantArt.rankTone(0, 1))
        assertEquals(ValorantArt.Tone.MID, ValorantArt.rankTone(-1, 10))
    }
}
