package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Plaque du mixeur : une seule couleur unie par thème (noir en sombre, fond du thème en clair), sans dégradé ni brossage. */
class MixerPlateTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()
    private val view = File("src/main/kotlin/app/mate/ui/mixer/MixerView.kt").readText()

    private fun plate(palette: String): String {
        val body = xml.substringAfter("""<style name="Palette.$palette"""").substringBefore("</style>")
        return Regex("""<item name="c_mx_plate">([^<]*)</item>""").find(body)?.groupValues?.get(1)
            ?: error("c_mx_plate absent de Palette.$palette")
    }

    @Test
    fun `themes sombres noir uni`() {
        for (name in listOf("Console", "Hush")) assertEquals(name, "#FF000000", plate(name))
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
