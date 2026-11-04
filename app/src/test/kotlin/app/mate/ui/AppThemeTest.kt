package app.mate.ui

import app.mate.FakeContext
import app.mate.Prefs
import app.mate.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    private val context = FakeContext()

    @Test
    fun `les themes, dans l'ordre des groupes`() {
        assertEquals(
            listOf(
                AppTheme.CONSOLE, AppTheme.CONSOLE_JOUR,
                AppTheme.CLAIR, AppTheme.SOMBRE,
                AppTheme.CADRAN, AppTheme.CADRAN_NUIT, AppTheme.RETRO,
                AppTheme.HUSH, AppTheme.SPIDERMAN, AppTheme.REGISTRE, AppTheme.STUDIO, AppTheme.MAISON, AppTheme.TABLEAU, AppTheme.TERMINAL,
                AppTheme.CONTRASTE,
            ),
            AppTheme.entries,
        )
    }

    @Test
    fun `cinq groupes, Console d'abord, Accessibilite en dernier`() {
        assertEquals(
            listOf(ThemeGroup.CONSOLE, ThemeGroup.SOBRE, ThemeGroup.RETRO, ThemeGroup.AMBIANCES, ThemeGroup.ACCESSIBILITE),
            ThemeGroup.entries,
        )
        assertEquals(ThemeGroup.entries, AppTheme.entries.map { it.group }.distinct())
        assertEquals(
            mapOf(
                ThemeGroup.CONSOLE to listOf(AppTheme.CONSOLE, AppTheme.CONSOLE_JOUR),
                ThemeGroup.SOBRE to listOf(AppTheme.CLAIR, AppTheme.SOMBRE),
                ThemeGroup.RETRO to listOf(AppTheme.CADRAN, AppTheme.CADRAN_NUIT, AppTheme.RETRO),
                ThemeGroup.AMBIANCES to listOf(
                    AppTheme.HUSH, AppTheme.SPIDERMAN, AppTheme.REGISTRE, AppTheme.STUDIO, AppTheme.MAISON, AppTheme.TABLEAU, AppTheme.TERMINAL,
                ),
                ThemeGroup.ACCESSIBILITE to listOf(AppTheme.CONTRASTE),
            ),
            AppTheme.entries.groupBy { it.group },
        )
    }

    @Test
    fun `Clair, Sombre, Cadran, Retro et les ambiances d'UnoMate ont leur base et leur palette`() {
        val expected = mapOf(
            AppTheme.CLAIR to (R.style.Theme_CompanionLight to R.style.Palette_CadranClair),
            AppTheme.SOMBRE to (R.style.Theme_Companion to R.style.Palette_CadranSombre),
            AppTheme.CADRAN to (R.style.Theme_CompanionLight to R.style.Palette_Cadran),
            AppTheme.CADRAN_NUIT to (R.style.Theme_Companion to R.style.Palette_CadranNuit),
            AppTheme.RETRO to (R.style.Theme_CompanionLight to R.style.Palette_Retro),
            AppTheme.REGISTRE to (R.style.Theme_CompanionLight to R.style.Palette_Registre),
            AppTheme.STUDIO to (R.style.Theme_CompanionLight to R.style.Palette_Studio),
            AppTheme.MAISON to (R.style.Theme_CompanionLight to R.style.Palette_Maison),
            AppTheme.TABLEAU to (R.style.Theme_CompanionLight to R.style.Palette_Tableau),
            AppTheme.TERMINAL to (R.style.Theme_Companion to R.style.Palette_Terminal),
        )
        for ((theme, basePalette) in expected) {
            assertEquals(theme.name, basePalette.first, theme.base)
            assertEquals(theme.name, basePalette.second, theme.palette)
        }
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
        for (old in listOf("NUIT", "NEON", "CHAUD", "DEMO")) {
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
    fun `chaque theme enregistre est garde`() {
        for (theme in AppTheme.entries) {
            Prefs.setTheme(context, theme.name)
            assertEquals(theme.name, theme, AppTheme.current(context))
        }
    }

    @Test
    fun `un nom de theme inconnu retombe sur Console`() {
        Prefs.setTheme(context, "CONTRASTE_ELEVE")
        assertEquals(AppTheme.CONSOLE, AppTheme.current(context))
    }
}
