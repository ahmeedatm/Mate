package app.mate.ui.home

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileStatesTest {

    @After
    fun reset() {
        TileStates.update(emptyList())
    }

    @Test
    fun `une touche est allumee quand son id est dans la liste de l'ordinateur`() {
        TileStates.update(listOf("t1", "t3"))
        assertTrue(TileStates.isLit("t1"))
        assertFalse(TileStates.isLit("t2"))
        assertTrue(TileStates.isLit("t3"))
    }

    @Test
    fun `chaque ordre remplace la liste complete`() {
        TileStates.update(listOf("t1"))
        TileStates.update(listOf("t2"))
        assertFalse(TileStates.isLit("t1"))
        assertTrue(TileStates.isLit("t2"))
    }

    @Test
    fun `update dit si la liste a change et ne previent que dans ce cas`() {
        var calls = 0
        val stop = TileStates.listen { calls++ }
        assertTrue(TileStates.update(listOf("t1")))
        assertFalse(TileStates.update(listOf("t1")))
        assertFalse("l'ordre des ids ne compte pas", TileStates.update(listOf("t1", "t1")))
        assertTrue(TileStates.update(emptyList()))
        assertEquals(2, calls)
        stop()
        TileStates.update(listOf("t9"))
        assertEquals("plus d'appel après l'arrêt", 2, calls)
    }
}
