package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Plaque du mixeur : une seule couleur unie par thème (noir en sombre, fond du thème en clair), sans dégradé ni brossage ;
 * transparente dans Hush, où la table est nue (ni vis ni filet après la dernière tranche) comme ses widgets.
 */
class MixerPlateTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()
    private val view = File("src/main/kotlin/app/mate/ui/mixer/MixerView.kt").readText()

    private fun item(palette: String, attr: String): String {
        val body = xml.substringAfter("""<style name="Palette.$palette"""").substringBefore("</style>")
        return Regex("""<item name="$attr">([^<]*)</item>""").find(body)?.groupValues?.get(1)
            ?: error("$attr absent de Palette.$palette")
    }

    private fun plate(palette: String) = item(palette, "c_mx_plate")

    @Test
    fun `console noir uni`() {
        assertEquals("#FF000000", plate("Console"))
    }

    @Test
    fun `hush transparent et nu`() {
        assertEquals("#00000000", plate("Hush"))
        assertEquals("true", item("Hush", "c_mx_bare"))
    }

    @Test
    fun `les autres themes gardent vis et filets`() {
        for (name in listOf("Console", "ConsoleJour", "Contraste")) {
            val body = xml.substringAfter("""<style name="Palette.$name"""").substringBefore("</style>")
            assertFalse(name, body.contains("""<item name="c_mx_bare">true</item>"""))
        }
    }

    @Test
    fun `themes clairs fond uni du theme`() {
        for (name in listOf("ConsoleJour", "Contraste")) assertEquals(name, "?attr/c_bg", plate(name))
    }

    @Test
    fun `plus de degrade ni de brossage`() {
        assertFalse(xml.contains("c_mx_plate_top") || xml.contains("c_mx_plate_bottom"))
        assertFalse(view.contains("brushLines") || view.contains("LinearGradient"))
    }
}
