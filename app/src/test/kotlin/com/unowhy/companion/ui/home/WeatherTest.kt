package com.unowhy.companion.ui.home

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherTest {

    @Test
    fun `le jour 0 donne min et max, les suivants vont dans days`() {
        val json = JSONObject(
            """
            {"current": {"temperature_2m": 12.4, "weather_code": 3},
             "daily": {"time": ["2026-10-28", "2026-10-29", "2026-10-30"],
                       "weather_code": [3, 61, 0],
                       "temperature_2m_min": [7.1, 8.0, 5.5],
                       "temperature_2m_max": [14.2, 13.0, 16.4]}}
            """,
        )
        val f = Weather.parse(json)
        assertEquals(12.4, f.temperature, 0.0)
        assertEquals(3, f.code)
        assertEquals(7.1, f.min, 0.0)
        assertEquals(14.2, f.max, 0.0)
        assertEquals(listOf("2026-10-29", "2026-10-30"), f.days.map { it.date })
        assertEquals(listOf(61, 0), f.days.map { it.code })
        assertEquals(16.4, f.days[1].max, 0.0)
    }

    @Test
    fun `sans weather_code par jour, le temps des jours suivants est 0`() {
        val json = JSONObject(
            """
            {"current": {"temperature_2m": 10, "weather_code": 1},
             "daily": {"time": ["2026-10-28", "2026-10-29"],
                       "temperature_2m_min": [4, 5], "temperature_2m_max": [11, 12]}}
            """,
        )
        assertEquals(listOf(0), Weather.parse(json).days.map { it.code })
    }
}
