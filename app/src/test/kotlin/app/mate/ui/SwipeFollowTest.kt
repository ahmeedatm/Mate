package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeFollowTest {

    private val edge = 40f

    @Test
    fun `un écran attend dans ce sens, l'écran suit une partie du geste`() {
        assertEquals(-80f, SwipeFollow.offset(-200f, canGo = true, edgeMax = edge), 0.01f)
        assertEquals(40f, SwipeFollow.offset(100f, canGo = true, edgeMax = edge), 0.01f)
    }

    @Test
    fun `au bout de la barre, l'écran résiste sans jamais dépasser la course plafond`() {
        val short = SwipeFollow.offset(-50f, canGo = false, edgeMax = edge)
        val long = SwipeFollow.offset(-2000f, canGo = false, edgeMax = edge)
        assertTrue("dans le sens du doigt", short < 0f && long < 0f)
        assertTrue("plus le doigt va loin, plus il cède", long < short)
        assertTrue("jamais au-delà du plafond", long >= -edge)
        assertTrue("moins que s'il pouvait changer d'écran", short > SwipeFollow.offset(-50f, canGo = true, edgeMax = edge))
    }

    @Test
    fun `l'écran s'estompe avec la course, jusqu'à 70 %`() {
        assertEquals(1f, SwipeFollow.alpha(0f, fade = 160f), 0.001f)
        assertEquals(0.85f, SwipeFollow.alpha(-80f, fade = 160f), 0.001f)
        assertEquals(0.7f, SwipeFollow.alpha(500f, fade = 160f), 0.001f)
    }
}
