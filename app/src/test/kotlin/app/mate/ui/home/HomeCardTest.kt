package app.mate.ui.home

import app.mate.FakeContext
import app.mate.Prefs
import org.junit.Assert.assertEquals
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
    fun `le voile laisse voir le fond`() {
        assertTrue(HomeCard.VEIL_ALPHA in 1 until 0xFF)
    }
}
