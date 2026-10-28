package com.unowhy.companion.net

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
