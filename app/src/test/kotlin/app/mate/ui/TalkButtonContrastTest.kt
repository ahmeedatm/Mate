package app.mate.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bouton Parler : le micro (teinte lue dans item_rail_talk.xml) se lit sur son fond, au repos (c_text) et à l'écoute (c_accent), dans chaque thème. */
class TalkButtonContrastTest {
    private val palettes = File("src/main/res/values/palettes.xml").readText()
    private val layout = File("src/main/res/layout/item_rail_talk.xml").readText()
    private val micTint = Regex("""android:id="@\+id/rail_icon"[^>]*?app:tint="\?attr/(c_\w+)"""", RegexOption.DOT_MATCHES_ALL)
        .find(layout)?.groupValues?.get(1) ?: error("teinte du micro absente de item_rail_talk.xml")

    private fun color(palette: String, name: String): String {
        val style = palettes.substringAfter("""<style name="$palette"""").substringBefore("</style>")
        return Regex("""<item name="$name">(#[0-9A-Fa-f]{8})</item>""").find(style)?.groupValues?.get(1)
            ?: error("$name absent de $palette")
    }

    private fun luminance(argb: String): Double {
        val rgb = argb.removePrefix("#").takeLast(6).chunked(2).map { it.toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    }

    private fun ratio(a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `le micro fait au moins 3 pour 1 sur le fond du bouton dans chaque theme`() {
        for (palette in listOf("Palette.Console", "Palette.ConsoleJour", "Palette.Contraste", "Palette.Hush")) {
            val mic = color(palette, micTint)
            for (bg in listOf("c_text", "c_accent")) {
                val r = ratio(mic, color(palette, bg))
                assertTrue("$palette : $micTint sur $bg = $r", r >= 3.0)
            }
        }
    }
}
