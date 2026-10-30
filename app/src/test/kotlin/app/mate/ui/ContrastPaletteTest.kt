package app.mate.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Palette.Contraste (res/values/palettes.xml) : rapports de contraste WCAG lus dans le fichier, sans appareil. */
class ContrastPaletteTest {
    private val palette = File("src/main/res/values/palettes.xml").readText()
        .substringAfter("""<style name="Palette.Contraste"""").substringBefore("</style>")
        .let { style -> Regex("""<item name="(c_\w+)">(#[0-9A-Fa-f]{8})</item>""").findAll(style).associate { it.groupValues[1] to it.groupValues[2] } }

    private fun color(name: String) = palette[name] ?: error("$name absent de Palette.Contraste")

    private fun luminance(argb: String): Double {
        val rgb = argb.removePrefix("#").takeLast(6).chunked(2).map { it.toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    }

    private fun ratio(a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(color(a)), luminance(color(b))).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val backgrounds = listOf("c_bg", "c_surface", "c_surface_raised")

    @Test
    fun `textes, accent et couleurs de sens au moins 7 sur fond, tuile et relief`() {
        for (fg in listOf("c_text", "c_text_dim", "c_text_faint", "c_accent", "c_ok", "c_ko", "c_warn")) {
            for (bg in backgrounds) assertTrue("$fg sur $bg : ${ratio(fg, bg)}", ratio(fg, bg) >= 7.0)
        }
    }

    @Test
    fun `signaux, traits et filets au moins 4,5 sur les quatre fonds, appui compris`() {
        for (fg in listOf("c_agent_wait", "c_agent_done", "c_agent_limit", "c_agent_work", "c_agent_monitor", "c_graphic", "c_stroke")) {
            for (bg in backgrounds + "c_surface_pressed") assertTrue("$fg sur $bg : ${ratio(fg, bg)}", ratio(fg, bg) >= 4.5)
        }
    }

    @Test
    fun `texte sur l'accent et sur les fonds teintes`() {
        assertTrue(ratio("c_on_accent", "c_accent") >= 7.0)
        assertTrue(ratio("c_accent", "c_accent_soft") >= 7.0)
        assertTrue(ratio("c_ok", "c_ok_soft") >= 7.0)
        assertTrue(ratio("c_text", "c_surface_pressed") >= 7.0)
    }

    @Test
    fun `chaque attribut de Console jour est defini`() {
        val xml = File("src/main/res/values/palettes.xml").readText()
        val jour = xml.substringAfter("""<style name="Palette.ConsoleJour"""").substringBefore("</style>")
        val contraste = xml.substringAfter("""<style name="Palette.Contraste"""").substringBefore("</style>")
        val names = { style: String -> Regex("""<item name="(c_\w+)">""").findAll(style).map { it.groupValues[1] }.toSet() }
        assertTrue("manquants : ${names(jour) - names(contraste)}", (names(jour) - names(contraste)).isEmpty())
    }
}
