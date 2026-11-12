package app.mate.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaDirectTest {

    private val states = """[
        {"entity_id":"light.salon","state":"on","attributes":{"friendly_name":"Salon","supported_color_modes":["hs"],"brightness":128,"rgb_color":[255,120,0]}},
        {"entity_id":"light.wled_bureau","state":"off","attributes":{"friendly_name":"WLED bureau","supported_color_modes":["rgb"]}},
        {"entity_id":"light.applique","state":"on","attributes":{"supported_color_modes":["onoff"]}},
        {"entity_id":"switch.wled_bureau_sync_send","state":"off","attributes":{"friendly_name":"Sync send"}},
        {"entity_id":"scene.film","state":"2026-11-12T10:00:00","attributes":{"friendly_name":"Film"}},
        {"entity_id":"media_player.tele","state":"standby","attributes":{"friendly_name":"Télé"}},
        {"entity_id":"media_player.apple_tv","state":"paused","attributes":{"friendly_name":"Apple TV"}},
        {"entity_id":"light.cave","state":"unavailable","attributes":{"friendly_name":"Cave","supported_color_modes":["hs"]}},
        {"entity_id":"sensor.temperature","state":"21","attributes":{}},
        {"entity_id":"light.Bad-Id","state":"on"}
    ]"""

    @Test
    fun `seuls les domaines pilotables sont gardes, dans l'ordre de Home Assistant`() {
        val ids = parseHaStates(states).map { it.id }
        assertEquals(
            listOf("light.salon", "light.wled_bureau", "light.applique", "switch.wled_bureau_sync_send", "scene.film", "media_player.tele", "media_player.apple_tv", "light.cave"),
            ids,
        )
    }

    @Test
    fun `memes regles que le backend pour l'etat, les scenes, la tele et les lumieres reglables`() {
        val byId = parseHaStates(states).associateBy { it.id }
        assertEquals(HaLight(color = true, brightness = 50, rgb = 0xFF7800), byId.getValue("light.salon").light)
        assertNull(byId.getValue("light.applique").light)
        assertEquals("light.applique", byId.getValue("light.applique").name)
        assertTrue(byId.getValue("scene.film").isScene)
        assertFalse(byId.getValue("media_player.tele").on)
        assertTrue(byId.getValue("media_player.apple_tv").on)
        val cave = byId.getValue("light.cave")
        assertFalse(cave.available)
        assertFalse(cave.on)
        assertNull(cave.light)
    }

    @Test
    fun `une reponse qui n'est pas une liste ne casse rien`() {
        assertEquals(emptyList<HaEntity>(), parseHaStates("""{"message":"oops"}"""))
    }

    @Test
    fun `le service appele suit le domaine, le reglage passe en pourcents et en RVB`() {
        assertEquals("/api/services/light/toggle", haDirectToggle("light.salon").first)
        assertEquals("/api/services/scene/turn_on", haDirectToggle("scene.film").first)
        assertEquals("light.salon", haDirectToggle("light.salon").second.getString("entity_id"))
        val body = haDirectLightBody("light.salon", 40, 0x0050FF)
        assertEquals(40, body.getInt("brightness_pct"))
        assertEquals("[0,80,255]", body.getJSONArray("rgb_color").toString())
        assertFalse(haDirectLightBody("light.salon", null, 0x0050FF).has("brightness_pct"))
    }

    @Test
    fun `une adresse du reseau local seulement autorise le HTTP`() {
        for (url in listOf("http://192.168.1.10:8123", "http://10.0.0.2:8123", "http://172.20.1.1:8123", "HTTP://192.168.1.10")) {
            assertTrue(url, isPrivateHttpUrl(url))
        }
        // Un nom (même .local) passe par le DNS : il peut mener ailleurs. Userinfo et « \ » sont lus autrement par la connexion.
        for (url in listOf(
            "http://8.8.8.8:8123", "http://172.32.0.1", "http://ha.exemple.fr", "https://192.168.1.10", "http://192.168.1.10.exemple.fr",
            "pas une url", "http://homeassistant.local:8123", "http://evil.example\\@192.168.1.10", "http://user:pass@192.168.1.10",
            "http://10.0.0.999", "http://192.168.1.10@evil.com",
        )) {
            assertFalse(url, isPrivateHttpUrl(url))
        }
    }

    @Test
    fun `le jeton ne part en clair que vers une adresse privee, et seulement si la case est cochee`() {
        assertTrue(Http("http://192.168.1.10:8123", "jeton", 1_000).cleartextRefused)
        assertFalse(Http("http://192.168.1.10:8123", "jeton", 1_000, allowPrivateCleartext = true).cleartextRefused)
        assertTrue(Http("http://8.8.8.8:8123", "jeton", 1_000, allowPrivateCleartext = true).cleartextRefused)
        assertFalse(Http("http://127.0.0.1:3000", "jeton", 1_000).cleartextRefused)
        assertFalse(Http("http://192.168.1.30", "", 1_000).cleartextRefused)
        assertTrue(Http("HTTP://8.8.8.8", "jeton", 1_000).cleartextRefused)
    }

    @Test
    fun `l'adresse saisie est verifiee avant d'enregistrer`() {
        assertEquals("http://192.168.1.10:8123", normalizeHaUrl(" 192.168.1.10:8123/ "))
        assertEquals("https://ha.exemple.fr", normalizeHaUrl("https://ha.exemple.fr/"))
        assertNull(haUrlProblem("https://ha.exemple.fr", allowHttp = false))
        assertNull(haUrlProblem("http://192.168.1.10:8123", allowHttp = true))
        assertEquals(app.mate.R.string.home_ha_need_http, haUrlProblem("http://192.168.1.10:8123", allowHttp = false))
        assertEquals(app.mate.R.string.home_ha_http_public, haUrlProblem("http://ha.exemple.fr", allowHttp = true))
        assertEquals(app.mate.R.string.home_ha_bad_url, haUrlProblem("ftp://192.168.1.10", allowHttp = true))
        assertEquals(app.mate.R.string.home_ha_bad_url, haUrlProblem("http://", allowHttp = true))
        assertEquals(app.mate.R.string.home_ha_bad_url, haUrlProblem("http://evil.example\\@192.168.1.10", allowHttp = true))
        assertEquals(app.mate.R.string.home_ha_bad_url, haUrlProblem("https://user:pass@ha.exemple.fr", allowHttp = false))
    }

    @Test
    fun `les reglages copies de l'ordinateur se lisent`() {
        val s = parseHaSettings("""{"url":"http://192.168.1.10:8123","token":"abc","allowHttp":true}""")
        assertEquals("http://192.168.1.10:8123", s.url)
        assertEquals("abc", s.token)
        assertTrue(s.allowHttp)
    }

    @Test
    fun `une WLED se reconnait a sa MAC dans json info`() {
        val d = parseWledInfo("192.168.1.42", """{"name":"WLED bureau","mac":"A1:B2:C3:D4:E5:F6","ver":"0.15.0"}""")!!
        assertEquals(WledDevice("wled:a1b2c3d4e5f6", "192.168.1.42", "WLED bureau"), d)
        assertNull(parseWledInfo("192.168.1.42", """{"name":"pas une WLED"}"""))
        assertNull(parseWledInfo("192.168.1.42", "<html>"))
    }

    @Test
    fun `une adresse de WLED saisie doit etre une IP du reseau local, sans chemin`() {
        assertTrue(isWledHost("192.168.1.42"))
        assertTrue(isWledHost("10.0.2.2:3971"))
        for (h in listOf("evil.com", "8.8.8.8", "192.168.1.1/admin?", "a@192.168.1.1", "192.168.1.1\\x", "", "wled.local")) assertFalse(h, isWledHost(h))
    }
}
