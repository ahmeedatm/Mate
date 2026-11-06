package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetricsWidgetTest {
    @Test
    fun `Pastille le premier chiffre, Ligne un par case, Colonne deux par case sous le titre`() {
        assertEquals(1, metricsShown(1, 1))
        assertEquals(2, metricsShown(2, 1))
        assertEquals(4, metricsShown(4, 1))
        assertEquals(3, metricsShown(1, 2))
        assertEquals(7, metricsShown(1, 4))
    }

    @Test
    fun `des 2 x 2, tous les chiffres`() {
        assertNull(metricsShown(2, 2))
        assertNull(metricsShown(4, 2))
    }
}
