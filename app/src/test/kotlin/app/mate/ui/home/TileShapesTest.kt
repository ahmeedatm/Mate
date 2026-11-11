package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileShapesTest {

    @Test
    fun `fit compte les elements qui tiennent, espaces compris`() {
        assertEquals(3, TileShapes.fit(room = 304, item = 96, gap = 8))
        assertEquals(1, TileShapes.fit(room = 199, item = 96, gap = 8))
        assertEquals(2, TileShapes.fit(room = 200, item = 96, gap = 8))
    }

    @Test
    fun `fit rend au moins un, meme sans place ou avant la mesure`() {
        assertEquals(1, TileShapes.fit(room = 0, item = 96, gap = 8))
        assertEquals(1, TileShapes.fit(room = 40, item = 96, gap = 8))
    }

    @Test
    fun `hors de la grille, la Ligne compte en largeur et la Colonne en hauteur`() {
        // 1280 × 800 à 160 dpi : 1 dp = 1 px. Ligne 3 × 1 de 940 × 92 : 13 icônes de 64 + 8. Colonne 1 × 3 de 300 × 500 : 8 lignes de 56 + 4.
        assertEquals(13, TileShapes.stripFit(ShapeFamily.ROW, 940, 92, 1f))
        assertEquals(8, TileShapes.stripFit(ShapeFamily.COLUMN, 300, 500, 1f))
        assertEquals(1, TileShapes.stripFit(ShapeFamily.PILL, 300, 170, 1f))
    }

    @Test
    fun `dans un panneau a filet, la Colonne ne montre que des lignes entieres, ecartees d'au moins 8 dp, sous le titre et sa marge`() {
        // Panneau de 300 × 500 : 2 × 6 de marge, titre de 14 + 8 dp de marge dessous, lignes de 56 (plaque de 52 entiere) et écart de 8.
        assertEquals(7, TileShapes.stripFit(ShapeFamily.COLUMN, 300, 500 - 12 - 22, 1f, panel = true))
        val lost = 2 * TileShapes.PANEL_STRIP_PAD_DP + TileShapes.PANEL_CAPTION_DP + TileShapes.PANEL_CAPTION_GAP_DP
        assertEquals(22, TileShapes.PANEL_CAPTION_DP + TileShapes.PANEL_CAPTION_GAP_DP)
        for (height in 120..800) {
            val room = height - lost
            val rows = TileShapes.stripFit(ShapeFamily.COLUMN, 300, room, 1f, panel = true)
            val used = rows * TileShapes.LIST_ROW_PANEL_DP + (rows - 1) * TileShapes.LIST_GAP_PANEL_DP
            if (rows > 1) assertTrue("h=$height : $rows lignes = $used dp > $room", used <= room)
        }
    }

    @Test
    fun `l'ecart entre deux lignes d'un panneau ne passe jamais sous 8 dp, meme si le theme en demande moins`() {
        assertEquals(TileShapes.stripFit(ShapeFamily.COLUMN, 300, 400, 1f, panel = true, gapDp = 8), TileShapes.stripFit(ShapeFamily.COLUMN, 300, 400, 1f, panel = true, gapDp = 4))
    }

    @Test
    fun `la plaque d'une touche tient dans sa ligne de liste`() {
        assertTrue(TileMetrics.NORMAL.plateDp <= TileShapes.LIST_ROW_PANEL_DP)
        assertTrue(TileMetrics.NORMAL.plateDp <= TileShapes.LIST_ROW_DP)
    }

    @Test
    fun `hors de la grille, la densite de l'ecran compte`() {
        assertEquals(4, TileShapes.stripFit(ShapeFamily.COLUMN, 300, 500, 2f))
    }

    @Test
    fun `vue pas encore mesuree, une touche, jamais zero (sinon le layout redessinerait sans fin)`() {
        for (family in ShapeFamily.entries) assertEquals(family.name, 1, TileShapes.stripFit(family, 0, 0, 1f))
    }

    @Test
    fun `la grille sert aux blocs, aux grands et au plein ecran`() {
        val grid = WidgetSize.entries.filter { TileShapes.usesGrid(it) }.map { it.family }.toSet()
        assertEquals(setOf(ShapeFamily.BLOCK, ShapeFamily.LARGE, ShapeFamily.FULL), grid)
    }
}
