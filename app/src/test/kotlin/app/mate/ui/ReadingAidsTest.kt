package app.mate.ui

import app.mate.FakeContext
import app.mate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAidsTest {
    private val context = FakeContext()

    @Test
    fun `transitions posees avec le reglage ou l'echelle d'Android a zero`() {
        assertFalse(Motion.transitions(setting = false, systemScale = 1f))
        assertFalse(Motion.transitions(setting = false, systemScale = 0.5f))
        assertTrue(Motion.transitions(setting = false, systemScale = 0f))
        assertTrue(Motion.transitions(setting = true, systemScale = 1f))
    }

    @Test
    fun `boucles arretees seulement par le reglage, pas par le kiosque`() {
        assertFalse(Motion.loops(setting = false))
        assertTrue(Motion.loops(setting = true))
    }

    @Test
    fun `reglages d'accessibilite coupes par defaut`() {
        assertFalse(Prefs.legibleFont(context))
        assertFalse(Prefs.reduceMotion(context))
        assertFalse(Prefs.readBanners(context))
    }

    @Test
    fun `bandeau lu avec des pauses au lieu du point median et du retour`() {
        assertEquals("Discord, Léa. On lance la partie ?", ReadingAids.spoken("Discord · Léa\nOn lance la partie ?"))
        assertEquals("Teams, Réunion", ReadingAids.spoken("Teams · Réunion"))
    }
}
