package app.mate.ui.home

import app.mate.Mode
import app.mate.net.HomeFeatures
import app.mate.ui.topbar.TopItem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutConfigTest {

    /** Disposition en place : Accueil (0), Réunion (1), rôles posés. */
    private fun current() = HomeLayout(mutableListOf(), mutableListOf("Accueil", "Réunion")).also {
        it.homeProfile = 0
        it.callProfile = 1
        it.rolesSet = true
    }

    private fun json(widgets: String = "[]", names: String = """["Accueil","Réunion"]""", extra: String = """"home":0,"call":1,"roles":true""") =
        JSONObject("""{"v":2,"names":$names,$extra,"widgets":$widgets}""")

    private fun w(t: String, s: String, p: Int, c: Int, r: Int, cfg: String = "{}") = """{"t":"$t","s":"$s","p":$p,"c":$c,"r":$r,"cfg":$cfg}"""

    private fun refused(raw: JSONObject, current: HomeLayout = current()): String {
        try {
            LayoutConfig.parseLayout(raw, current)
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("La disposition aurait dû être refusée")
        return ""
    }

    @Test
    fun `une disposition valide est relue telle quelle, config comprise`() {
        val raw = json("[${w("note", "M", 0, 0, 0, """{"text":"salut"}""")},${w("clock", "L", 1, 2, 2)}]")
        val layout = LayoutConfig.parseLayout(raw, current())
        assertEquals(2, layout.widgets.size)
        assertEquals("salut", layout.widgets[0].config.getString("text"))
        assertEquals(WidgetSize.L, layout.widgets[1].size)
        assertEquals(listOf<String?>("Accueil", "Réunion"), layout.names)
        assertEquals(1, layout.callProfile)
    }

    @Test
    fun `le Deck supprime est accepte`() {
        val layout = LayoutConfig.parseLayout(json(extra = """"home":0,"call":1,"deck":-1,"roles":true"""), current())
        assertEquals(-1, layout.deckProfile)
    }

    @Test
    fun `un widget inconnu est refuse`() {
        assertTrue(refused(json("[${w("nope", "S", 0, 0, 0)}]")).contains("Widget inconnu"))
    }

    @Test
    fun `une taille non permise pour le type est refusee`() {
        assertTrue(refused(json("[${w("water", "XL", 0, 0, 0)}]")).contains("pas permise"))
    }

    @Test
    fun `un widget hors de la grille est refuse`() {
        assertTrue(refused(json("[${w("clock", "L", 0, 3, 0)}]")).contains("sort de la grille"))
        assertTrue(refused(json("[${w("clock", "S", 0, 0, 4)}]")).contains("sort de la grille"))
    }

    @Test
    fun `un chevauchement est refuse, sur un autre profil non`() {
        assertTrue(refused(json("[${w("clock", "L", 0, 0, 0)},${w("note", "S", 0, 1, 1)}]")).contains("chevauche"))
        LayoutConfig.parseLayout(json("[${w("clock", "L", 0, 0, 0)},${w("note", "S", 1, 1, 1)}]"), current())
    }

    @Test
    fun `un widget sur un profil inexistant est refuse`() {
        assertTrue(refused(json("[${w("clock", "L", 5, 0, 0)}]")).contains("n'existe pas"))
    }

    @Test
    fun `au moins un profil, avec un nom`() {
        assertTrue(refused(json(names = "[]"), HomeLayout(mutableListOf(), 1)).contains("au moins un profil"))
        assertTrue(refused(json(names = """["Accueil","  "]""")).contains("n'a pas de nom"))
        assertTrue(refused(json(names = """["Accueil","${"x".repeat(31)}"]""")).contains("dépasse"))
    }

    @Test
    fun `l'Accueil et les roles sont conserves, Reunion supprime est accepte`() {
        val layout = LayoutConfig.parseLayout(json(extra = """"home":0,"roles":true"""), HomeLayout(mutableListOf(), 1).apply { ensureRoles("Réunion") })
        assertEquals(-1, layout.callProfile)
        assertTrue(refused(json(extra = """"home":0,"call":1,"roles":false""")).contains("rôles"))
        assertTrue(refused(json(extra = """"call":1,"roles":true""")).contains("home"))
        assertTrue(refused(json(extra = """"home":1,"call":1,"roles":true""")).contains("différents"))
    }

    @Test
    fun `sans roles poses, aucun profil n'est impose sauf l'Accueil`() {
        val layout = LayoutConfig.parseLayout(json(names = """["Bureau"]""", extra = """"home":0"""), HomeLayout(mutableListOf(), 1))
        assertEquals(-1, layout.callProfile)
        assertFalse(layout.rolesSet)
    }

    @Test
    fun `l'ecran de jeu et sa liste sont relus, ils peuvent etre l'Accueil`() {
        val raw = json(extra = """"home":0,"call":1,"roles":true,"gameScreen":0,"gameApps":["Steam"," Minecraft "]""")
        val layout = LayoutConfig.parseLayout(raw, current())
        assertEquals(0, layout.gameProfile)
        assertEquals(listOf("Steam", "Minecraft"), layout.gameApps)
        val sent = JSONObject(layout.toJson())
        assertEquals(0, sent.getInt("gameScreen"))
        assertEquals("Minecraft", sent.getJSONArray("gameApps").getString(1))
    }

    @Test
    fun `sans ecran de jeu dans la disposition, il n'y en a pas`() {
        val layout = LayoutConfig.parseLayout(json(), current())
        assertEquals(-1, layout.gameProfile)
        assertTrue(layout.gameApps.isEmpty())
    }

    @Test
    fun `un ecran de jeu inexistant ou une liste mal formee est refuse`() {
        assertTrue(refused(json(extra = """"home":0,"call":1,"roles":true,"gameScreen":5""")).contains("jeu"))
        assertTrue(refused(json(extra = """"home":0,"call":1,"roles":true,"gameApps":[3]""")).contains("jeu"))
        assertTrue(refused(json(extra = """"home":0,"call":1,"roles":true,"gameScreen":1""")).contains("Réunion"))
        assertTrue(refused(json(extra = """"home":0,"call":1,"roles":true,"gameScreen":"0"""")).contains("jeu"))
    }

    @Test
    fun `bandeau du haut, cles connues et sans doublon`() {
        assertEquals(listOf(TopItem.CLOCK, TopItem.MUSIC), LayoutConfig.parseTopBar(JSONArray("""["clock","music"]""")))
        assertEquals(emptyList<TopItem>(), LayoutConfig.parseTopBar(JSONArray()))
        try {
            LayoutConfig.parseTopBar(JSONArray("""["clock","clock"]"""))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("double"))
        }
        try {
            LayoutConfig.parseTopBar(JSONArray("""["clock","zzz"]"""))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("zzz"))
        }
    }

    @Test
    fun `outils de la barre, seulement ceux de la barre`() {
        assertEquals(setOf(Mode.FOCUS), LayoutConfig.parseRailTools(JSONArray("""["focus"]""")))
        try {
            LayoutConfig.parseRailTools(JSONArray("""["camera"]"""))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("camera"))
        }
    }

    @Test
    fun `describe rend le catalogue avec offered, les tailles et les barres`() {
        val labels = LayoutConfig.Labels({ "w-${it.key}" }, { "s-${it.name}" }, { "t-${it.key}" }, { "o-${it.key}" })
        val data = LayoutConfig.describe(
            current(), "pc-1", HomeFeatures(agents = false, jira = true),
            listOf(TopItem.CLOCK), railLabels = false, railShown = listOf(Mode.FOCUS), labels = labels,
        )
        assertEquals("pc-1", data.getString("computerId"))
        assertEquals(4, data.getInt("cols"))
        val catalog = data.getJSONArray("catalog")
        val byKey = (0 until catalog.length()).associate { catalog.getJSONObject(it).let { o -> o.getString("key") to o } }
        assertEquals(WidgetType.entries.size, byKey.size)
        assertFalse(byKey.getValue("agents").getBoolean("offered"))
        assertTrue(byKey.getValue("jira").getBoolean("offered"))
        assertEquals("w-clock", byKey.getValue("clock").getString("title"))
        val first = byKey.getValue("clock").getJSONArray("sizes").getJSONObject(0)
        assertEquals("L", first.getString("key"))
        assertEquals(2, first.getInt("w"))
        assertEquals(2, first.getInt("h"))
        assertEquals(WidgetSize.entries.size, data.getJSONArray("sizes").length())
        val top = data.getJSONObject("topBar")
        assertEquals("clock", top.getJSONArray("items").getJSONObject(0).getString("key"))
        val addable = (0 until top.getJSONArray("addable").length()).map { top.getJSONArray("addable").getJSONObject(it).getString("key") }
        assertFalse("clock" in addable)
        assertFalse("agents" in addable)
        val rail = data.getJSONObject("rail")
        assertFalse(rail.getBoolean("labels"))
        assertEquals("focus", rail.getJSONArray("tools").getJSONObject(0).getString("key"))
        assertEquals("docs", rail.getJSONArray("available").getJSONObject(0).getString("key"))
        assertEquals(2, data.getJSONObject("layout").getJSONArray("names").length())
    }
}
