package app.mate.ui

import app.mate.FakeContext
import app.mate.Prefs
import app.mate.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    private val context = FakeContext()

    @Test
    fun `Console d'abord, puis Console jour, Contraste eleve, Hush et Spider-Verse`() {
        assertEquals(listOf(AppTheme.CONSOLE, AppTheme.CONSOLE_JOUR, AppTheme.CONTRASTE, AppTheme.HUSH, AppTheme.SPIDERMAN), AppTheme.entries)
    }

    @Test
    fun `sans choix enregistre, Console`() {
        assertEquals(AppTheme.CONSOLE, AppTheme.current(context))
    }

    @Test
    fun `Console est sombre, Console jour et Contraste sont clairs`() {
        assertEquals(R.style.Theme_Companion, AppTheme.CONSOLE.base)
        assertEquals(R.style.Theme_CompanionLight, AppTheme.CONSOLE_JOUR.base)
        // Palette à part seulement pour Contraste : une palette à parent AppCompat écraserait colorAccent de la base.
        assertEquals(null, AppTheme.CONSOLE.palette)
        assertEquals(null, AppTheme.CONSOLE_JOUR.palette)
        assertEquals(R.style.Theme_CompanionLight, AppTheme.CONTRASTE.base)
        assertEquals(R.style.Palette_Contraste, AppTheme.CONTRASTE.palette)
        assertEquals(R.style.Theme_Companion, AppTheme.HUSH.base)
        assertEquals(R.style.Palette_Hush, AppTheme.HUSH.palette)
        assertEquals(R.style.Theme_Companion, AppTheme.SPIDERMAN.base)
        assertEquals(R.style.Palette_SpiderMan, AppTheme.SPIDERMAN.palette)
    }

    @Test
    fun `un theme enregistre qui n'existe plus retombe sur Console`() {
        for (old in listOf("CLAIR", "SOMBRE", "CADRAN", "CADRAN_NUIT", "RETRO", "REGISTRE", "STUDIO", "MAISON", "TABLEAU", "TERMINAL", "NUIT", "NEON", "CHAUD")) {
            Prefs.setTheme(context, old)
            assertEquals(old, AppTheme.CONSOLE, AppTheme.current(context))
        }
    }

    @Test
    fun `Console jour enregistre est garde`() {
        Prefs.setTheme(context, "CONSOLE_JOUR")
        assertEquals(AppTheme.CONSOLE_JOUR, AppTheme.current(context))
    }

    @Test
    fun `Contraste enregistre est garde`() {
        Prefs.setTheme(context, "CONTRASTE")
        assertEquals(AppTheme.CONTRASTE, AppTheme.current(context))
    }

    @Test
    fun `Hush enregistre est garde`() {
        Prefs.setTheme(context, "HUSH")
        assertEquals(AppTheme.HUSH, AppTheme.current(context))
    }

    @Test
    fun `Spider-Verse enregistre est garde`() {
        Prefs.setTheme(context, "SPIDERMAN")
        assertEquals(AppTheme.SPIDERMAN, AppTheme.current(context))
    }

    @Test
    fun `un nom de theme inconnu retombe sur Console`() {
        Prefs.setTheme(context, "CONTRASTE_ELEVE")
        assertEquals(AppTheme.CONSOLE, AppTheme.current(context))
    }
}
