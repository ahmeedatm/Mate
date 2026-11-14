package app.mate.ui.cyberpunk

import app.mate.net.CyberpunkPoi
import app.mate.net.CyberpunkServerPin
import app.mate.net.PoiCategory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Map3dBridgeTest {
    private val colors = PoiCategory.entries.associateWith { "#ffcc00" } + mapOf(null to "#4ef2f5")
    private val pins = listOf(
        CyberpunkServerPin("3", -1200.0, 300.0, 20.0, null, tracked = false),
        CyberpunkServerPin("4", -900.0, 330.0, 0.0, "Planque", tracked = true),
    )

    @Test
    fun `joueur a pied ou en vehicule, cap du jeu garde`() {
        val p = Map3dBridge.player(1.0, 2.0, 3.0, 45.0, vehicle = true, speed = 12.5)
        assertEquals(1.0, p.getDouble("x"), 0.0)
        assertEquals(45.0, p.getDouble("yaw"), 0.0)
        assertEquals(45.0, p.getDouble("camYaw"), 0.0)
        assertTrue(p.getBoolean("vehicle"))
        assertEquals(12.5, p.getDouble("speed"), 0.0)
        assertEquals(0.0, Map3dBridge.player(0.0, 0.0, null, 0.0, vehicle = false, speed = null).getDouble("speed"), 0.0)
    }

    @Test
    fun `reperes, points d'interet et objectif deviennent des blips de la legende`() {
        val pois = listOf(
            CyberpunkPoi("p1", 10.0, 20.0, PoiCategory.SHOP, "gun", "Armurerie", dim = false),
            CyberpunkPoi("p2", 30.0, 40.0, PoiCategory.GIG, null, null, dim = true),
        )
        val blips = Map3dBridge.blips(pins, pois, objective = Pair(5.0, 6.0), objectiveLabel = "Rejoindre Judy", colors = colors, pinLabel = "Repère")
        val ids = (0 until blips.length()).map { blips.getJSONObject(it).getString("id") }
        assertEquals(listOf("pin:3", "pin:4", "poi:p1", "poi:p2", "quest"), ids)
        val pin = blips.getJSONObject(1)
        assertEquals("pin", pin.getString("category"))
        assertEquals("Planque", pin.getString("label"))
        assertEquals("Repère", blips.getJSONObject(0).getString("label"))
        val shop = blips.getJSONObject(2)
        assertEquals("shop", shop.getString("category"))
        assertEquals("store", shop.getString("icon"))
        assertEquals("Armurerie", shop.getString("label"))
        assertTrue(blips.getJSONObject(4).getBoolean("pulse"))
        assertEquals("Rejoindre Judy", blips.getJSONObject(4).getString("label"))
        assertFalse(blips.getJSONObject(3).has("label"))
    }

    @Test
    fun `destination, le repere suivi, sinon l'objectif de la quete, sinon rien`() {
        assertEquals(-900.0, Map3dBridge.waypoint(pins, Pair(5.0, 6.0))!!.getDouble("x"), 0.0)
        val quest = Map3dBridge.waypoint(pins.map { it.copy(tracked = false) }, Pair(5.0, 6.0))!!
        assertEquals(6.0, quest.getDouble("y"), 0.0)
        assertNull(Map3dBridge.waypoint(emptyList(), null))
    }

    @Test
    fun `destination posee sur la carte, nouveau repere, repere existant ou retour a la quete`() {
        assertEquals(Map3dBridge.Action.NewPin(10.0, 20.0, 5.0), Map3dBridge.action(JSONObject("""{"x":10,"y":20,"z":5}"""), pins))
        assertEquals(Map3dBridge.Action.Track("3"), Map3dBridge.action(JSONObject("""{"x":-1200.5,"y":300.4,"z":0}"""), pins))
        assertEquals(Map3dBridge.Action.Clear("4"), Map3dBridge.action(JSONObject("""{"clear":true}"""), pins))
        assertEquals(Map3dBridge.Action.Clear(null), Map3dBridge.action(JSONObject("""{"clear":true}"""), pins.map { it.copy(tracked = false) }))
        assertEquals(Map3dBridge.Action.None, Map3dBridge.action(JSONObject("""{"x":"a"}"""), pins))
    }

    @Test
    fun `configuration, carte pleine en francais, sans minimap ni bouton fermer du jeu`() {
        val c = Map3dBridge.config(colors, labels = PoiCategory.entries.associateWith { it.key } + mapOf(null to "Repères"), locale = mapOf("legend" to "Légende"))
        assertFalse(c.getJSONObject("minimap").getBoolean("enabled"))
        assertTrue(c.getJSONObject("map").getBoolean("doubleClickWaypoint"))
        assertEquals("Légende", c.getJSONObject("locale").getString("legend"))
        val cats = c.getJSONArray("categories")
        assertEquals("pin", cats.getJSONObject(0).getString("id"))
        assertEquals(PoiCategory.entries.size + 1, cats.length())
    }

    @Test
    fun `fichiers de donnees a telecharger, absents ou d'empreinte differente`() {
        val remote = mapOf("a.bin" to "1", "b.bin" to "2", "c.json" to "3")
        assertEquals(setOf("b.bin", "c.json"), Map3dBridge.missing(remote, mapOf("a.bin" to "1", "b.bin" to "x")))
    }
}
