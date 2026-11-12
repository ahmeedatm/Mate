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

    @Test
    fun `photos, plus grandes quand il y a peu de monde, sans depasser la hauteur`() {
        val two = planFaces(2, lengthDp = 500f, maxAvatarDp = 56f, nameDp = 50f)
        val eight = planFaces(8, lengthDp = 500f, maxAvatarDp = 56f, nameDp = 50f)
        assertEquals(2, two.fits)
        assertEquals(56f, two.avatarDp, 0.01f)
        assertTrue(eight.avatarDp < two.avatarDp)
        assertTrue(eight.avatarDp >= FaceMetrics.MIN_AVATAR_DP)
    }

    @Test
    fun `photos, l'espace se partage entre les participants`() {
        assertEquals(250f, planFaces(2, 500f, 56f, 50f).slotDp, 0.01f)
        assertEquals(125f, planFaces(4, 500f, 56f, 50f).slotDp, 0.01f)
    }

    @Test
    fun `un nom court n'est jamais coupe, meme dans un salon plein`() {
        for (count in 1..30) {
            val plan = planFaces(count, lengthDp = 500f, maxAvatarDp = 56f, nameDp = 50f)
            assertTrue("$count participants", plan.slotDp - FaceMetrics.GAP_DP >= 50f)
            assertTrue(plan.avatarDp + FaceMetrics.HALO_DP + FaceMetrics.GAP_DP <= plan.slotDp + 0.01f)
        }
        // Salon plein : moins de places que de participants, la derniere devient « +N » (planMembers).
        assertEquals(8, planFaces(30, 500f, 56f, 50f).fits)
    }

    @Test
    fun `photos, rien ne tient dans une rangee trop courte`() {
        assertEquals(0, planFaces(3, lengthDp = 30f, maxAvatarDp = 56f, nameDp = 50f).fits)
        assertEquals(0, planFaces(0, lengthDp = 500f, maxAvatarDp = 56f, nameDp = 50f).fits)
        assertEquals("vue pas encore mesuree", 0, planFaces(3, lengthDp = 0f, maxAvatarDp = 56f, nameDp = 50f).fits)
    }

    @Test
    fun `colonne sans nom, la photo garde sa taille minimale`() {
        val plan = planFaces(3, lengthDp = 300f, maxAvatarDp = 10f, nameDp = 0f)
        assertEquals(3, plan.fits)
        assertEquals(FaceMetrics.MIN_AVATAR_DP, plan.avatarDp, 0.01f)
    }
}
