package app.mate.ui.home

import app.mate.ui.home.TransportTraffic.Level
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransportTrafficTest {

    /** 2026-11-16T10:00:00Z, repère fixe pour les dates de validité. */
    private val now = 1_794_823_200_000L
    private val rerB = "STIF:Line::C01743:"
    private val rerA = "STIF:Line::C01742:"

    /** Un message au format SIRI de l'API general-message d'IDFM : texte court et texte long. */
    private fun message(
        short: String?,
        long: String? = null,
        channel: String = "Perturbation",
        until: String? = "2026-11-21T03:45:00.000Z",
    ) = JSONObject()
        .put("InfoChannelRef", JSONObject().put("value", channel))
        .apply { if (until != null) put("ValidUntilTime", until) }
        .put(
            "Content",
            JSONObject()
                .put("LineRef", JSONArray().put(JSONObject().put("value", rerB)))
                .put(
                    "Message",
                    JSONArray()
                        .apply { if (short != null) put(text("SHORT_MESSAGE", short)) }
                        .apply { if (long != null) put(text("TEXT_ONLY", long)) },
                ),
        )

    private fun text(type: String, value: String) =
        JSONObject().put("MessageType", type).put("MessageText", JSONObject().put("value", value).put("lang", "fr"))

    private fun delivery(vararg messages: JSONObject) = JSONObject().put(
        "Siri",
        JSONObject().put(
            "ServiceDelivery",
            JSONObject().put(
                "GeneralMessageDelivery",
                JSONArray().put(JSONObject().put("InfoMessage", JSONArray().apply { messages.forEach { put(it) } })),
            ),
        ),
    )

    @Test
    fun `lit le texte court d'une perturbation et la rattache à la ligne demandée`() {
        val out = TransportTraffic.parse(delivery(message("Le trafic est perturbé sur toute la ligne.", "Texte long.")), rerB, now)
        assertEquals(1, out.size)
        assertEquals(rerB, out[0].lineRef)
        assertEquals("Le trafic est perturbé sur toute la ligne.", out[0].text)
        assertEquals(Level.DISRUPTED, out[0].level)
    }

    @Test
    fun `prend le texte long quand le court manque, et met les retours à la ligne à plat`() {
        val out = TransportTraffic.parse(delivery(message(null, "Trafic interrompu.\nBus de remplacement.")), rerB, now)
        assertEquals("Trafic interrompu. Bus de remplacement.", out.single().text)
    }

    @Test
    fun `ignore les messages qui ne sont pas des perturbations, expirés, vides ou en double`() {
        val out = TransportTraffic.parse(
            delivery(
                message("Nouveaux horaires du week-end.", channel = "Information"),
                message("Trafic perturbé hier.", until = "2026-11-15T23:00:00.000Z"),
                message(" "),
                message("Le trafic est perturbé."),
                message("Le trafic est perturbé."),
            ),
            rerB,
            now,
        )
        assertEquals(listOf("Le trafic est perturbé."), out.map { it.text })
    }

    @Test
    fun `garde un message sans date de fin`() {
        assertEquals(1, TransportTraffic.parse(delivery(message("Trafic perturbé.", until = null)), rerB, now).size)
    }

    @Test
    fun `réponse sans messages ou mal formée donne une liste vide`() {
        assertEquals(0, TransportTraffic.parse(JSONObject(), rerB, now).size)
        assertEquals(0, TransportTraffic.parse(delivery(), rerB, now).size)
    }

    private fun level(text: String, at: Long = now) = TransportTraffic.level(text, at)

    @Test
    fun `la gravité se lit dans le texte d'IDFM`() {
        assertEquals(Level.DISRUPTED, level("Le trafic est perturbé sur toute la ligne (incident technique sur un train)."))
        assertEquals(Level.INTERRUPTED, level("Le trafic est interrompu entre Aulnay-sous-Bois et Mitry – Claye (travaux)."))
        assertEquals(Level.INTERRUPTED, level("Trafic INTERROMPU entre Nation et Vincennes."))
        assertEquals(Level.DISRUPTED, level("Trafic ralenti en raison d'un colis suspect."))
    }

    @Test
    fun `un message au futur est à venir, même s'il annonce une interruption`() {
        assertEquals(Level.SOON, level("La gare de Luxembourg sera fermée ce week-end."))
        assertEquals(Level.SOON, level("Le trafic sera interrompu ce week-end (travaux)."))
        // « sera rétabli » parle de la fin d'un incident en cours, « serait » n'est pas un futur.
        assertEquals(Level.DISRUPTED, level("Le trafic est perturbé, il sera rétabli vers 18 h."))
        assertEquals(Level.DISRUPTED, level("En raison d'un incident, des trains seront supprimés entre Nation et Vincennes."))
        assertEquals(Level.DISRUPTED, level("Trafic perturbé, le retour serait prévu vers 18 h."))
    }

    @Test
    fun `une date de début pas encore arrivée donne à venir, une date passée la vraie gravité`() {
        // now = lundi 16 novembre, 11 h à Paris.
        assertEquals(Level.SOON, level("Du 21 novembre au 22 novembre inclus, le trafic sera interrompu entre Aulnay-sous-Bois et Mitry – Claye (travaux)."))
        assertEquals(Level.SOON, level("Le 1er décembre, trafic interrompu entre Gare du Nord et Châtelet."))
        assertEquals(Level.SOON, level("Le 5 janvier, trafic interrompu entre Gare du Nord et Châtelet."))
        assertEquals(Level.DISRUPTED, level("Du 14 novembre au 18 novembre inclus, trafic perturbé (travaux)."))
        assertEquals(Level.INTERRUPTED, level("Le 16 novembre, trafic interrompu entre Gare du Nord et Châtelet."))
        assertEquals(Level.SOON, level("Du 21 au 22 novembre, trafic interrompu entre Aulnay-sous-Bois et Mitry - Claye."))
        assertEquals(Level.SOON, level("Travaux RER B, du lundi 14 au vendredi 18 décembre inclus dès 22:45, trafic interrompu."))
        // La date d'une fin annoncée n'est pas un début.
        assertEquals(Level.INTERRUPTED, level("Trafic interrompu entre Nation et Vincennes, retour prévu le 18 novembre."))
    }

    @Test
    fun `une période commencée en décembre est en cours en janvier`() {
        val january5 = now + 50L * 24 * 60 * 60_000L // 5 janvier 2027, 11 h
        assertEquals(Level.DISRUPTED, level("Du 20 décembre au 10 janvier, trafic perturbé (travaux).", january5))
    }

    @Test
    fun `des travaux de soirée sont à venir dans la journée, en cours le soir et la nuit`() {
        val text = "Du 16 novembre au 11 décembre inclus, du lundi au vendredi dès 22:45, trafic interrompu entre Châtelet - Les Halles et Mitry - Claye (tvx)."
        assertEquals(Level.SOON, level(text))
        val evening = now + 12 * 60 * 60_000L // 23 h à Paris
        assertEquals(Level.INTERRUPTED, level(text, evening))
        val night = now + 14 * 60 * 60_000L // 1 h du matin
        assertEquals(Level.INTERRUPTED, level(text, night))
        assertEquals(Level.SOON, level("Trafic interrompu à partir de 21h30 entre Nation et Vincennes.", now))
    }

    @Test
    fun `le plus grave d'une ligne passe devant, à venir compte le moins`() {
        val messages = listOf(
            TransportTraffic.Message(rerB, Level.SOON, "a"),
            TransportTraffic.Message(rerB, Level.INTERRUPTED, "b"),
            TransportTraffic.Message(rerA, Level.DISRUPTED, "c"),
        )
        assertEquals(Level.INTERRUPTED, TransportTraffic.worst(messages, rerB))
        assertEquals(Level.DISRUPTED, TransportTraffic.worst(messages, rerA))
        assertNull(TransportTraffic.worst(messages, "STIF:Line::C01371:"))
        assertEquals(Level.INTERRUPTED, TransportTraffic.worst(messages))
        assertEquals(listOf("b", "c", "a"), TransportTraffic.sorted(messages).map { it.text })
    }

    @Test
    fun `surveille la ligne choisie, sinon chaque ligne vue à l'arrêt, sans dépasser le plafond`() {
        val deps = listOf(rerB, rerA, rerB, "", "STIF:Line::C01371:").map { Transport.Departure(it, "X", 0L) }
        assertEquals(listOf(rerB), TransportTraffic.watched(deps, rerB))
        assertEquals(listOf(rerB, rerA, "STIF:Line::C01371:"), TransportTraffic.watched(deps, ""))
        val many = (1..20).map { Transport.Departure("STIF:Line::C0$it:", "X", 0L) }
        assertEquals(TransportTraffic.MAX_LINES, TransportTraffic.watched(many, null).size)
    }

    @Test
    fun `relit toutes les 5 minutes, ou tout de suite si les lignes changent`() {
        val lines = listOf(rerB)
        assertEquals(true, TransportTraffic.due(lastAt = 0L, lastLines = emptyList(), lines = lines, now = now))
        assertEquals(false, TransportTraffic.due(lastAt = now - 60_000L, lastLines = lines, lines = lines, now = now))
        assertEquals(true, TransportTraffic.due(lastAt = now - 5 * 60_000L, lastLines = lines, lines = lines, now = now))
        assertEquals(true, TransportTraffic.due(lastAt = now - 60_000L, lastLines = lines, lines = listOf(rerA), now = now))
        assertEquals(false, TransportTraffic.due(lastAt = 0L, lastLines = emptyList(), lines = emptyList(), now = now))
    }
}
