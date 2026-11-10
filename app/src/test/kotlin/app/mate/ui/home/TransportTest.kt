package app.mate.ui.home

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TransportTest {

    /** 2026-11-10T14:00:00Z, repère fixe pour les attentes. */
    private val now = 1_794_319_200_000L

    /** Un passage au format SIRI de l'API stop-monitoring d'IDFM. */
    private fun visit(line: String, destination: String?, expected: String?, aimed: String? = null) = JSONObject()
        .put(
            "MonitoredVehicleJourney",
            JSONObject()
                .put("LineRef", JSONObject().put("value", line))
                .apply { if (destination != null) put("DestinationName", JSONArray().put(JSONObject().put("value", destination))) }
                .put(
                    "MonitoredCall",
                    JSONObject()
                        .apply { if (expected != null) put("ExpectedDepartureTime", expected) }
                        .apply { if (aimed != null) put("AimedDepartureTime", aimed) },
                ),
        )

    private fun monitoring(vararg visits: JSONObject) = JSONObject().put(
        "Siri",
        JSONObject().put(
            "ServiceDelivery",
            JSONObject().put(
                "StopMonitoringDelivery",
                JSONArray().put(
                    JSONObject().put("MonitoredStopVisit", JSONArray().apply { visits.forEach { put(it) } }),
                ),
            ),
        ),
    )

    @Test
    fun `les passages sont tries par attente et les passes retires`() {
        val json = monitoring(
            visit("STIF:Line::C01742:", "Boissy-Saint-Léger", "2026-11-10T14:07:00.000Z"),
            visit("STIF:Line::C01742:", "Boissy-Saint-Léger", "2026-11-10T13:58:00.000Z"),
            visit("STIF:Line::C01742:", "Marne-la-Vallée", "2026-11-10T14:03:00.000Z"),
        )
        val d = Transport.parse(json, now)
        assertEquals(listOf("Marne-la-Vallée", "Boissy-Saint-Léger"), d.map { it.destination })
        assertEquals(listOf(3L, 7L), d.map { it.waitMs / 60_000L })
    }

    @Test
    fun `l'heure prevue prime, sinon l'heure theorique`() {
        val json = monitoring(
            visit("STIF:Line::C01742:", "Boissy-Saint-Léger", expected = null, aimed = "2026-11-10T14:05:00.000Z"),
        )
        assertEquals(5L, Transport.parse(json, now).single().waitMs / 60_000L)
    }

    @Test
    fun `heure avec decalage horaire et heure sans fraction`() {
        val json = monitoring(
            // 15:05 +01:00 = 14:05 UTC → 5 min ; et une heure sans « .000 ».
            visit("STIF:Line::C01742:", "Boissy", "2026-11-10T15:05:00.000+01:00"),
            visit("STIF:Line::C01742:", "Boissy", "2026-11-10T14:06:00Z"),
        )
        assertEquals(listOf(5L, 6L), Transport.parse(json, now).map { it.waitMs / 60_000L })
    }

    @Test
    fun `l'heure d'arrivee sert au terminus, faute d'heure de depart`() {
        val json = JSONObject().put(
            "Siri",
            JSONObject().put(
                "ServiceDelivery",
                JSONObject().put(
                    "StopMonitoringDelivery",
                    JSONArray().put(
                        JSONObject().put(
                            "MonitoredStopVisit",
                            JSONArray().put(
                                JSONObject().put(
                                    "MonitoredVehicleJourney",
                                    JSONObject()
                                        .put("LineRef", JSONObject().put("value", "STIF:Line::C01742:"))
                                        .put("DestinationName", JSONArray().put(JSONObject().put("value", "Boissy")))
                                        .put("MonitoredCall", JSONObject().put("ExpectedArrivalTime", "2026-11-10T14:04:00.000Z")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(4L, Transport.parse(json, now).single().waitMs / 60_000L)
    }

    @Test
    fun `filtrer par ligne et par direction`() {
        val all = listOf(
            Transport.Departure("STIF:Line::C01742:", "Boissy-Saint-Léger", 180_000L),
            Transport.Departure("STIF:Line::C01742:", "Marne-la-Vallée", 240_000L),
            Transport.Departure("STIF:Line::C01371:", "La Défense", 120_000L),
        )
        assertEquals(2, Transport.pick(all, "STIF:Line::C01742:", null).size)
        assertEquals(listOf("Boissy-Saint-Léger"), Transport.pick(all, "STIF:Line::C01742:", "Boissy").map { it.destination })
    }

    @Test
    fun `l'attente en minutes est arrondie vers le bas, jamais negative`() {
        assertEquals(0, Transport.waitMinutes(59_000L))
        assertEquals(3, Transport.waitMinutes(239_000L))
        assertEquals(0, Transport.waitMinutes(-10_000L))
    }
}
