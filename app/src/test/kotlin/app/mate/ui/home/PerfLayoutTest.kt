package app.mate.ui.home

import app.mate.net.GpuPerf
import app.mate.net.PcPerf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val gpu = GpuPerf("GPU", usage = 80, dedicated = true, tempC = 70, fanRpm = null)
    private val all = setOf(PerfOption.CPU, PerfOption.GPU, PerfOption.RAM)

    @Test
    fun `la Pastille montre la mesure cochee la plus chargee`() {
        assertEquals(PillReading(PerfSeries.GPU, 80), pillReading(PcPerf(cpu = 40, ramPercent = 60, ramUsedGb = 1.0, ramTotalGb = 2.0, gpu = gpu), all))
        assertEquals(PillReading(PerfSeries.RAM, 60), pillReading(PcPerf(cpu = 40, ramPercent = 60, ramUsedGb = 1.0, ramTotalGb = 2.0, gpu = gpu), setOf(PerfOption.CPU, PerfOption.RAM)))
    }

    @Test
    fun `sans carte graphique lisible, elle n'est pas candidate, a egalite le processeur passe d'abord`() {
        assertEquals(PillReading(PerfSeries.CPU, 50), pillReading(PcPerf(cpu = 50, ramPercent = 50, ramUsedGb = 1.0, ramTotalGb = 2.0, gpu = null), all))
    }

    @Test
    fun `rien a montrer si aucune des trois mesures n'est cochee`() {
        assertNull(pillReading(PcPerf(cpu = 50, ramPercent = 50, ramUsedGb = 1.0, ramTotalGb = 2.0, gpu = gpu), setOf(PerfOption.GPU_TEMP, PerfOption.NET_UP)))
        assertNull(pillReading(PcPerf(cpu = 50, ramPercent = 50, ramUsedGb = 1.0, ramTotalGb = 2.0, gpu = null), setOf(PerfOption.GPU)))
    }
}
