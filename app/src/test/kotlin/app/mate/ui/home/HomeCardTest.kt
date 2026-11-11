package app.mate.ui.home

import app.mate.FakeContext
import app.mate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardTest {

    private val context = FakeContext()

    @Test
    fun `voile par defaut, puis le choix est retenu`() {
        assertEquals(HomeCardStyle.VEIL, Prefs.homeCardStyle(context))
        Prefs.setHomeCardStyle(context, HomeCardStyle.BARE)
        assertEquals(HomeCardStyle.BARE, Prefs.homeCardStyle(context))
    }

    @Test
    fun `chaque style s'applique quand la carte se distingue du fond`() {
        for (style in HomeCardStyle.entries) assertEquals(style, HomeCard.look(style, bg = 0xFFFAF9F5.toInt(), surface = 0xFFFFFFFF.toInt()))
    }

    @Test
    fun `carte de la couleur du fond, seul son filet la montre, le theme la garde (Contraste eleve)`() {
        for (style in HomeCardStyle.entries) assertEquals(null, HomeCard.look(style, bg = 0xFFFFFFFF.toInt(), surface = 0xFFFFFFFF.toInt()))
    }

    @Test
    fun `un theme a filet le garde au voile seulement, Sans filet et A meme le fond le retirent`() {
        assertTrue(HomeCard.keepsOutline(themeOutline = true, style = HomeCardStyle.VEIL))
        assertFalse(HomeCard.keepsOutline(themeOutline = true, style = HomeCardStyle.FLAT))
        assertFalse(HomeCard.keepsOutline(themeOutline = true, style = HomeCardStyle.BARE))
        for (style in HomeCardStyle.entries) assertFalse(HomeCard.keepsOutline(themeOutline = false, style = style))
    }

    @Test
    fun `a meme le fond, la console de mixage n'a plus de plaque (#230)`() {
        val plate = 0xFF1A1A1A.toInt()
        assertEquals(0, HomeCard.plate(HomeCardStyle.BARE, plate))
        for (style in listOf(HomeCardStyle.VEIL, HomeCardStyle.FLAT, null)) assertEquals(plate, HomeCard.plate(style, plate))
    }

    @Test
    fun `sans plaque, un rail de la couleur du fond prend celle du filet pour rester visible`() {
        val bg = 0xFF16171A.toInt()
        val stroke = 0xFF3A3D44.toInt()
        val text = 0xFFF2F2F2.toInt()
        assertEquals(stroke, HomeCard.slot(HomeCardStyle.BARE, slot = bg, bg = bg, stroke = stroke))
        assertEquals(text, HomeCard.slot(HomeCardStyle.BARE, slot = text, bg = bg, stroke = stroke))
        for (style in listOf(HomeCardStyle.VEIL, HomeCardStyle.FLAT, null)) assertEquals(bg, HomeCard.slot(style, slot = bg, bg = bg, stroke = stroke))
    }

    @Test
    fun `le voile laisse voir le fond`() {
        assertTrue(HomeCard.VEIL_ALPHA in 1 until 0xFF)
    }
}
