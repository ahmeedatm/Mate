package app.mate.ui

import app.mate.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyDockModelTest {

    private val allTools = listOf(Mode.FOCUS, Mode.COMPUTER)

    @Test
    fun `cinq touches numerotees Accueil Focus Ordi Camera Reglages`() {
        val keys = KeyDockModel.keys(allTools)
        assertEquals(listOf(Mode.HOME, Mode.FOCUS, Mode.COMPUTER, Mode.CAMERA, Mode.SETTINGS), keys.map { it.mode })
        assertEquals(listOf("01", "02", "03", "04", "05"), keys.map { it.number })
    }

    @Test
    fun `un outil masque dans Reglages disparait et les touches se renumerotent`() {
        val keys = KeyDockModel.keys(listOf(Mode.COMPUTER))
        assertEquals(listOf(Mode.HOME, Mode.COMPUTER, Mode.CAMERA, Mode.SETTINGS), keys.map { it.mode })
        assertEquals(listOf("01", "02", "03", "04"), keys.map { it.number })
    }

    @Test
    fun `la touche marquee suit le mode, la Camera a la sienne`() {
        val keys = KeyDockModel.keys(allTools)
        assertEquals(0, KeyDockModel.marked(keys, Mode.HOME))
        assertEquals(1, KeyDockModel.marked(keys, Mode.FOCUS))
        assertEquals(2, KeyDockModel.marked(keys, Mode.COMPUTER))
        assertEquals(3, KeyDockModel.marked(keys, Mode.CAMERA))
        assertEquals(4, KeyDockModel.marked(keys, Mode.SETTINGS))
    }

    @Test
    fun `aucune touche marquee quand le mode n'en a pas`() {
        assertEquals(-1, KeyDockModel.marked(KeyDockModel.keys(listOf(Mode.COMPUTER)), Mode.FOCUS))
    }

    @Test
    fun `la DEL de 01 a un segment par profil, allume sur celui qu'on voit`() {
        val segments = KeyDockModel.segments(order = listOf(0, 1, 2), page = 1, callPage = 2, inCall = false)
        assertEquals(listOf(false, true, false), segments.map { it.on })
        assertEquals(listOf(false, false, false), segments.map { it.call })
    }

    @Test
    fun `pendant un appel le segment de Reunion est marque`() {
        val segments = KeyDockModel.segments(order = listOf(0, 1, 2), page = 0, callPage = 2, inCall = true)
        assertEquals(listOf(false, false, true), segments.map { it.call })
    }

    @Test
    fun `toucher 01 hors de l'Accueil rouvre le profil affiche`() {
        assertEquals(KeyDockModel.HomeTap.Open(1), KeyDockModel.homeTap(Mode.FOCUS, listOf(0, 1, 2), 1))
    }

    @Test
    fun `toucher 01 deja active passe au profil suivant, puis reboucle`() {
        assertEquals(KeyDockModel.HomeTap.Open(1), KeyDockModel.homeTap(Mode.HOME, listOf(0, 1, 2), 0))
        assertEquals(KeyDockModel.HomeTap.Open(0), KeyDockModel.homeTap(Mode.HOME, listOf(0, 1, 2), 2))
    }

    @Test
    fun `avec un seul profil, toucher 01 active ouvre la liste des profils`() {
        assertEquals(KeyDockModel.HomeTap.Profiles, KeyDockModel.homeTap(Mode.HOME, listOf(0), 0))
    }
}
