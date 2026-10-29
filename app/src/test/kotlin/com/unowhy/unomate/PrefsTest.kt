package com.unowhy.unomate

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
        assertFalse(Prefs.exitLock(context))
        assertFalse(Prefs.powerMenu(context))
        assertFalse(Prefs.landscapeLocked(context))
        assertNull(Prefs.theme(context))
    }

    @Test
    fun `reglages du kiosque retenus`() {
        Prefs.setExitLock(context, true)
        Prefs.setPowerMenu(context, true)
        Prefs.setLandscapeLocked(context, true)
        assertTrue(Prefs.exitLock(context))
        assertTrue(Prefs.powerMenu(context))
        assertTrue(Prefs.landscapeLocked(context))
    }

    @Test
    fun `barre de gauche, noms et outils affiches par defaut, puis retenus`() {
        assertTrue(Prefs.railLabels(context))
        // La Caméra n'est plus un outil de la barre : elle s'ouvre depuis Ordi.
        assertEquals(listOf(Mode.FOCUS, Mode.COMPUTER), Prefs.railTools(context))
        Prefs.setRailLabels(context, false)
        Prefs.setRailTool(context, Mode.FOCUS, false)
        assertFalse(Prefs.railLabels(context))
        assertEquals(listOf(Mode.COMPUTER), Prefs.railTools(context))
        Prefs.setRailTool(context, Mode.FOCUS, true)
        assertEquals(listOf(Mode.FOCUS, Mode.COMPUTER), Prefs.railTools(context))
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
    fun `taille du texte normale par defaut, puis celle choisie`() {
        assertEquals(1f, Prefs.textScale(context), 0f)
        Prefs.setTextScale(context, 1.5f)
        assertEquals(1.5f, Prefs.textScale(context), 0f)
    }
}
