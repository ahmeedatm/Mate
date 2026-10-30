package app.mate.remote

import app.mate.FakeContext
import app.mate.Prefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordre `settings` envoyé par la carte Tablette de la page de l'ordinateur. */
class TabletSettingsTest {

    private val context = FakeContext()

    @Test
    fun `enregistre seulement les reglages presents`() {
        assertFalse(TabletSettings.save(context, JSONObject().put("brightness", 0.3).put("nightDim", false)))
        assertEquals(0.3f, Prefs.brightness(context))
        assertFalse(Prefs.nightDim(context))
        assertTrue(Prefs.focusDnd(context))
    }

    @Test
    fun `luminosite gardee au-dessus du minimum`() {
        TabletSettings.save(context, JSONObject().put("brightness", 0.0))
        assertEquals(0.05f, Prefs.brightness(context))
    }

    @Test
    fun `un nouveau theme demande de recreer l'ecran, le meme non`() {
        assertTrue(TabletSettings.save(context, JSONObject().put("theme", "RETRO")))
        assertEquals("RETRO", Prefs.theme(context))
        // Ordre renvoyé après la recréation (résultat perdu) : rien à refaire.
        assertFalse(TabletSettings.save(context, JSONObject().put("theme", "RETRO")))
    }

    @Test
    fun `theme inconnu refuse sans rien changer`() {
        assertThrows(IllegalStateException::class.java) {
            TabletSettings.save(context, JSONObject().put("theme", "NEON").put("nightDim", false))
        }
        assertTrue(Prefs.nightDim(context))
    }

    @Test
    fun `un theme de persona est accepte depuis la page de l'ordinateur, hors demo`() {
        assertTrue(TabletSettings.save(context, JSONObject().put("theme", "TERMINAL")))
        assertEquals("TERMINAL", TabletSettings.read(context, 0.5f, 0.4f).getString("theme"))
    }

    @Test
    fun `lit les reglages, luminosite du systeme tant qu'aucune n'est choisie`() {
        val first = TabletSettings.read(context, systemBrightness = 0.5f, volume = 0.4f)
        assertEquals("CLAIR", first.getString("theme"))
        assertEquals(0.5, first.getDouble("brightness"), 0.001)
        assertEquals(0.4, first.getDouble("volume"), 0.001)
        assertTrue(first.getBoolean("nightDim"))
        assertTrue(first.getBoolean("focusDnd"))
        Prefs.setBrightness(context, 0.8f)
        assertEquals(0.8, TabletSettings.read(context, 0.5f, 0.4f).getDouble("brightness"), 0.001)
    }
}
