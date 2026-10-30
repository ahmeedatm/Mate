package com.unowhy.unomate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Palettes des thèmes de démo (res/values/palettes.xml) : attributs complets, contrastes WCAG, polices présentes. */
class ThemePalettesTest {
    private val xml = File("src/main/res/values/palettes.xml").readText()

    private val names = listOf("Registre", "Studio", "Maison", "Tableau", "Terminal")

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
    fun `chaque attribut de Palette Cadran est defini dans chaque palette de demo`() {
        // Tubes et grain : propres à chaque thème (motif vérifié plus bas).
        val expected = values("Cadran").keys.filterNot { it.startsWith("c_flow") || it.startsWith("c_grain_") }.toSet()
        for (name in names) assertEquals("manquants dans $name", emptySet<String>(), expected - values(name).keys)
    }

    @Test
    fun `texte principal et secondaires au moins 4,5 sur fond, tuile et relief`() {
        for (name in names) for (fg in listOf("c_text", "c_text_dim", "c_text_faint")) for (bg in backgrounds) {
            assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `couleurs de sens et couleur du theme au moins 4,5 sur fond, tuile et relief`() {
        for (name in names) for (fg in listOf("c_accent", "c_ok", "c_ko", "c_warn", "c_primary")) for (bg in backgrounds) {
            assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `texte sur les aplats et les fonds teintes au moins 4,5`() {
        for (name in names) {
            for ((fg, bg) in listOf(
                "c_on_accent" to "c_accent", "c_on_primary" to "c_primary", "c_on_ko" to "c_ko", "c_accent" to "c_accent_soft",
                "c_ok" to "c_ok_soft", "c_text" to "c_surface_pressed",
            )) assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 4.5)
        }
    }

    @Test
    fun `signaux, teintes de fonction et traits au moins 3 sur les fonds`() {
        for (name in names) {
            val signals = values(name).keys.filter { it.startsWith("c_agent_") || it.startsWith("c_hue_") }
            assertTrue(signals.size >= 13)
            for (fg in signals) for (bg in backgrounds + "c_surface_pressed") {
                assertTrue("$name $fg sur $bg : ${ratio(name, fg, bg)}", ratio(name, fg, bg) >= 3.0)
            }
            for (bg in backgrounds) assertTrue("$name c_graphic sur $bg", ratio(name, "c_graphic", bg) >= 3.0)
        }
    }

    @Test
    fun `chaque police citee existe et chaque thème a un motif`() {
        for (name in names) {
            for ((attr, value) in values(name)) {
                if (!value.startsWith("@font/")) continue
                assertTrue("$name $attr : $value", File("src/main/res/font/${value.removePrefix("@font/")}.ttf").isFile)
            }
            val v = values(name)
            val pattern = v["c_pattern"]
            val grain = v.containsKey("c_grain_light") && v.containsKey("c_grain_dark")
            assertTrue("$name : un motif (c_pattern ou grain)", pattern != null || grain)
            if (pattern != null) color(name, "c_pattern_color")
        }
    }

    @Test
    fun `les grands chiffres de Registre sont a chasse fixe`() {
        assertEquals("@font/ibm_plex_mono_300", values("Registre")["c_font_display"])
    }
}
