package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorsApiTest {
    private val json = """
        {"monitors":[
          {"id":"D1","name":"MSI G24C4","primary":true,"width":1920,"height":1080,"hz":144,
           "modes":[{"width":1920,"height":1080,"hz":[144,120,60]},{"width":1280,"height":720,"hz":[60]}],
           "ddc":true,"brightness":{"value":40,"max":50},"contrast":{"value":70,"max":100},
           "preset":{"value":5,"options":[5,6,11]}},
          {"id":"D2","name":"","primary":false,"width":1920,"height":1200,"hz":60,"modes":[],"ddc":false}
        ]}
    """.trimIndent()

    @Test
    fun litLesEcransEtLeursReglages() {
        val list = MonitorsApi.parse(json)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("MSI G24C4", first.name)
        assertEquals(144, first.hz)
        assertEquals(listOf(144, 120, 60), first.frequencies())
        assertEquals(80, first.levels[MonitorControl.BRIGHTNESS]?.percent)
        assertNull(first.levels[MonitorControl.SHARPNESS])
        assertEquals(listOf(5, 6, 11), first.preset?.options)
        assertFalse(list[1].ddc)
        assertTrue(list[1].frequencies().isEmpty())
    }

    @Test
    fun unPourcentageRedevientLaValeurDeLEcran() {
        val level = MonitorLevel(value = 40, max = 50)
        assertEquals(25, level.toValue(50))
        assertEquals(50, level.toValue(100))
        assertEquals(0, level.toValue(-5))
    }
}
