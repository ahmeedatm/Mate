package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
