package app.mate.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Style des fenêtres LCD des widgets (c_lcd_overlay) : posé par Instrument et Instrument nuit, @null ailleurs. */
class LcdOverlayTest {
    private val palettes = File("src/main/res/values/palettes.xml").readText()
    private val attrs = Regex("""<attr name="(\w+)"""").findAll(File("src/main/res/values/attrs.xml").readText()).map { it.groupValues[1] }.toSet()

    private fun items(style: String) = Regex("""<item name="([\w:]+)">([^<]*)</item>""")
        .findAll(palettes.substringAfter("""<style name="$style"""").substringBefore("</style>"))
        .associate { it.groupValues[1] to it.groupValues[2] }

    private fun luminance(argb: String): Double {
        val rgb = argb.removePrefix("#").takeLast(6).chunked(2).map { it.toInt(16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
    }

    private fun ratio(a: String, b: String) = listOf(luminance(a), luminance(b)).sortedDescending().let { (hi, lo) -> (hi + 0.05) / (lo + 0.05) }

    @Test
    fun `Instrument et Instrument nuit posent leur style, les autres palettes aucun`() {
        assertEquals("@style/Lcd.Instrument", items("Palette.Instrument")["c_lcd_overlay"])
        assertEquals("@style/Lcd.InstrumentNuit", items("Palette.InstrumentNuit")["c_lcd_overlay"])
        val others = Regex("""<style name="Palette\.(\w+)"""").findAll(palettes).map { it.groupValues[1] }.toList() - listOf("Instrument", "InstrumentNuit")
        for (name in others) assertEquals("Palette.$name", "@null", items("Palette.$name")["c_lcd_overlay"])
    }

    @Test
    fun `le style LCD est un afficheur sombre, texte et orange lisibles, sur des attributs qui existent`() {
        for (name in listOf("Lcd.Instrument", "Lcd.InstrumentNuit")) {
            val v = items(name)
            assertTrue("$name : ${v.keys - attrs}", (v.keys - attrs).all { it.startsWith("android:") })
            // Nuit : noir pur, surface à peine au-dessus pour que « Sans filet » et « À même le fond » s'appliquent (HomeCard.look), rayon 12.
            val nuit = name == "Lcd.InstrumentNuit"
            if (nuit) assertEquals(name, "#FF000000", v["c_bg"]) else assertEquals(name, v["c_bg"], v["c_surface"])
            assertEquals(name, if (nuit) "@drawable/instrument_nuit_lcd_bg" else "@drawable/instrument_lcd_bg", v["c_home_card_bg"])
            assertEquals(name, "@font/departure_mono_400", v["c_font_light"])
            for (text in listOf("c_text", "c_text_dim", "c_accent", "c_hue_orange", "c_hue_cyan", "c_ok", "c_ko", "c_warn")) {
                assertTrue("$name $text : ${ratio(v[text]!!, v["c_surface"]!!)}", ratio(v[text]!!, v["c_surface"]!!) >= 4.5)
            }
        }
    }
}
