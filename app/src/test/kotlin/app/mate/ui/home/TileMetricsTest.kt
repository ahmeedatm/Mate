package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMetricsTest {
    @Test
    fun `cotes normales sans le thème Contraste, grandes avec`() {
        assertSame(TileMetrics.NORMAL, TileMetrics.of(false))
        assertSame(TileMetrics.LARGE, TileMetrics.of(true))
    }

    @Test
    fun `les grandes touches restent des cibles de 48 dp au moins, avec un libelle plus grand`() {
        assertTrue(TileMetrics.LARGE.plateDp >= 48 && TileMetrics.LARGE.cellDp >= 48)
        assertTrue(TileMetrics.LARGE.plateDp > TileMetrics.NORMAL.plateDp)
        assertTrue(TileMetrics.LARGE.labelSp > TileMetrics.NORMAL.labelSp)
    }

    @Test
    fun `la case contient la plaque et deux lignes de libelle`() {
        for (m in listOf(TileMetrics.NORMAL, TileMetrics.LARGE)) {
            // Plaque, marge, puis deux lignes de libellé à 1,2 × la taille.
            val needed = m.plateDp + TileMetrics.LABEL_GAP_DP + 2 * m.labelSp * 1.2f
            assertTrue("cellule de ${m.cellDp} dp pour $needed", m.cellDp >= needed)
            assertTrue(m.columnDp >= m.plateDp)
        }
    }

    @Test
    fun `rayon en proportion de la plaque`() {
        assertEquals(22, TileMetrics.radiusFor(52))
        assertEquals(33, TileMetrics.radiusFor(78))
    }

    @Test
    fun `symbole lisible, plus de la moitié de la plaque, sur plaque couleur comme sans`() {
        for (plate in listOf(TileMetrics.NORMAL.plateDp, TileMetrics.LARGE.plateDp)) {
            val icon = plate - 2 * TileMetrics.symbolPadFor(plate)
            assertTrue("icône de $icon dp sur $plate", icon * 100 >= plate * 52)
            assertTrue("icône de $icon dp sur $plate", icon <= plate * 2 / 3)
        }
        assertEquals(28, TileMetrics.NORMAL.plateDp - 2 * TileMetrics.symbolPadFor(TileMetrics.NORMAL.plateDp))
    }
}
