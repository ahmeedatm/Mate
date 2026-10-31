package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemApiPerfTest {

    @Test
    fun `parsePerf lit une carte dediee complete`() {
        val p = parsePerf(
            """{"cpu":42,"ram":{"used":20.1,"total":32,"percent":63},"gpu":{"name":"RTX","usage":97,"dedicated":true,"tempC":81,"fanRpm":1850}}""",
        )
        assertEquals(42, p.cpu)
        assertEquals(63, p.ramPercent)
        assertEquals(20.1, p.ramUsedGb, 0.0001)
        assertEquals(32.0, p.ramTotalGb, 0.0001)
        val gpu = p.gpu!!
        assertEquals("RTX", gpu.name)
        assertEquals(97, gpu.usage)
        assertTrue(gpu.dedicated)
        assertEquals(81, gpu.tempC)
        assertEquals(1850, gpu.fanRpm)
    }

    @Test
    fun `parsePerf sans gpu (Mac)`() {
        assertNull(parsePerf("""{"cpu":5,"ram":{"used":8,"total":16,"percent":50}}""").gpu)
    }

    @Test
    fun `parsePerf puce integree sans temperature ni ventilateur`() {
        val gpu = parsePerf(
            """{"cpu":42,"ram":{"used":20.1,"total":32,"percent":63},"gpu":{"name":"Intel(R) Graphics","usage":5,"dedicated":false}}""",
        ).gpu
        assertNotNull(gpu)
        assertFalse(gpu!!.dedicated)
        assertNull(gpu.tempC)
        assertNull(gpu.fanRpm)
    }

    @Test
    fun `parsePerf ventilateur arrete reste a zero`() {
        val gpu = parsePerf(
            """{"cpu":10,"ram":{"used":8,"total":16,"percent":50},"gpu":{"name":"RTX","usage":3,"dedicated":true,"tempC":40,"fanRpm":0}}""",
        ).gpu!!
        assertEquals(0, gpu.fanRpm)
        assertEquals(40, gpu.tempC)
    }
}

class SystemApiPerfMoreTest {

    @Test
    fun `parsePerf lit frequence, memoire video, disque et reseau`() {
        val p = parsePerf(
            """{"cpu":42,"cpuMhz":3800,"ram":{"used":20.1,"total":32,"percent":63},
            "gpu":{"name":"RTX","usage":97,"dedicated":true,"vramUsedMb":6200,"vramTotalMb":8192},
            "disk":{"freeGb":120.5,"totalGb":931.5,"activity":12},"net":{"upKBps":120,"downKBps":3400}}""",
        )
        assertEquals(3800, p.cpuMhz)
        assertEquals(6200, p.gpu!!.vramUsedMb)
        assertEquals(8192, p.gpu!!.vramTotalMb)
        assertEquals(120.5, p.disk!!.freeGb, 0.0001)
        assertEquals(12, p.disk!!.activity)
        assertEquals(120, p.net!!.upKBps)
        assertEquals(3400, p.net!!.downKBps)
    }

    @Test
    fun `parsePerf sans les nouveaux champs les laisse absents`() {
        val p = parsePerf("""{"cpu":5,"ram":{"used":8,"total":16,"percent":50},"disk":{"freeGb":10,"totalGb":100}}""")
        assertNull(p.cpuMhz)
        assertNull(p.net)
        assertNull(p.disk!!.activity)
    }

    @Test
    fun `parsePerf lit les images par seconde, null sans jeu, absentes sans lecteur`() {
        val ram = """"ram":{"used":8,"total":16,"percent":50}"""
        val playing = parsePerf("""{"cpu":10,"fps":144,$ram}""")
        assertEquals(144, playing.fps)
        assertTrue(playing.fpsReadable)
        val idle = parsePerf("""{"cpu":10,"fps":null,$ram}""")
        assertNull(idle.fps)
        assertTrue(idle.fpsReadable)
        val none = parsePerf("""{"cpu":10,$ram}""")
        assertNull(none.fps)
        assertFalse(none.fpsReadable)
    }

    @Test
    fun `parsePerf lit la temperature du processeur, absente sinon`() {
        val ram = """"ram":{"used":8,"total":16,"percent":50}"""
        assertEquals(64, parsePerf("""{"cpu":10,"cpuTempC":64,$ram}""").cpuTempC)
        assertNull(parsePerf("""{"cpu":10,$ram}""").cpuTempC)
    }
}
