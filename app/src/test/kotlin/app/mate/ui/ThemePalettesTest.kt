package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Palettes des thèmes venus d'UnoMate (res/values/palettes.xml) : contrastes WCAG, polices présentes, motif propre à
 * chaque thème, et chaque attribut d'attrs.xml défini dans chaque palette de l'appli.
 */
class ThemePalettesTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()

    private val sober = listOf("CadranClair", "CadranSombre")
    private val retro = listOf("Cadran", "CadranNuit", "Retro")
    private val ambiances = listOf("Registre", "Studio", "Maison", "Tableau", "Terminal")
    private val imported = sober + retro + ambiances
    private val all = listOf("Console", "ConsoleJour", "Contraste", "Hush", "SpiderMan") + imported

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
    fun `chaque attribut d'attrs xml est defini dans chaque palette`() {
        val declared = Regex("""<attr name="(\w+)"""").findAll(File("src/main/res/values/attrs.xml").readText()).map { it.groupValues[1] }.toSet()
        for (name in all) assertEquals("manquants dans Palette.$name", emptySet<String>(), declared - values(name).keys)
    }

    @Test
    fun `les attributs propres a Mate prennent leur valeur dans la palette, pas dans celle de Console`() {
        for (name in imported) {
            val v = values(name)
            for (attr in listOf("c_label", "c_slider_thumb", "c_slider_track", "c_talk", "c_talk_live", "c_mx_plate")) {
                assertTrue("$name $attr : ${v[attr]}", v[attr]!!.startsWith("?attr/c_"))
            }
            assertEquals("@null", v["c_wallpaper"])
            assertEquals("0dp", v["c_spine"])
            assertEquals("false", v["c_backdrop"])
            assertEquals("false", v["c_mx_bare"])
            assertEquals(v["c_font_title"], v["c_font_label"])
        }
    }

    @Test
    fun `texte principal et secondaires au moins 4,5 sur fond, tuile et relief`() {
        for (name in imported) for (fg in listOf("c_text", "c_text_dim", "c_text_faint")) for (bg in backgrounds) {
            assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `couleurs de sens et couleur des ambiances au moins 4,5 sur fond, tuile et relief`() {
        // Comme à UnoMate, les ambiances seules : le rouge de Cadran nuit sur le relief (4,498) et l'ambre de Rétro (3,9 sur le fond)
        // restent à leur valeur d'origine.
        for (name in ambiances) for (fg in listOf("c_accent", "c_ok", "c_ko", "c_warn", "c_primary")) for (bg in backgrounds) {
            assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `texte sur les aplats et les fonds teintes au moins 4,5`() {
        for (name in imported) {
            for ((fg, bg) in listOf(
                "c_on_accent" to "c_accent", "c_on_primary" to "c_primary", "c_on_ko" to "c_ko", "c_accent" to "c_accent_soft",
                "c_ok" to "c_ok_soft", "c_text" to "c_surface_pressed",
            )) assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `signaux, teintes de fonction et traits des ambiances au moins 3 sur les fonds`() {
        for (name in ambiances) {
            val signals = values(name).keys.filter { it.startsWith("c_agent_") || it.startsWith("c_hue_") }
            assertTrue(signals.size >= 13)
            for (fg in signals) for (bg in backgrounds + "c_surface_pressed") {
                assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 3.0)
            }
            for (bg in backgrounds) assertTrue("$name c_graphic sur $bg", ratio(name, "c_graphic", bg) >= 3.0)
        }
    }

    @Test
    fun `chaque police citee existe`() {
        for (name in imported) for ((attr, value) in values(name)) {
            if (!value.startsWith("@font/")) continue
            assertTrue("$name $attr : $value", File("src/main/res/font/${value.removePrefix("@font/")}.ttf").isFile)
        }
    }

    @Test
    fun `chaque drawable cite existe`() {
        for (name in all) for ((attr, value) in values(name)) {
            if (!value.startsWith("@drawable/")) continue
            val found = File("src/main/res").listFiles()!!.filter { it.name.startsWith("drawable") }
                .any { dir -> dir.listFiles()!!.any { it.nameWithoutExtension == value.removePrefix("@drawable/") } }
            assertTrue("$name $attr : $value", found)
        }
    }

    @Test
    fun `chaque ambiance a un motif, les autres thèmes n'en ont aucun`() {
        for (name in ambiances) {
            val v = values(name)
            val pattern = v["c_pattern"]
            val grain = v.containsKey("c_grain_light") && v["c_grain_light"] != "#00000000"
            assertTrue("$name : un motif (c_pattern ou grain)", (pattern != null && pattern != "none") || grain)
            if (pattern != null && pattern != "none") assertTrue("$name c_pattern_color", color(name, "c_pattern_color") != "#00000000")
        }
        for (name in sober + listOf("Console", "ConsoleJour", "Contraste", "Hush", "SpiderMan")) {
            val v = values(name)
            assertEquals("$name c_flow", "false", v["c_flow"])
            assertEquals("$name c_pattern", "none", v["c_pattern"])
            assertEquals("$name c_grain_light", "#00000000", v["c_grain_light"])
            assertEquals("$name c_grain_dark", "#00000000", v["c_grain_dark"])
        }
    }

    @Test
    fun `Cadran, Cadran nuit et Retro ont leurs tubes et leur grain`() {
        for (name in retro) {
            val v = values(name)
            assertEquals("$name c_flow", "true", v["c_flow"])
            for (attr in listOf("c_flow_dark", "c_flow_red", "c_flow_orange", "c_flow_cream", "c_grain_light", "c_grain_dark")) {
                assertTrue("$name $attr", color(name, attr) != "#00000000")
            }
        }
    }

    @Test
    fun `les grands chiffres de Registre sont a chasse fixe`() {
        assertEquals("@font/ibm_plex_mono_300", values("Registre")["c_font_display"])
    }
}
