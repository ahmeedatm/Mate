package com.unowhy.companion.ui

import com.unowhy.companion.FakeContext
import com.unowhy.companion.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTest {
    private val context = FakeContext()

    @Test
    fun `tailles normale, grande et tres grande`() {
        assertEquals(listOf(1f, 1.15f, 1.3f), TextSize.entries.map { it.factor })
    }

    @Test
    fun `sans choix ou choix inconnu, taille normale`() {
        assertEquals(TextSize.NORMAL, TextSize.current(context))
        assertEquals(TextSize.NORMAL, TextSize.of("GEANTE"))
        Prefs.setTextSize(context, "HUGE")
        assertEquals(TextSize.HUGE, TextSize.current(context))
    }

    @Test
    fun `la taille choisie multiplie celle d'Android`() {
        assertEquals(1f, TextSize.fontScale(1f, TextSize.NORMAL), 0.0001f)
        assertEquals(1.15f, TextSize.fontScale(1f, TextSize.LARGE), 0.0001f)
        assertEquals(1.3f * 1.15f, TextSize.fontScale(1.15f, TextSize.HUGE), 0.0001f)
        assertEquals(0.85f, TextSize.fontScale(0.85f, TextSize.NORMAL), 0.0001f)
    }

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
        assertEquals("Discord, Léa. On lance la partie ?", Accessibility.spoken("Discord · Léa\nOn lance la partie ?"))
        assertEquals("Teams, Réunion", Accessibility.spoken("Teams · Réunion"))
    }
}
