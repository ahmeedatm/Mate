package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorantDuelTest {
    @Test
    fun playersFaceTheOneAtTheSamePlace() {
        assertEquals(listOf("a" to "x", "b" to "y"), ValorantDuel.pairs(listOf("a", "b"), listOf("x", "y")))
        // Équipe d'en face pas encore connue (sélection) ou incomplète : la place reste vide.
        assertEquals(listOf("a" to null, "b" to null), ValorantDuel.pairs(listOf("a", "b"), emptyList()))
        assertEquals(listOf("a" to "x", null to "y"), ValorantDuel.pairs(listOf("a"), listOf("x", "y")))
    }

    @Test
    fun teamAverageSkipsUnknownValues() {
        assertEquals(1.0, ValorantDuel.average(listOf(0.5, null, 1.5))!!, 1e-9)
        assertNull(ValorantDuel.average(listOf(null, null)))
        assertNull(ValorantDuel.average(emptyList()))
    }

    @Test
    fun tugSplitsBetweenTheTwoTeams() {
        assertEquals(0.5f, ValorantDuel.share(1.0, 1.0)!!, 1e-6f)
        assertEquals(0.75f, ValorantDuel.share(3.0, 1.0)!!, 1e-6f)
        assertNull(ValorantDuel.share(null, 1.0))
        assertNull(ValorantDuel.share(0.0, 0.0))
    }

    @Test
    fun barGrowsWithTheValueAndAlwaysShows() {
        assertEquals(0.5f, ValorantDuel.fraction(1.0, ValorantDuel.KD_FULL), 1e-6f)
        assertEquals(1f, ValorantDuel.fraction(3.4, ValorantDuel.KD_FULL), 1e-6f)
        // Un K/D nul ou inconnu garde un bout de barre : la ligne ne paraît pas vide.
        assertEquals(ValorantDuel.MIN_BAR, ValorantDuel.fraction(0.0, ValorantDuel.KD_FULL), 1e-6f)
        assertEquals(ValorantDuel.MIN_BAR, ValorantDuel.fraction(null, ValorantDuel.KD_FULL), 1e-6f)
        assertEquals(ValorantDuel.MIN_BAR, ValorantDuel.fraction(1.0, 0.0), 1e-6f)
    }

    @Test
    fun lastGamesBarsAreScaledOnTheBiggestSwing() {
        assertEquals(listOf(1f, -0.5f, 0f), ValorantDuel.swings(listOf(20, -10, 0)))
        assertEquals(listOf(0f), ValorantDuel.swings(listOf(0)))
        assertEquals(emptyList<Float>(), ValorantDuel.swings(emptyList()))
    }

    @Test
    fun lowZoomDropsTheSmallStatsLine() {
        assertTrue((ValorantDuel.lines(bodyDp = 0, pairs = 5) == 3))
        val enough = ValorantDuel.HEADER_DP + 5 * ValorantDuel.ROOMY_PAIR_DP
        assertTrue((ValorantDuel.lines(enough, pairs = 5) == 3))
        assertFalse((ValorantDuel.lines(enough - 1, pairs = 5) == 3))
        // Sélection : l'équipe d'en face n'est pas là, mais les cinq paires gardent leur place.
        assertTrue((ValorantDuel.lines(enough, pairs = 0) == 3))
        // Texte grossi : la petite ligne demande plus de place.
        assertFalse((ValorantDuel.lines(enough, pairs = 5, fontScale = 1.3f) == 3))
    }

    @Test
    fun eachPlayerKeepsTheLinesThatFit() {
        assertEquals(3, ValorantDuel.lines(bodyDp = 0, pairs = 5))
        val three = ValorantDuel.HEADER_DP + 5 * ValorantDuel.ROOMY_PAIR_DP
        val two = ValorantDuel.HEADER_DP + 5 * ValorantDuel.TWO_LINES_DP
        assertEquals(3, ValorantDuel.lines(three, pairs = 5))
        assertEquals(2, ValorantDuel.lines(three - 1, pairs = 5))
        assertEquals(2, ValorantDuel.lines(two, pairs = 5))
        // Plus que le nom : rien de rogné à moitié.
        assertEquals(1, ValorantDuel.lines(two - 1, pairs = 5))
        // Texte grossi : l'en-tête et les lignes grandissent.
        assertEquals(1, ValorantDuel.lines(two, pairs = 5, fontScale = 1.3f))
    }

    @Test
    fun menuDialShrinksToTheRoom() {
        assertEquals(ValorantDuel.DIAL_DP, ValorantDuel.dialDp(bodyDp = 0))
        assertEquals(ValorantDuel.DIAL_DP, ValorantDuel.dialDp(bodyDp = 1000))
        assertEquals(300 - ValorantDuel.DIAL_MARGIN_DP, ValorantDuel.dialDp(bodyDp = 300))
        assertEquals(ValorantDuel.DIAL_MIN_DP, ValorantDuel.dialDp(bodyDp = 50))
    }
}
