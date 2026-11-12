package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowCapsTest {
    @Test
    fun `une appli porte son nom en capitales et son nombre de fenetres`() {
        assertEquals("CHROME · 2", WindowCaps.appLabel("Chrome", 2))
        assertEquals("ÉDITEUR · 1", WindowCaps.appLabel("éditeur", 1))
    }

    @Test
    fun `un ecran porte son nom et son role en capitales`() {
        assertEquals("ÉCRAN 1 · PRINCIPAL", WindowCaps.screenTitle("Écran 1", "principal"))
        assertEquals("ÉCRAN 2 · SECONDAIRE", WindowCaps.screenTitle("écran 2", "Secondaire"))
    }

    @Test
    fun `les colonnes de capuchons suivent la largeur, une au moins`() {
        assertEquals(1, WindowCaps.columns(0, 2f))
        assertEquals(1, WindowCaps.columns(200, 2f))
        assertEquals(2, WindowCaps.columns(460, 2f))
        assertEquals(3, WindowCaps.columns(900, 2f))
        assertEquals(4, WindowCaps.columns(1000, 2f))
    }
}
