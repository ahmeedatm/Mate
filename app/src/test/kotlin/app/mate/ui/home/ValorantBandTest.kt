package app.mate.ui.home

import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMapStats
import app.mate.net.ValorantRound
import app.mate.net.ValorantRoundEnd
import app.mate.net.ValorantScore
import app.mate.net.ValorantState
import app.mate.net.ValorantToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorantBandTest {
    private val today = ValorantToday(27, 3, 2, listOf(true, false, true, false, true))
    private val stats = ValorantMapStats(5, 8, 40, listOf(true, true, false, true, false, true, false, true))

    private fun state(state: ValorantState, end: ValorantEnd? = null, today: ValorantToday? = this.today, stats: ValorantMapStats? = this.stats) =
        Valorant(state, "Compétition", "Ascent", ValorantScore(7, 5), null, emptyList(), today = today, mapStats = stats, end = end)

    private fun end(rr: Int? = 21) =
        ValorantEnd(ValorantEndStatus.READY, "Ascent", "Compétition", false, true, ValorantScore(13, 9), emptyList(), rounds = emptyList(), rr = rr)

    /** Bandeau moyen seulement là où le tableau garde ses lignes : les formes à dix lignes empilées prennent le fin. */
    @Test
    fun bandSizeFollowsTheShape() {
        val expected = mapOf(
            WidgetSize.S to ValorantBand.Size.NONE,
            WidgetSize.M to ValorantBand.Size.MINI,
            WidgetSize.M3 to ValorantBand.Size.MINI,
            WidgetSize.W to ValorantBand.Size.MINI,
            WidgetSize.V to ValorantBand.Size.MINI,
            WidgetSize.V3 to ValorantBand.Size.MINI,
            WidgetSize.V4 to ValorantBand.Size.MINI,
            WidgetSize.L to ValorantBand.Size.THIN,
            WidgetSize.L23 to ValorantBand.Size.THIN,
            WidgetSize.L3 to ValorantBand.Size.THIN,
            WidgetSize.XL to ValorantBand.Size.THIN,
            WidgetSize.L33 to ValorantBand.Size.THIN,
            WidgetSize.T to ValorantBand.Size.THIN,
            WidgetSize.T3 to ValorantBand.Size.THIN,
            WidgetSize.XL3 to ValorantBand.Size.MEDIUM,
            WidgetSize.FULL to ValorantBand.Size.FULL,
        )
        assertEquals(WidgetSize.entries.toSet(), expected.keys)
        for ((size, band) in expected) assertEquals(size.name, band, ValorantBand.Size.of(size))
    }

    @Test
    fun inGameEverySizeButThePillShowsTheBand() {
        val v = state(ValorantState.INGAME)
        assertFalse(ValorantBand.shows(v, ValorantBand.Size.NONE))
        for (size in listOf(ValorantBand.Size.MINI, ValorantBand.Size.THIN, ValorantBand.Size.MEDIUM, ValorantBand.Size.FULL)) {
            assertTrue(size.name, ValorantBand.shows(v, size))
        }
    }

    @Test
    fun agentSelectHasNoScoreSoNoMiniBand() {
        val v = state(ValorantState.PREGAME)
        assertFalse(ValorantBand.shows(v, ValorantBand.Size.MINI))
        assertTrue(ValorantBand.shows(v, ValorantBand.Size.THIN))
    }

    @Test
    fun atTheMenuOnlyTheFullScreenShowsTheDayWhenThereWasAGame() {
        assertTrue(ValorantBand.shows(state(ValorantState.MENU), ValorantBand.Size.FULL))
        assertFalse(ValorantBand.shows(state(ValorantState.MENU), ValorantBand.Size.MEDIUM))
        assertFalse(ValorantBand.shows(state(ValorantState.MENU, today = ValorantToday(0, 0, 0, emptyList())), ValorantBand.Size.FULL))
        assertFalse(ValorantBand.shows(state(ValorantState.MENU, today = null), ValorantBand.Size.FULL))
        assertFalse(ValorantBand.shows(state(ValorantState.OFF), ValorantBand.Size.FULL))
    }

    @Test
    fun chipsDependOnSizeAndMoment() {
        val ingame = state(ValorantState.INGAME)
        assertEquals(listOf(ValorantBand.Chip.MAP, ValorantBand.Chip.TODAY), ValorantBand.chips(ingame, ValorantBand.Size.FULL))
        assertEquals(listOf(ValorantBand.Chip.MAP), ValorantBand.chips(ingame, ValorantBand.Size.MEDIUM))
        assertEquals(emptyList<ValorantBand.Chip>(), ValorantBand.chips(ingame, ValorantBand.Size.THIN))
        val after = state(ValorantState.MENU, end = end())
        assertEquals(listOf(ValorantBand.Chip.MATCH_RR, ValorantBand.Chip.TODAY), ValorantBand.chips(after, ValorantBand.Size.FULL))
        assertEquals(listOf(ValorantBand.Chip.MATCH_RR), ValorantBand.chips(after, ValorantBand.Size.MEDIUM))
        // Compagnon ancien : rien à poser.
        assertEquals(emptyList<ValorantBand.Chip>(), ValorantBand.chips(state(ValorantState.INGAME, today = null, stats = null), ValorantBand.Size.FULL))
        assertEquals(listOf(ValorantBand.Chip.TODAY), ValorantBand.chips(state(ValorantState.MENU, end = end(rr = null)), ValorantBand.Size.FULL))
    }

    @Test
    fun liveFriezeKeepsRoomForTheTwentyFourRoundsThenOvertime() {
        val played = List(7) { ValorantRound(it % 2 == 0, null) }
        val live = ValorantBand.cells(played, live = true)
        assertEquals(24, live.size)
        assertEquals(played, live.take(7))
        assertTrue(live.drop(7).all { it == null })
        val overtime = List(26) { ValorantRound(true, ValorantRoundEnd.ELIMINATION) }
        assertEquals(overtime, ValorantBand.cells(overtime, live = true))
        // Fin de partie : seulement les rounds joués.
        assertEquals(played, ValorantBand.cells(played, live = false))
    }

    @Test
    fun mapRateNeedsThreeGames() {
        assertEquals(63, ValorantBand.mapRate(stats))
        assertNull(ValorantBand.mapRate(ValorantMapStats(1, 2, 40, listOf(true, false))))
        assertNull(ValorantBand.mapRate(null))
    }
}
