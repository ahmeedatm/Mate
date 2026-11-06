package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentsWidgetTest {
    @Test
    fun `sessions listees sous les compteurs, plus la forme est haute plus il y en a`() {
        val rows = WidgetSize.entries.associateWith { agentsListRows(it) }
        // Pastille et Ligne : les compteurs seuls ; Colonne : à partir de 1 × 3.
        for (size in listOf(WidgetSize.S, WidgetSize.M, WidgetSize.M3, WidgetSize.W, WidgetSize.V)) assertEquals("$size", 0, rows[size])
        assertEquals(2, rows[WidgetSize.V3])
        assertEquals(4, rows[WidgetSize.V4])
        // Deux cases de large : compteurs et liste.
        assertEquals(4, rows[WidgetSize.L])
        assertEquals(6, rows[WidgetSize.L23])
        assertEquals(8, rows[WidgetSize.T])
    }
}
