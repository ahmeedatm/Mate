package app.mate

import app.mate.ui.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerConfigTest {

    private val context = FakeContext()
    private val prefs = context.getSharedPreferences(Prefs.FILE, 0)
    private val bureau = Computer("id-bureau", "Bureau", "http://192.168.1.20:3000", "jeton-b", "mac", lastUsedMs = 1)
    private val maison = Computer("id-maison", "Maison", "http://192.168.0.5:3000", "jeton-m", "windows", lastUsedMs = 2)

    @Test
    fun `chaque ordinateur garde son bandeau, sa barre et ses ecrans`() {
        Computers.save(context, bureau, makeActive = true)
        Computers.save(context, maison)
        Prefs.setTopWeather(context, "bureau-ville")
        Prefs.setRailLabels(context, false)
        prefs.edit().putString(HomeLayout.KEY + "@id-bureau", "x").apply()

        Computers.setActive(context, "id-maison")
        assertNull(Prefs.topWeather(context))
        assertTrue(Prefs.railLabels(context))
        assertFalse(Prefs.templatesOffered(context))
        assertNull(HomeLayout.raw(context))
        Prefs.setTopWeather(context, "maison-ville")

        Computers.setActive(context, "id-bureau")
        assertEquals("bureau-ville", Prefs.topWeather(context))
        assertFalse(Prefs.railLabels(context))
        assertEquals("x", HomeLayout.raw(context))
    }

    @Test
    fun `a la mise a jour, chaque ordinateur deja appaire recoit la config commune`() {
        prefs.edit()
            .putString(HomeLayout.KEY, "ecrans")
            .putString("top_weather", "paris")
            .putBoolean("rail_labels", false)
            .putStringSet("rail_hidden_tools", mutableSetOf("focus"))
            .putBoolean("templates_offered", true)
            .apply()
        Computers.save(context, bureau, makeActive = true)
        Computers.save(context, maison)

        for (id in listOf("id-bureau", "id-maison")) {
            Computers.setActive(context, id)
            assertEquals("ecrans", HomeLayout.raw(context))
            assertEquals("paris", Prefs.topWeather(context))
            assertFalse(Prefs.railLabels(context))
            assertEquals(listOf(Mode.COMPUTER), Prefs.railTools(context))
            assertTrue(Prefs.templatesOffered(context))
        }
        assertFalse(prefs.contains(HomeLayout.KEY))
    }

    @Test
    fun `un ordinateur appaire apres la mise a jour part de la config de base`() {
        Computers.save(context, bureau, makeActive = true)
        Prefs.setTopWeather(context, "paris")
        Prefs.setTemplatesOffered(context)

        Computers.save(context, maison, makeActive = true)
        assertNull(Prefs.topWeather(context))
        assertFalse(Prefs.templatesOffered(context))
    }

    @Test
    fun `oublier un ordinateur efface sa config`() {
        Computers.save(context, bureau, makeActive = true)
        Prefs.setTopWeather(context, "paris")
        Computers.remove(context, "id-bureau")
        assertTrue(prefs.all.keys.none { it.endsWith("@id-bureau") })
    }

    @Test
    fun `un ordinateur provisoire adopte garde sa config`() {
        val ancien = Computer(Computer.PROVISIONAL + bureau.url, "Mac", bureau.url, "jeton-b", "mac")
        Computers.save(context, ancien, makeActive = true)
        Prefs.setTopWeather(context, "paris")
        Computers.adopt(context, ancien, "id-bureau", "Bureau", "mac")
        assertEquals("paris", Prefs.topWeather(context))
    }
}
