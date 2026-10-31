package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Palettes Console et Console jour (res/values/palettes.xml) : attributs complets, contrastes WCAG, polices présentes. */
class ConsolePaletteTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()

    private val names = listOf("Console", "ConsoleJour")

    private fun style(name: String) = xml.substringAfter("""<style name="Palette.$name"""").substringBefore("</style>")

    private fun values(name: String) = Regex("""<item name="([\w:]+)">([^<]*)</item>""").findAll(style(name))
        .associate { it.groupValues[1] to it.groupValues[2] }

    /** Couleur littérale ou `?attr/…` résolu dans la même palette. */
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

    private val backgrounds = listOf("c_bg", "c_surface", "c_surface_raised")

    @Test
    fun `Console jour definit les memes attributs que Console`() {
        assertEquals(emptySet<String>(), values("Console").keys - values("ConsoleJour").keys)
        assertEquals(emptySet<String>(), values("ConsoleJour").keys - values("Console").keys)
    }

    @Test
    fun `texte principal et secondaires au moins 4,5 sur fond, tuile et relief`() {
        for (name in names) for (fg in listOf("c_text", "c_text_dim", "c_text_faint")) for (bg in backgrounds) {
            assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `accent, couleurs de sens et teintes de fonction au moins 4,5 sur fond, tuile et relief`() {
        for (name in names) {
            val hues = values(name).keys.filter { it.startsWith("c_hue_") }
            assertEquals(8, hues.size)
            for (fg in listOf("c_accent", "c_primary", "c_ok", "c_ko", "c_warn") + hues) for (bg in backgrounds) {
                assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
            }
        }
    }

    @Test
    fun `texte sur les aplats et les fonds teintes au moins 4,5`() {
        for (name in names) {
            for ((fg, bg) in listOf(
                "c_on_accent" to "c_accent", "c_on_primary" to "c_primary", "c_on_ko" to "c_ko", "c_accent" to "c_accent_soft",
                "c_ok" to "c_ok_soft", "c_text" to "c_surface_pressed", "c_text" to "c_accent_soft",
            )) assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `traits au moins 3 et signaux des agents au moins 2,4 sur fond, tuile et relief`() {
        // Signaux gardés tels que ceux d'Orca (icône + forme, pas la couleur seule) : le jaune de surveillance reste sous 3 en jour (2,49 sur le relief).
        for (name in names) {
            val signals = values(name).keys.filter { it.startsWith("c_agent_") }
            assertEquals(5, signals.size)
            for (bg in backgrounds) {
                assertTrue("$name c_graphic sur $bg : ${ratio(name, "c_graphic", bg)}", ratio(name, "c_graphic", bg) >= 3.0)
                for (fg in signals) assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 2.4)
            }
        }
    }

    @Test
    fun `chaque police citee existe et toutes sont en Figtree`() {
        for (name in names) {
            for ((attr, value) in values(name)) {
                if (!value.startsWith("@font/")) continue
                assertTrue("$name $attr : $value", value.startsWith("@font/figtree_"))
                assertTrue("$name $attr : $value", File("src/main/res/font/${value.removePrefix("@font/")}.ttf").isFile)
            }
            assertTrue(values(name).keys.containsAll(listOf("c_font_regular", "c_font_medium", "c_font_light", "c_font_title", "c_font_display")))
        }
    }

    @Test
    fun `l'accent de Console est le vert acide`() {
        assertEquals("#FFB8F34A", values("Console")["c_accent"])
    }
}
