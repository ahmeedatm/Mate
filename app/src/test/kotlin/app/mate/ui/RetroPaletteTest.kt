package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Palettes rétro (Cadran, Cadran nuit, Rétro ; res/values/palettes.xml) : tous les attributs de leur base, tubes et
 * grain posés, textes et accent lisibles, polices présentes.
 */
class RetroPaletteTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()

    private val bases = mapOf("Cadran" to "ConsoleJour", "CadranNuit" to "Console", "Retro" to "ConsoleJour")

    private fun style(name: String) = xml.substringAfter("""<style name="Palette.$name"""").substringBefore("</style>")

    private fun values(name: String) = Regex("""<item name="([\w:]+)">([^<]*)</item>""").findAll(style(name))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun color(palette: String, attr: String): String {
        var value = values(palette)[attr] ?: error("$attr absent de Palette.$palette")
        while (value.startsWith("?attr/")) value = values(palette)[value.removePrefix("?attr/")] ?: error("$value absent de Palette.$palette")
        check(Regex("#[0-9A-Fa-f]{8}").matches(value)) { "$palette $attr : $value n'est pas une couleur" }
        return value
    }

    private fun luminance(argb: String): Double {
        val rgb = argb.removePrefix("#").takeLast(6).chunked(2).map { it.toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    }

    private fun ratio(palette: String, a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(color(palette, a)), luminance(color(palette, b))).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `chaque palette retro a tous les attributs c_ de sa base, tubes et grain compris`() {
        for ((name, base) in bases) {
            val missing = values(base).keys.filter { it.startsWith("c_") } - values(name).keys
            assertEquals("Palette.$name", emptyList<String>(), missing)
            assertEquals("true", values(name)["c_flow"])
            for (attr in listOf("c_flow_dark", "c_flow_red", "c_flow_orange", "c_flow_cream", "c_grain_light", "c_grain_dark")) color(name, attr)
        }
    }

    @Test
    fun `textes au moins 4,5 sur fond, tuile et relief`() {
        for (name in bases.keys) for (fg in listOf("c_text", "c_text_dim", "c_text_faint")) {
            for (bg in listOf("c_bg", "c_surface", "c_surface_raised")) {
                assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
            }
        }
    }

    @Test
    fun `accent et texte sur les aplats au moins 4,5`() {
        for (name in bases.keys) {
            for ((fg, bg) in listOf("c_accent" to "c_bg", "c_accent" to "c_surface", "c_on_accent" to "c_accent", "c_on_primary" to "c_primary")) {
                assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
            }
        }
    }

    @Test
    fun `les polices des palettes retro existent`() {
        for (name in bases.keys) for (font in values(name).values.filter { it.startsWith("@font/") }) {
            assertTrue("$name : $font", File("src/main/res/font/${font.removePrefix("@font/")}.ttf").exists())
        }
    }
}
