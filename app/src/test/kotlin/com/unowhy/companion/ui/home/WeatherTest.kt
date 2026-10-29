package com.unowhy.companion.ui.home

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class WeatherTest {

    private val paris = TimeZone.getTimeZone("Europe/Paris")

    /** Échéance au format de MET Norway (locationforecast 2.0 compact). */
    private fun step(time: String, temperature: Double, symbol: String?, period: String = "next_1_hours") = JSONObject()
        .put("time", time)
        .put(
            "data",
            JSONObject()
                .put("instant", JSONObject().put("details", JSONObject().put("air_temperature", temperature)))
                .apply { if (symbol != null) put(period, JSONObject().put("summary", JSONObject().put("symbol_code", symbol))) },
        )

    private fun forecast(vararg steps: JSONObject) =
        JSONObject().put("properties", JSONObject().put("timeseries", JSONArray().apply { steps.forEach { put(it) } }))

    @Test
    fun `temps actuel, min et max du jour, puis les jours suivants`() {
        val json = forecast(
            step("2026-10-29T12:00:00Z", 13.3, "partlycloudy_day"),
            step("2026-10-29T15:00:00Z", 15.1, "clearsky_day"),
            step("2026-10-29T21:00:00Z", 9.0, "clearsky_night"),
            step("2026-10-30T06:00:00Z", 6.5, "cloudy", "next_6_hours"),
            step("2026-10-30T12:00:00Z", 12.0, "lightrain", "next_6_hours"),
            step("2026-10-31T12:00:00Z", 14.0, "fair_day", "next_6_hours"),
        )
        val f = Weather.parse(json, paris)
        assertEquals(13.3, f.temperature, 0.0)
        assertEquals(2, f.code)
        assertEquals(9.0, f.min, 0.0)
        assertEquals(15.1, f.max, 0.0)
        assertEquals(listOf("2026-10-30", "2026-10-31"), f.days.map { it.date })
        // Mi-journée (12 h UTC = 13 h à Paris) : pluie faible, pas les nuages du matin.
        assertEquals(listOf(61, 1), f.days.map { it.code })
        assertEquals(6.5, f.days[0].min, 0.0)
        assertEquals(12.0, f.days[0].max, 0.0)
    }

    @Test
    fun `les jours suivent le fuseau de la tablette, pas l'UTC`() {
        // 23 h UTC le 29 = minuit et demi à Paris le 30 (heure d'hiver : UTC+1).
        val f = Weather.parse(forecast(step("2026-10-29T22:00:00Z", 8.0, "fog"), step("2026-10-29T23:00:00Z", 7.0, "fog")), paris)
        assertEquals(8.0, f.max, 0.0)
        assertEquals(listOf("2026-10-30"), f.days.map { it.date })
    }

    @Test
    fun `au plus 4 jours suivants`() {
        val steps = (0 until 9).map { step("2026-11-%02dT12:00:00Z".format(it + 1), 10.0, "cloudy", "next_6_hours") }
        assertEquals(4, Weather.parse(forecast(*steps.toTypedArray()), paris).days.size)
    }

    @Test
    fun `temps de MET Norway vers les codes de l'OMM`() {
        assertEquals(0, Weather.codeOf("clearsky_night"))
        assertEquals(1, Weather.codeOf("fair_day"))
        assertEquals(2, Weather.codeOf("partlycloudy_polartwilight"))
        assertEquals(3, Weather.codeOf("cloudy"))
        assertEquals(45, Weather.codeOf("fog"))
        assertEquals(61, Weather.codeOf("lightrain"))
        assertEquals(65, Weather.codeOf("heavyrain"))
        assertEquals(80, Weather.codeOf("lightrainshowers_day"))
        assertEquals(82, Weather.codeOf("heavyrainshowers_night"))
        assertEquals(67, Weather.codeOf("sleet"))
        assertEquals(85, Weather.codeOf("lightsleetshowers_day"))
        assertEquals(71, Weather.codeOf("lightsnow"))
        assertEquals(95, Weather.codeOf("heavyrainshowersandthunder_day"))
        assertEquals(-1, Weather.codeOf(null))
        assertEquals(-1, Weather.codeOf("inconnu"))
    }

    @Test
    fun `chaque code obtenu a une icone et un libelle connus`() {
        for (code in listOf(0, 1, 2, 3, 45, 61, 63, 65, 67, 71, 73, 75, 80, 82, 85, 95)) {
            assert(Weather.describe(code).text != com.unowhy.companion.R.string.sky_unknown) { "code $code" }
        }
    }
}
