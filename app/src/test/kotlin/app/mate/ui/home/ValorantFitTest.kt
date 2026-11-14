package app.mate.ui.home

import app.mate.net.Valorant
import app.mate.net.ValorantEnd
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantMatch
import app.mate.net.ValorantMe
import app.mate.net.ValorantScore
import app.mate.net.ValorantState
import app.mate.net.ValorantToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValorantFitTest {
    private val all = ValorantFit(enemies = true, detail = true, level = true)

    @Test
    fun unmeasuredViewGetsTheMostTheShapeAllows() {
        assertEquals(all, ValorantFit.of(ValorantShape.of(WidgetSize.XL), heightDp = 0))
        assertEquals(ValorantFit(enemies = true, detail = false, level = false), ValorantFit.of(ValorantShape.of(WidgetSize.L), heightDp = 0))
    }

    @Test
    fun roomyViewKeepsEverything() {
        assertEquals(all, ValorantFit.of(ValorantShape.of(WidgetSize.FULL), heightDp = 600))
    }

    @Test
    fun theLevelGoesFirstThenTheDetailColumnsThenTheOtherTeam() {
        val shape = ValorantShape.of(WidgetSize.XL)
        val levelOff = ValorantFit(enemies = true, detail = true, level = false)
        val essential = ValorantFit(enemies = true, detail = false, level = false)
        val mine = ValorantFit(enemies = false, detail = false, level = false)
        assertEquals(all, ValorantFit.of(shape, ValorantFit.neededDp(shape, all)))
        assertEquals(levelOff, ValorantFit.of(shape, ValorantFit.neededDp(shape, all) - 1))
        assertEquals(essential, ValorantFit.of(shape, ValorantFit.neededDp(shape, levelOff) - 1))
        // Côte à côte, l'équipe seule a autant de lignes : rien de gagné, on garde les deux.
        assertEquals(essential, ValorantFit.of(shape, 10))
        val stacked = ValorantShape.of(WidgetSize.L)
        assertEquals(mine, ValorantFit.of(stacked, ValorantFit.neededDp(stacked, ValorantFit(enemies = true, detail = false, level = false)) - 1))
    }

    @Test
    fun twoByTwoShowsOnlyMyTeamWhereTenRowsDoNotFit() {
        // Mesuré sur l'émulateur : la place sous le titre d'un 2 × 2 fait ~220 dp ; dix lignes et deux titres n'y tiennent pas.
        assertFalse(ValorantFit.of(ValorantShape.of(WidgetSize.L), heightDp = 220).enemies)
        assertTrue(ValorantFit.of(ValorantShape.of(WidgetSize.L23), heightDp = 400).enemies)
    }

    @Test
    fun largerTextNeedsMoreRoom() {
        val shape = ValorantShape.of(WidgetSize.XL)
        assertTrue(ValorantFit.neededDp(shape, all, fontScale = 1.3f) > ValorantFit.neededDp(shape, all))
        // La place qui suffisait en taille normale ne suffit plus : le niveau tombe d'abord.
        val levelOff = ValorantFit(enemies = true, detail = true, level = false)
        assertEquals(levelOff, ValorantFit.of(shape, ValorantFit.neededDp(shape, levelOff, fontScale = 1.3f), fontScale = 1.3f))
        assertEquals(all, ValorantFit.of(shape, ValorantFit.neededDp(shape, all, fontScale = 1.3f), fontScale = 1.3f))
    }

    @Test
    fun bigShapesKeepTallRowsOnlyWhereTheyFit() {
        val tall = ValorantShape.of(WidgetSize.T3)
        val fit = ValorantFit.of(tall, heightDp = 0)
        // Pas mesuré, ou assez de place : lignes hautes, tableau centré.
        assertFalse(ValorantFit.stretches(tall, fit, heightDp = 0))
        assertFalse(ValorantFit.stretches(tall, fit, heightDp = 2000))
        // Thème à cadre et barre du bas (Instrument) : les lignes se partagent la hauteur au lieu d'être coupées.
        assertTrue(ValorantFit.stretches(tall, fit, heightDp = 480))
        // Une forme qui étire déjà ses lignes continue.
        assertTrue(ValorantFit.stretches(ValorantShape.of(WidgetSize.XL), fit, heightDp = 2000))
    }

    private val me = ValorantMe("Eliot#EUW", 14, "Or 3", 42, 90)
    private val game = Valorant(ValorantState.INGAME, "Compétition", "Ascent", ValorantScore(7, 5), me, emptyList())
    private val menu = game.copy(
        state = ValorantState.MENU,
        score = null,
        today = ValorantToday(9, 2, 2, listOf(true, false)),
        history = listOf(ValorantMatch(true, null, "Ascent", null, null, null, null, null, null, 21)),
    )
    private val ended = menu.copy(end = ValorantEnd(ValorantEndStatus.READY, "Ascent", "Compétition", false, true, ValorantScore(13, 9), emptyList(), rr = 21))

    @Test
    fun pillShowsOneThing() {
        assertEquals(ValorantGlance(score = true), ValorantGlance.of(game, WidgetSize.S))
        assertEquals(ValorantGlance(result = true), ValorantGlance.of(ended, WidgetSize.S))
        assertEquals(ValorantGlance(rank = true), ValorantGlance.of(menu, WidgetSize.S))
    }

    @Test
    fun rowTradesTheRankForTheMapDuringTheGame() {
        assertEquals(ValorantGlance(band = true), ValorantGlance.of(game, WidgetSize.M))
        assertEquals(ValorantGlance(rank = true, band = true), ValorantGlance.of(game, WidgetSize.W))
        assertEquals(ValorantGlance(result = true), ValorantGlance.of(ended, WidgetSize.M))
        assertEquals(ValorantGlance(result = true, myLine = true), ValorantGlance.of(ended, WidgetSize.M3))
        assertEquals(ValorantGlance(rank = true), ValorantGlance.of(menu, WidgetSize.M))
        assertEquals(ValorantGlance(rank = true, today = true), ValorantGlance.of(menu, WidgetSize.W))
    }

    @Test
    fun columnGrowsDownward() {
        assertEquals(ValorantGlance(score = true), ValorantGlance.of(game, WidgetSize.V))
        assertEquals(ValorantGlance(rank = true, band = true), ValorantGlance.of(game, WidgetSize.V3))
        assertEquals(ValorantGlance(result = true), ValorantGlance.of(ended, WidgetSize.V))
        assertEquals(ValorantGlance(result = true, myLine = true), ValorantGlance.of(ended, WidgetSize.V4))
        assertEquals(ValorantGlance(rank = true), ValorantGlance.of(menu, WidgetSize.V))
        assertEquals(ValorantGlance(rank = true, history = true), ValorantGlance.of(menu, WidgetSize.V3))
    }

    @Test
    fun nothingToAddWithoutTheData() {
        assertEquals(ValorantGlance(rank = true), ValorantGlance.of(menu.copy(today = null), WidgetSize.W))
        assertEquals(ValorantGlance(rank = true), ValorantGlance.of(menu.copy(history = emptyList()), WidgetSize.V4))
        // Partie sans score encore lu : pas de chiffre vide, le rang reste.
        assertEquals(ValorantGlance(rank = true), ValorantGlance.of(game.copy(score = null), WidgetSize.S))
        assertEquals(ValorantGlance(rank = true), ValorantGlance.of(game.copy(state = ValorantState.PREGAME, score = null), WidgetSize.M))
    }
}
