package app.mate.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrightnessTest {

    @Test
    fun `dans le noir l'ecran reste au minimum lisible`() {
        assertEquals(AutoBrightness.DARK_LEVEL, AutoBrightness.level(0f), 0.001f)
        assertEquals(AutoBrightness.DARK_LEVEL, AutoBrightness.level(2f), 0.001f)
    }

    @Test
    fun `en plein jour l'ecran est au maximum`() {
        assertEquals(1f, AutoBrightness.level(1_000f), 0.001f)
        assertEquals(1f, AutoBrightness.level(50_000f), 0.001f)
    }

    @Test
    fun `plus la piece est claire plus l'ecran est lumineux`() {
        val levels = listOf(5f, 20f, 100f, 300f, 800f).map { AutoBrightness.level(it) }
        assertEquals(levels.sorted(), levels)
        assertTrue(levels.first() < levels.last())
    }

    @Test
    fun `une valeur de capteur invalide donne le minimum`() {
        assertEquals(AutoBrightness.DARK_LEVEL, AutoBrightness.level(-3f), 0.001f)
        assertEquals(AutoBrightness.DARK_LEVEL, AutoBrightness.level(Float.NaN), 0.001f)
    }

    @Test
    fun `un petit ecart de lumiere ne change pas l'ecran`() {
        assertTrue(!AutoBrightness.worthChange(0.50f, 0.52f))
        assertTrue(AutoBrightness.worthChange(0.50f, 0.60f))
    }
}
