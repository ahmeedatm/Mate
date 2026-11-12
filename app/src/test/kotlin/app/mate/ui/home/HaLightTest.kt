package app.mate.ui.home

import app.mate.net.HaEntity
import app.mate.net.HaLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaLightTest {
    private val opaque = -0x1000000
    private val white = opaque or 0xFFFFFF
    private val ink = opaque or 0x141413
    private val accent = opaque or 0xA34A2B

    private fun light(on: Boolean, brightness: Int?, rgb: Int?, available: Boolean = true) =
        HaEntity("light.salon", "Salon", isScene = false, on = on, available = available, light = HaLight(color = rgb != null, brightness = brightness, rgb = rgb))

    @Test
    fun `la luminosite ne s'affiche que pour une lumiere allumee qui la donne`() {
        assertEquals(71, haBrightness(light(on = true, brightness = 71, rgb = null)))
        assertNull(haBrightness(light(on = false, brightness = null, rgb = null)))
        assertNull(haBrightness(HaEntity("light.applique", "Applique", isScene = false, on = true, available = true)))
        assertNull(haBrightness(light(on = true, brightness = 50, rgb = null, available = false)))
    }

    @Test
    fun `l'icone prend la couleur de la lampe allumee, sinon l'accent`() {
        assertEquals(opaque or 0x5078FF, haTint(light(on = true, brightness = 50, rgb = 0x5078FF), accent, white))
        assertEquals(accent, haTint(light(on = false, brightness = null, rgb = 0x5078FF), accent, white))
        assertEquals(accent, haTint(light(on = true, brightness = 50, rgb = null), accent, white))
    }

    @Test
    fun `une couleur de lampe trop pale sur le fond garde l'accent`() {
        assertEquals(accent, haTint(light(on = true, brightness = 50, rgb = 0xFFF1D6), accent, white))
    }

    @Test
    fun `le chiffre sur la jauge prend l'encre la plus lisible`() {
        assertEquals(ink, readableInk(opaque or 0xFFF1D6, ink, white))
        assertEquals(white, readableInk(opaque or 0x3A3AFF, ink, white))
    }
}
