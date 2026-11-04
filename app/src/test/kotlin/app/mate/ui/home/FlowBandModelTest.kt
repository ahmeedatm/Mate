package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowBandModelTest {
    @Test
    fun `passer a l'ecran suivant fait defiler d'un ecran vers le haut`() {
        assertEquals(800f, FlowBandModel.scrolled(0f, 1), 0f)
        assertEquals(-800f, FlowBandModel.scrolled(0f, -1), 0f)
        assertEquals(1000f, FlowBandModel.scrolled(200f, 5), 0f)
    }

    @Test
    fun `le defilement pose est ramene dans un tour`() {
        assertEquals(400f, FlowBandModel.settled(1600f), 0f)
        assertEquals(0f, FlowBandModel.settled(1200f), 0f)
        assertEquals(-800f, FlowBandModel.settled(-800f), 0f)
    }

    @Test
    fun `un tour ramene le meme dessin`() {
        assertEquals(0f, FlowBandModel.LOOP % FlowBandModel.PERIOD, 0f)
        assertEquals(0f, FlowBandModel.LOOP % FlowBandModel.WAVE, 0f)
    }

    @Test
    fun `boucles visibles autour du defilement`() {
        assertEquals(0, FlowBandModel.firstLoop(offset = 0f, firstLoop = 153f))
        assertEquals(1, FlowBandModel.lastLoop(offset = 0f, firstLoop = 153f))
        assertEquals(2, FlowBandModel.firstLoop(offset = 800f, firstLoop = 153f))
        assertEquals(3, FlowBandModel.lastLoop(offset = 800f, firstLoop = 153f))
    }

    @Test
    fun `une vague fait un nombre entier de fois le tour d'une boucle`() {
        assertEquals(1, FlowBandModel.waves(22f))
        assertEquals(2, FlowBandModel.waves(46f))
        assertEquals(3, FlowBandModel.waves(70f))
    }

    @Test
    fun `aucune vue sans carte, aucun voile`() {
        assertEquals(emptyList<Pair<Float, Float>>(), FlowBandModel.veils(emptyList(), 48f, 800f))
    }

    @Test
    fun `deux zones proches se rejoignent`() {
        val bands = FlowBandModel.veils(listOf(200f to 300f, 330f to 400f), 48f, 800f)
        assertEquals(listOf(188f to 412f), bands)
    }

    @Test
    fun `deux zones eloignees restent separees`() {
        val bands = FlowBandModel.veils(listOf(300f to 400f, 500f to 600f), 48f, 800f)
        assertEquals(listOf(288f to 412f, 488f to 612f), bands)
    }

    @Test
    fun `une zone pres du haut ou du bas va jusqu'au bord`() {
        val bands = FlowBandModel.veils(listOf(20f to 120f, 700f to 780f), 48f, 800f)
        assertEquals(listOf(0f to 132f, 688f to 800f), bands)
    }

    @Test
    fun `les zones sont triees avant d'etre rejointes`() {
        val bands = FlowBandModel.veils(listOf(500f to 600f, 200f to 300f), 48f, 800f)
        assertEquals(listOf(188f to 312f, 488f to 612f), bands)
    }

    @Test
    fun `le liquide coule jusqu'a la fin de la queue, jamais en animations reduites`() {
        assertEquals(5000L, FlowBandModel.flowEnd(now = 1000L, still = false, tail = 4000L))
        assertEquals(0L, FlowBandModel.flowEnd(now = 1000L, still = true, tail = 4000L))
    }

    @Test
    fun `le voile suit les widgets seulement si les tubes tournent ou juste apres un changement de mise en page`() {
        assertFalse(FlowBandModel.follows(now = 100L, flowUntil = 0L, layoutUntil = 0L))
        assertFalse(FlowBandModel.follows(now = 100L, flowUntil = 100L, layoutUntil = 100L))
        assertTrue(FlowBandModel.follows(now = 99L, flowUntil = 100L, layoutUntil = 0L))
        assertTrue(FlowBandModel.follows(now = 99L, flowUntil = 0L, layoutUntil = 100L))
        assertTrue(FlowBandModel.follows(now = 100L, flowUntil = Long.MAX_VALUE, layoutUntil = 0L))
    }
}
