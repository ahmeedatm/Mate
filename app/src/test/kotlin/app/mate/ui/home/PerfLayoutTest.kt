package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class PerfLayoutTest {
    @Test
    fun `les formes d'avant gardent leur mise en page`() {
        assertEquals(PerfLayout.FIGURES, perfLayout(WidgetSize.M))
        assertEquals(PerfLayout.GAUGES, perfLayout(WidgetSize.L))
        assertEquals(PerfLayout.BARS, perfLayout(WidgetSize.W))
    }

    @Test
    fun `une mise en page par famille`() {
        assertEquals(PerfLayout.PILL, perfLayout(WidgetSize.S))
        assertEquals(PerfLayout.BARS, perfLayout(WidgetSize.M3))
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) assertEquals("$size", PerfLayout.BARS, perfLayout(size))
        for (size in listOf(WidgetSize.L3, WidgetSize.L23, WidgetSize.L33)) assertEquals("$size", PerfLayout.GAUGES, perfLayout(size))
        for (size in listOf(WidgetSize.T, WidgetSize.T3, WidgetSize.XL, WidgetSize.XL3, WidgetSize.FULL)) assertEquals("$size", PerfLayout.FIGURES, perfLayout(size))
    }
}
