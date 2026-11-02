package app.mate.ui.home

import app.mate.net.DiskPerf
import app.mate.net.GpuPerf
import app.mate.net.NetPerf
import app.mate.net.PcPerf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfChoiceTest {
    private val base = PcPerf(10, 50, 8.0, 16.0, null, null, null, null)

    @Test
    fun `sans choix, on garde l'affichage d'avant`() {
        val choice = PerfChoice.read(JSONObject(), "a")
        assertEquals(
            setOf(PerfOption.CPU, PerfOption.GPU, PerfOption.RAM, PerfOption.GPU_TEMP, PerfOption.GPU_FAN),
            choice,
        )
    }

    @Test
    fun `le choix tient par ordinateur`() {
        val config = JSONObject()
        PerfChoice.write(config, "a", setOf(PerfOption.CPU, PerfOption.NET_DOWN))
        PerfChoice.write(config, "b", setOf(PerfOption.RAM))
        val reread = JSONObject(config.toString())
        assertEquals(setOf(PerfOption.CPU, PerfOption.NET_DOWN), PerfChoice.read(reread, "a"))
        assertEquals(setOf(PerfOption.RAM), PerfChoice.read(reread, "b"))
        assertEquals(PerfChoice.DEFAULT, PerfChoice.read(reread, "c"))
    }

    @Test
    fun `un choix vide reste vide`() {
        val config = JSONObject()
        PerfChoice.write(config, "a", emptySet())
        assertTrue(PerfChoice.read(JSONObject(config.toString()), "a").isEmpty())
    }

    @Test
    fun `une cle inconnue est ignoree`() {
        val config = JSONObject("""{"perf":{"a":["cpu","nimporte","fps"]}}""")
        assertEquals(setOf(PerfOption.CPU, PerfOption.FPS), PerfChoice.read(config, "a"))
    }

    @Test
    fun `lisible seulement si l'ordinateur donne la mesure`() {
        assertTrue(PerfOption.CPU.readable(base))
        assertFalse(PerfOption.GPU.readable(base))
        assertFalse(PerfOption.CPU_FREQ.readable(base))
        assertFalse(PerfOption.DISK_FREE.readable(base))
        assertFalse(PerfOption.NET_UP.readable(base))
        assertFalse(PerfOption.CPU_TEMP.readable(base))
        assertFalse(PerfOption.FPS.readable(base))
        assertFalse(PerfOption.CPU_WATTS.readable(base))
        assertFalse(PerfOption.CPU_FAN.readable(base))
        assertFalse(PerfOption.GPU_WATTS.readable(base))
        val full = base.copy(
            cpuMhz = 3800,
            cpuTempC = 64,
            cpuWatts = 42.5,
            gpuWatts = 95.2,
            cpuFanRpm = 1180,
            fpsReadable = true,
            gpu = GpuPerf("RTX", 90, true, 70, null, 6000, 8192),
            disk = DiskPerf(100.0, 500.0, null),
            net = NetPerf(1, 2),
        )
        assertTrue(PerfOption.CPU_FREQ.readable(full))
        assertTrue(PerfOption.CPU_TEMP.readable(full))
        assertTrue(PerfOption.FPS.readable(full))
        assertTrue(PerfOption.CPU_WATTS.readable(full))
        assertTrue(PerfOption.GPU_WATTS.readable(full))
        assertTrue(PerfOption.CPU_FAN.readable(full))
        assertTrue(PerfOption.GPU_TEMP.readable(full))
        assertFalse(PerfOption.GPU_FAN.readable(full))
        assertTrue(PerfOption.GPU_VRAM.readable(full))
        assertTrue(PerfOption.DISK_FREE.readable(full))
        assertFalse(PerfOption.DISK_ACTIVITY.readable(full))
        assertTrue(PerfOption.NET_DOWN.readable(full))
    }

    @Test
    fun `nombres et debits`() {
        assertEquals("20,1", perfNumber(20.1))
        assertEquals("32", perfNumber(32.0))
        assertEquals("120", perfNumber(120.4))
        assertEquals("3,8", perfNumber(3.8))
        assertEquals(PerfRate(120.0, false), perfRate(120))
        assertEquals(PerfRate(3.4, true), perfRate(3400))
    }
}
