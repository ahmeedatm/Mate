package app.mate.ui

import app.mate.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyDockModelTest {

    private val kept = listOf(Mode.FOCUS, Mode.COMPUTER)

    @Test
    fun `une touche par ecran, numerotees dans l'ordre`() {
        val screens = KeyDockModel.screens(order = listOf(0, 2, 3), callPage = 1, inCall = false)
        assertEquals(listOf(0, 2, 3), screens.map { it.page })
        assertEquals(listOf("01", "02", "03"), screens.map { it.number })
        assertEquals(listOf(false, false, false), screens.map { it.call })
    }

    @Test
    fun `pendant un appel Reunion apparait en dernier, marquee comme appel`() {
        val screens = KeyDockModel.screens(order = listOf(0, 2, 1), callPage = 1, inCall = true)
        assertEquals(listOf(0, 2, 1), screens.map { it.page })
        assertEquals(listOf(false, false, true), screens.map { it.call })
        assertEquals("03", screens.last().number)
    }

    @Test
    fun `les outils ronds sont Focus Ordi Camera Reglages, un outil masque disparait`() {
        assertEquals(listOf(Mode.FOCUS, Mode.COMPUTER, Mode.CAMERA, Mode.SETTINGS), KeyDockModel.tools(kept))
        assertEquals(listOf(Mode.COMPUTER, Mode.CAMERA, Mode.SETTINGS), KeyDockModel.tools(listOf(Mode.COMPUTER)))
        assertEquals(listOf(Mode.CAMERA, Mode.SETTINGS), KeyDockModel.tools(emptyList()))
    }

    @Test
    fun `le plus tant qu'il reste de la place`() {
        assertTrue(KeyDockModel.showAdd(1))
        assertTrue(KeyDockModel.showAdd(KeyDockModel.MAX_SCREENS - 1))
        assertFalse(KeyDockModel.showAdd(KeyDockModel.MAX_SCREENS))
    }

    @Test
    fun `au-dela de cinq ecrans les touches deviennent etroites`() {
        assertFalse(KeyDockModel.compact(5))
        assertTrue(KeyDockModel.compact(6))
    }

    @Test
    fun `l'ecran actif est celui de la page affichee sur l'Accueil, aucun ailleurs`() {
        val screens = KeyDockModel.screens(listOf(0, 2, 1), callPage = 1, inCall = true)
        assertEquals(1, KeyDockModel.markedScreen(screens, Mode.HOME, 2))
        assertEquals(2, KeyDockModel.markedScreen(screens, Mode.HOME, 1))
        assertEquals(-1, KeyDockModel.markedScreen(screens, Mode.FOCUS, 2))
    }

    @Test
    fun `l'outil actif suit le mode, la Camera a le sien, un outil masque n'en a pas`() {
        val tools = KeyDockModel.tools(kept)
        assertEquals(0, KeyDockModel.markedTool(tools, Mode.FOCUS))
        assertEquals(2, KeyDockModel.markedTool(tools, Mode.CAMERA))
        assertEquals(3, KeyDockModel.markedTool(tools, Mode.SETTINGS))
        assertEquals(-1, KeyDockModel.markedTool(tools, Mode.HOME))
        assertEquals(-1, KeyDockModel.markedTool(KeyDockModel.tools(listOf(Mode.COMPUTER)), Mode.FOCUS))
    }

    @Test
    fun `numero de l'ecran pour l'aplat, vide sans touche`() {
        val screens = KeyDockModel.screens(listOf(0, 2), callPage = -1, inCall = false)
        assertEquals("02", KeyDockModel.numberOf(screens, 2))
        assertEquals("", KeyDockModel.numberOf(screens, 7))
    }

    @Test
    fun `le balayage vertical parcourt tous les ecrans puis les outils gardes`() {
        val screens = KeyDockModel.screens(listOf(0, 1, 2, 3, 4, 5, 6), callPage = -1, inCall = false)
        val entries = KeyDockModel.stepEntries(screens, kept)
        assertEquals(RailEntry.Profile(6, false), RailModel.neighbor(entries, Mode.HOME, 5, 1))
        assertEquals(RailEntry.Tool(Mode.FOCUS), RailModel.neighbor(entries, Mode.HOME, 6, 1))
        assertEquals(RailEntry.Profile(6, false), RailModel.neighbor(entries, Mode.FOCUS, 0, -1))
    }
}
