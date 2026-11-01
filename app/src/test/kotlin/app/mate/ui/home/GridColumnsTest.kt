package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Colonnes de la grille de l'Accueil, avec ou sans colonne vide au milieu (thème Spider-Man : c_spine). */
class GridColumnsTest {
    // Écran de la tablette : grille de 1148 dp (1280 - barre de 84 - 2 × 24 de marge), gouttière de 20.
    private val plain = GridColumns(start = 108, width = 1148, gap = 20, spine = 0, cols = 4)
    private val spider = GridColumns(start = 108, width = 1148, gap = 20, spine = 260, cols = 4)

    @Test
    fun `sans colonne vide, la grille est celle d'avant`() {
        assertEquals((1148 - 3 * 20) / 4, plain.cellW)
        assertEquals(listOf(108, 108 + 292, 108 + 2 * 292, 108 + 3 * 292), (0 until 4).map { plain.left(it) })
        assertEquals(2 * 272 + 20, plain.width(1, 2))
    }

    @Test
    fun `la colonne vide remplace la gouttiere du milieu, centree sur la grille`() {
        assertEquals((1148 - 2 * 20 - 260) / 4, spider.cellW)
        val leftEnd = spider.left(1) + spider.cellW
        assertEquals(260, spider.left(2) - leftEnd)
        assertEquals(108 + 1148 / 2, (leftEnd + spider.left(2)) / 2)
    }

    @Test
    fun `un widget a cheval sur le milieu la traverse`() {
        assertEquals(spider.left(3) + spider.cellW - spider.left(1), spider.width(1, 3))
        assertEquals(2 * spider.cellW + 20, spider.width(2, 2))
    }

    @Test
    fun `colonne sous le doigt, rien dans la colonne vide`() {
        assertEquals(0, spider.colAt(110f))
        assertEquals(1, spider.colAt((spider.left(1) + 5).toFloat()))
        assertNull(spider.colAt((spider.left(1) + spider.cellW + 100).toFloat()))
        assertEquals(2, spider.colAt((spider.left(2) + 1).toFloat()))
        assertNull(spider.colAt(50f))
        assertNull(spider.colAt(1300f))
    }

    @Test
    fun `position en cases continue, la colonne vide compte pour rien`() {
        assertEquals(0f, spider.cellsAt(108f), 0.001f)
        assertEquals(2f, spider.cellsAt(spider.left(2).toFloat()), 0.001f)
        // Dans la colonne vide, on reste au bord droit de la colonne de gauche.
        val edge = spider.cellsAt((spider.left(1) + spider.cellW).toFloat())
        assertEquals(edge, spider.cellsAt((spider.left(2) - 100).toFloat()), 0.001f)
        assertEquals(plain.cellsAt(700f), (700f - 108) / 292, 0.001f)
    }

    @Test
    fun `un widget traverse le milieu seulement s'il deborde sur les deux moities`() {
        assertEquals(true, spider.crossesMiddle(1, 2))
        assertEquals(true, spider.crossesMiddle(0, 4))
        assertEquals(false, spider.crossesMiddle(0, 2))
        assertEquals(false, spider.crossesMiddle(2, 2))
        assertEquals(false, plain.crossesMiddle(0, 4))
    }
}
