package com.unowhy.companion.ui.home

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
            assertTrue(m.cellDp >= m.plateDp + 2 * m.labelSp.toInt() * 12 / 10)
            assertTrue(m.columnDp >= m.plateDp)
        }
    }

    @Test
    fun `rayon en proportion de la plaque`() {
        assertEquals(22, TileMetrics.radiusFor(52))
        assertEquals(33, TileMetrics.radiusFor(78))
    }
}
