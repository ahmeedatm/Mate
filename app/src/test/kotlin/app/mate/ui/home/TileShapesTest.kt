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
    fun `dans un panneau a filet, la Colonne a des lignes plus basses et en montre au moins autant que sans panneau`() {
        assertEquals(9, TileShapes.stripFit(ShapeFamily.COLUMN, 300, 500, 1f, panel = true))
        // Panneau : marge de 6 dp et titre de 14 dp en moins, contre 12 + 28 (et des lignes de 56) avant ; jamais moins qu'à nu dès 3 lignes.
        val lost = 2 * TileShapes.PANEL_STRIP_PAD_DP + TileShapes.PANEL_CAPTION_DP
        for (height in 200..800) {
            val bare = TileShapes.stripFit(ShapeFamily.COLUMN, 300, height, 1f)
            val framed = TileShapes.stripFit(ShapeFamily.COLUMN, 300, height - lost, 1f, panel = true)
            if (bare >= 4) assertTrue("h=$height : $framed < $bare", framed >= bare)
        }
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
