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
    fun `le sens vient du quai, sinon du sens IDFM`() {
        val onQuay = visit("STIF:Line::C01743:", "Mitry - Claye", "2026-11-10T14:05:00.000Z")
        onQuay.getJSONObject("MonitoredVehicleJourney").put("DirectionRef", JSONObject().put("value", "Aller"))
            .getJSONObject("MonitoredCall")
            .put("DepartureStopAssignment", JSONObject().put("ExpectedQuayRef", JSONObject().put("value", "STIF:StopPoint:Q:474018:")))
        val noQuay = visit("STIF:Line::C01371:", "La Défense", "2026-11-10T14:06:00.000Z")
        noQuay.getJSONObject("MonitoredVehicleJourney").put("DirectionRef", JSONObject().put("value", "Aller"))
        val bare = visit("STIF:Line::C01299:", "Roissypôle", "2026-11-10T14:07:00.000Z")
        val d = Transport.parse(monitoring(onQuay, noQuay, bare), now)
        assertEquals(listOf("STIF:StopPoint:Q:474018:", "Aller", ""), d.map { it.side })
    }

    @Test
    fun `une direction garde tous les trains du meme sens, quel que soit le terminus`() {
        // Sceaux, RER B vers le nord, un matin : les trains vont à CDG 2, le premier Mitry - Claye est dans 67 min.
        val north = "STIF:StopPoint:Q:474018:"
        val rerB = "STIF:Line::C01743:"
        val all = listOf(
            Transport.Departure(rerB, "Robinson", 60_000L, "STIF:StopPoint:Q:474019:"),
            Transport.Departure(rerB, "Aéroport Charles de Gaulle 2 (Terminal 2)", 180_000L, north),
            Transport.Departure(rerB, "Mitry - Claye", 67 * 60_000L, north),
        )
        val expected = listOf("Aéroport Charles de Gaulle 2 (Terminal 2)", "Mitry - Claye")
        // Réglage enregistré avec son sens…
        assertEquals(expected, Transport.pick(all, rerB, "Mitry - Claye", north).map { it.destination })
        // …ou ancien réglage sans sens : retrouvé par le terminus choisi.
        assertEquals(expected, Transport.pick(all, rerB, "Mitry - Claye", null).map { it.destination })
    }

    @Test
    fun `le terminus choisi reste affiche meme sans sens ou avec un sens perime`() {
        val rerB = "STIF:Line::C01743:"
        val all = listOf(
            Transport.Departure(rerB, "Robinson", 60_000L, "STIF:StopPoint:Q:474019:"),
            Transport.Departure(rerB, "Mitry - Claye", 90_000L),
            Transport.Departure(rerB, "Aéroport Charles de Gaulle 2 (Terminal 2)", 180_000L, "STIF:StopPoint:Q:474018:"),
        )
        // Le passage pour Mitry n'a pas de quai : il reste, avec les autres trains du même sens.
        assertEquals(
            listOf("Mitry - Claye", "Aéroport Charles de Gaulle 2 (Terminal 2)"),
            Transport.pick(all, rerB, "Mitry - Claye", "STIF:StopPoint:Q:474018:").map { it.destination },
        )
        // Sens enregistré qu'IDFM ne donne plus : on retombe sur le terminus.
        assertEquals(listOf("Mitry - Claye"), Transport.pick(all, rerB, "Mitry - Claye", "STIF:StopPoint:Q:999:").map { it.destination })
    }

    @Test
    fun `sans sens connu, la direction reste le terminus`() {
        val bus = "STIF:Line::C01299:"
        val all = listOf(
            Transport.Departure(bus, "Roissypôle", 60_000L),
            Transport.Departure(bus, "Nation - Trône", 120_000L),
        )
        assertEquals(listOf("Roissypôle"), Transport.pick(all, bus, "Roissypôle", null).map { it.destination })
    }

    @Test
    fun `l'attente en minutes est arrondie vers le bas, jamais negative`() {
        assertEquals(0, Transport.waitMinutes(59_000L))
        assertEquals(3, Transport.waitMinutes(239_000L))
        assertEquals(0, Transport.waitMinutes(-10_000L))
    }

    @Test
    fun `l'attente se lit comme un afficheur de quai`() {
        assertEquals(Transport.Wait(Transport.Wait.Kind.AT_STOP, 0, blink = false), Transport.waitOf(0))
        assertEquals(Transport.Wait(Transport.Wait.Kind.APPROACHING, 1, blink = true), Transport.waitOf(1))
        assertEquals(Transport.Wait(Transport.Wait.Kind.NUMBER, 2, blink = true), Transport.waitOf(2))
        assertEquals(Transport.Wait(Transport.Wait.Kind.NUMBER, 3, blink = false), Transport.waitOf(3))
        assertEquals(Transport.Wait(Transport.Wait.Kind.NUMBER, 12, blink = false), Transport.waitOf(12))
    }

    @Test
    fun `puis N min est le passage suivant de la meme ligne et de la meme destination`() {
        val a = Transport.Departure("STIF:Line::C01742:", "Boissy", 60_000L)
        val otherDest = Transport.Departure("STIF:Line::C01742:", "Marne", 120_000L)
        val otherLine = Transport.Departure("STIF:Line::C01371:", "Boissy", 150_000L)
        val b = Transport.Departure("STIF:Line::C01742:", "Boissy", 420_000L)
        val c = Transport.Departure("STIF:Line::C01742:", "Boissy", 900_000L)
        val all = listOf(a, otherDest, otherLine, b, c)
        assertEquals(b, Transport.following(all, a))
        assertEquals(c, Transport.following(all, b))
        assertEquals(null, Transport.following(all, c))
        assertEquals(null, Transport.following(all, otherDest))
    }

    @Test
    fun `la jauge d'approche se remplit sur les 10 dernieres minutes`() {
        assertEquals(0f, Transport.approach(15 * 60_000L), 0.001f)
        assertEquals(0f, Transport.approach(10 * 60_000L), 0.001f)
        assertEquals(0.5f, Transport.approach(5 * 60_000L), 0.001f)
        assertEquals(1f, Transport.approach(0L), 0.001f)
        assertEquals(1f, Transport.approach(-30_000L), 0.001f)
    }
}
