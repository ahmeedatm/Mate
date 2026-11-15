package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorsLayoutTest {
    @Test
    fun plusLaFormeEstGrandePlusOnRegle() {
        assertTrue(monitorSections(ShapeFamily.PILL).isEmpty())
        assertEquals(listOf(MonitorSection.PICKER, MonitorSection.BRIGHTNESS), monitorSections(ShapeFamily.ROW))
        assertTrue(MonitorSection.FREQUENCY in monitorSections(ShapeFamily.BLOCK))
        assertEquals(MonitorSection.entries, monitorSections(ShapeFamily.LARGE))
    }

    @Test
    fun laPastilleMonteDUnQuartPuisRepartDuBas() {
        assertEquals(25, nextPillBrightness(0))
        assertEquals(75, nextPillBrightness(60))
        assertEquals(100, nextPillBrightness(80))
        assertEquals(25, nextPillBrightness(100))
    }

    @Test
    fun lesPreregagesDeLaNormeDonnentLeurTemperature() {
        assertEquals(6500, presetKelvin(5))
        assertEquals(9300, presetKelvin(8))
        assertNull(presetKelvin(11))
    }
}
