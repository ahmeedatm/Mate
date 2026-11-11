package app.mate.ui.home

import app.mate.ui.home.TransportLines.Line
import app.mate.ui.home.TransportLines.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictoSpecTest {
    private val bg = 0xFFFFCD00.toInt()
    private val fg = 0xFF000000.toInt()
    private val themeBg = 0xFF112233.toInt()
    private val themeFg = 0xFFEEEEEE.toInt()

    private fun spec(mode: Mode) = PictoSpec.of(Line("X", mode, bg, fg), themeBg, themeFg)

    @Test
    fun `le metro est rond aux couleurs de la ligne`() {
        val s = spec(Mode.METRO)
        assertEquals(PictoSpec.Form.CIRCLE, s.form)
        assertEquals(1f, s.widthRatio, 0f)
        assertEquals(bg, s.fill)
        assertEquals(fg, s.text)
        assertFalse(s.bars)
    }

    @Test
    fun `RER et train sont un carre arrondi a 22 pour cent`() {
        for (m in listOf(Mode.RER, Mode.TRAIN)) {
            val s = spec(m)
            assertEquals(PictoSpec.Form.RECT, s.form)
            assertEquals(1f, s.widthRatio, 0f)
            assertEquals(0.22f, s.radiusRatio, 0f)
            assertEquals(bg, s.fill)
        }
    }

    @Test
    fun `le bus est un rectangle 1,45 fois plus large que haut`() {
        val s = spec(Mode.BUS)
        assertEquals(PictoSpec.Form.RECT, s.form)
        assertEquals(1.45f, s.widthRatio, 0f)
        assertEquals(0.12f, s.radiusRatio, 0f)
        assertEquals(bg, s.fill)
        assertEquals(fg, s.text)
    }

    @Test
    fun `le tram est blanc, barres et texte a la couleur de la ligne`() {
        val s = spec(Mode.TRAM)
        assertEquals(0xFFFFFFFF.toInt(), s.fill)
        assertEquals(bg, s.text)
        assertEquals(bg, s.barColor)
        assertTrue(s.bars)
    }

    @Test
    fun `une ligne inconnue prend les couleurs du theme`() {
        val s = PictoSpec.of(null, themeBg, themeFg)
        assertEquals(PictoSpec.Form.CIRCLE, s.form)
        assertEquals(themeFg, s.fill)
        assertEquals(themeBg, s.text)
        assertFalse(s.bars)
    }
}
