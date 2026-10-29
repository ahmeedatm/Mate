package com.unowhy.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsTest {

    private val context = FakeContext()

    @Test
    fun `valeurs par defaut`() {
        assertEquals(BuildConfig.DEFAULT_BACKEND_URL, Prefs.backendUrl(context))
        assertEquals("", Prefs.token(context))
        assertEquals(-1f, Prefs.brightness(context))
        assertTrue(Prefs.nightDim(context))
        assertTrue(Prefs.focusDnd(context))
        assertFalse(Prefs.allowInstalls(context))
        assertNull(Prefs.theme(context))
        assertEquals(emptyList<String>(), Prefs.deckTabOrder(context))
    }

    @Test
    fun `barre de gauche, noms et outils affiches par defaut, puis retenus`() {
        assertTrue(Prefs.railLabels(context))
        assertEquals(listOf(Mode.FOCUS, Mode.COMPUTER, Mode.CAMERA), Prefs.railTools(context))
        Prefs.setRailLabels(context, false)
        Prefs.setRailTool(context, Mode.CAMERA, false)
        assertFalse(Prefs.railLabels(context))
        assertEquals(listOf(Mode.FOCUS, Mode.COMPUTER), Prefs.railTools(context))
        Prefs.setRailTool(context, Mode.CAMERA, true)
        assertEquals(listOf(Mode.FOCUS, Mode.COMPUTER, Mode.CAMERA), Prefs.railTools(context))
    }

    @Test
    fun `url et jeton viennent de l'ordinateur actif`() {
        Computers.save(context, Computer("a", "Bureau", "http://10.0.0.3:3000", "jeton", "mac"), makeActive = true)
        assertEquals("http://10.0.0.3:3000", Prefs.backendUrl(context))
        assertEquals("jeton", Prefs.token(context))
    }

    @Test
    fun `reglages enregistres`() {
        Prefs.setBrightness(context, 0.4f)
        Prefs.setNightDim(context, false)
        Prefs.setFocusDnd(context, false)
        Prefs.setAllowInstalls(context, true)
        Prefs.setTheme(context, "NEON")
        assertEquals(0.4f, Prefs.brightness(context))
        assertFalse(Prefs.nightDim(context))
        assertFalse(Prefs.focusDnd(context))
        assertTrue(Prefs.allowInstalls(context))
        assertEquals("NEON", Prefs.theme(context))
    }

    @Test
    fun `ordre des onglets du deck propre a chaque ordinateur`() {
        Computers.save(context, Computer("a", "Bureau", "http://10.0.0.3:3000", "", "mac"), makeActive = true)
        Prefs.setDeckTabOrder(context, listOf("apps", "open", "media"))
        Computers.save(context, Computer("b", "Maison", "http://10.0.0.4:3000", "", "windows"), makeActive = true)
        assertEquals(emptyList<String>(), Prefs.deckTabOrder(context))
        Computers.setActive(context, "a")
        assertEquals(listOf("apps", "open", "media"), Prefs.deckTabOrder(context))
    }
}
