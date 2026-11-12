package app.mate.ui.home

import app.mate.R
import app.mate.net.DeckTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordConsoleTest {

    private fun tile(id: String, whenState: String? = null, symbol: String? = null, type: String = "press") =
        DeckTile(id, id, type, glyph = null, color = null, whenState = whenState, symbol = symbol)

    private val open = tile("ouvrir", type = "app")
    private val mic = tile("micro", "call", "mic_off")
    private val deaf = tile("sourdine", "call", "volume_off")
    private val share = tile("partage", "streaming", "present")
    private val leave = tile("quitter", "call", "call_end")

    /** Ordre de la page Discord du Deck (`deck.example.windows.json`). */
    private val inCall = listOf(open, mic, deaf, leave)

    private fun keys(family: ShapeFamily, tiles: List<DeckTile>, w: Int = 600, h: Int = 300) = planConsole(family, tiles, w, h).keys

    @Test
    fun `assez de place, toutes les touches, celle de l'etat d'abord et Ouvrir au bout`() {
        assertEquals(listOf(mic, deaf, leave, open), keys(ShapeFamily.BLOCK, inCall))
        assertEquals(listOf(mic, deaf, share, leave, open), keys(ShapeFamily.FULL, listOf(open, mic, deaf, share, leave), w = 1000))
    }

    @Test
    fun `place qui manque, Quitter reste avant Ouvrir`() {
        // Une touche = 76 dp de large : 2 places dans 160 dp, 3 dans 240.
        assertEquals(listOf(mic, leave), keys(ShapeFamily.BLOCK, inCall, w = 160))
        assertEquals(listOf(mic, deaf, leave), keys(ShapeFamily.COLUMN, inCall, w = 240, h = 400))
    }

    @Test
    fun `ligne, les touches prennent la moitie de la largeur, l'ecran LCD le reste`() {
        assertEquals(listOf(mic, deaf, leave), keys(ShapeFamily.ROW, inCall, w = 520, h = 68))
        assertEquals(listOf(mic, leave), keys(ShapeFamily.ROW, inCall, w = 400, h = 68))
    }

    @Test
    fun `pastille, une seule touche, et l'ecran LCD seulement si la hauteur le permet`() {
        assertEquals(listOf(mic), keys(ShapeFamily.PILL, inCall, w = 240, h = 200))
        assertTrue(planConsole(ShapeFamily.PILL, inCall, 240, 200).lcd)
        assertFalse("rangee basse : la touche seule", planConsole(ShapeFamily.PILL, inCall, 240, 68).lcd)
        assertTrue(planConsole(ShapeFamily.BLOCK, inCall, 240, 68).lcd)
    }

    @Test
    fun `hors appel, la touche Ouvrir seule, sans page, aucune touche`() {
        assertEquals(listOf(open), keys(ShapeFamily.BLOCK, listOf(open)))
        assertEquals(emptyList<DeckTile>(), keys(ShapeFamily.BLOCK, emptyList()))
        assertEquals(emptyList<DeckTile>(), keys(ShapeFamily.PILL, emptyList()))
    }

    @Test
    fun `libelles sous les touches seulement si la hauteur le permet`() {
        assertFalse(planConsole(ShapeFamily.ROW, inCall, 520, 68).labels)
        assertTrue(planConsole(ShapeFamily.ROW, inCall, 520, 148).labels)
        assertTrue(planConsole(ShapeFamily.BLOCK, inCall, 520, 300).labels)
    }

    @Test
    fun `colonne, les vumetres en colonne, ailleurs en rangee`() {
        assertTrue(planConsole(ShapeFamily.COLUMN, inCall, 240, 400).vertical)
        assertFalse(planConsole(ShapeFamily.BLOCK, inCall, 520, 300).vertical)
    }

    @Test
    fun `carte tres large, les touches a droite de la fenetre LCD, comme en Ligne`() {
        assertTrue(planConsole(ShapeFamily.ROW, inCall, 520, 68).side)
        assertTrue("Grand 4 x 2", planConsole(ShapeFamily.LARGE, inCall, 1160, 230).side)
        assertFalse("Bloc 2 x 2", planConsole(ShapeFamily.BLOCK, inCall, 560, 280).side)
        assertFalse(planConsole(ShapeFamily.FULL, inCall, 1160, 560).side)
        assertFalse(planConsole(ShapeFamily.PILL, inCall, 240, 60).side)
    }

    @Test
    fun `pastille basse, la ligne du LCD tient au-dessus de la touche sans libelle`() {
        val plan = planConsole(ShapeFamily.PILL, inCall, 240, 114)
        assertTrue(plan.lcd)
        assertFalse(plan.labels)
    }

    @Test
    fun `libelle court d'une touche, tire de son symbole`() {
        assertEquals(R.string.console_key_mic, consoleLabel(mic))
        assertEquals(R.string.console_key_deafen, consoleLabel(deaf))
        assertEquals(R.string.console_key_leave, consoleLabel(leave))
        assertEquals(R.string.console_key_share, consoleLabel(share))
        assertEquals(R.string.console_key_open, consoleLabel(open))
        assertNull("touche maison : son propre libelle", consoleLabel(tile("autre", symbol = "lock")))
    }
}
