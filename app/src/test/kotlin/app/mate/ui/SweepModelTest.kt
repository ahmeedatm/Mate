package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SweepModelTest {

    @Test
    fun `le sens suit le deplacement dans la barre, zero compte comme avant`() {
        assertEquals(1, SweepModel.direction(1))
        assertEquals(1, SweepModel.direction(0))
        assertEquals(-1, SweepModel.direction(-1))
    }

    @Test
    fun `durees couvrir 300 ms, decouvrir 360 ms, texte 420 et 360 ms`() {
        assertEquals(300L, SweepModel.COVER_MS)
        assertEquals(360L, SweepModel.REVEAL_MS)
        assertEquals(420L, SweepModel.TEXT_COVER_MS)
        assertEquals(360L, SweepModel.TEXT_REVEAL_MS)
    }

    @Test
    fun `vers le bas l'aplat vient de la gauche, hors ecran, puis sort a droite`() {
        val plan = SweepModel.plan(lastMove = 1, width = 1280f, density = 1f)
        assertEquals(1, plan.direction)
        assertEquals(-1292.8f, plan.plateStart, 0.01f)
        assertEquals(1292.8f, plan.plateEnd, 0.01f)
    }

    @Test
    fun `vers le haut l'aplat vient de la droite et sort a gauche`() {
        val plan = SweepModel.plan(lastMove = -1, width = 1280f, density = 1f)
        assertEquals(-1, plan.direction)
        assertEquals(1292.8f, plan.plateStart, 0.01f)
        assertEquals(-1292.8f, plan.plateEnd, 0.01f)
    }

    @Test
    fun `le numero et le nom roulent de 180 dp a l'arrivee, puis sortent de 240 dp`() {
        val forward = SweepModel.plan(lastMove = 1, width = 1280f, density = 2f)
        assertEquals(-360f, forward.textStart, 0.01f)
        assertEquals(480f, forward.textEnd, 0.01f)
        val back = SweepModel.plan(lastMove = -1, width = 1280f, density = 2f)
        assertEquals(360f, back.textStart, 0.01f)
        assertEquals(-480f, back.textEnd, 0.01f)
    }
}
