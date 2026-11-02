package app.mate.ui.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirQualityTest {

    @Test
    fun `l'indice europeen se range par tranches de 20`() {
        assertEquals(AirQuality.Level.GOOD, AirQuality.levelOf(0))
        assertEquals(AirQuality.Level.GOOD, AirQuality.levelOf(20))
        assertEquals(AirQuality.Level.FAIR, AirQuality.levelOf(21))
        assertEquals(AirQuality.Level.MODERATE, AirQuality.levelOf(60))
        assertEquals(AirQuality.Level.POOR, AirQuality.levelOf(80))
        assertEquals(AirQuality.Level.VERY_POOR, AirQuality.levelOf(81))
        assertEquals(AirQuality.Level.VERY_POOR, AirQuality.levelOf(140))
    }

    @Test
    fun `l'indice est lu dans la mesure actuelle, arrondi`() {
        val json = JSONObject("""{"current":{"time":"2026-11-01T10:00","european_aqi":34.6}}""")
        assertEquals(35, AirQuality.parse(json))
    }

    @Test
    fun `sans mesure pour ce lieu, pas d'indice`() {
        assertNull(AirQuality.parse(JSONObject("""{"current":{"european_aqi":null}}""")))
        assertNull(AirQuality.parse(JSONObject("""{"current":{}}""")))
        assertNull(AirQuality.parse(JSONObject("""{}""")))
    }
}
