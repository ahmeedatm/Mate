package app.mate.ui

import app.mate.FakeContext
import app.mate.Prefs
import app.mate.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {
    private val context = FakeContext()

    @Test
    fun `Clair d'abord, puis par groupe, sobres, retro et accessibilite`() {
        assertEquals(AppTheme.CLAIR, AppTheme.entries.first())
        assertEquals(
            mapOf(
                ThemeGroup.SOBRE to listOf(AppTheme.CLAIR, AppTheme.SOMBRE),
                ThemeGroup.RETRO to listOf(AppTheme.CADRAN, AppTheme.CADRAN_NUIT, AppTheme.RETRO),
                ThemeGroup.ACCESSIBILITE to listOf(AppTheme.CONTRASTE),
            ),
            AppTheme.entries.groupBy { it.group },
        )
        // Groupes dans l'ordre de la liste : chaque groupe d'un seul tenant.
        assertEquals(AppTheme.entries.map { it.group }.sorted(), AppTheme.entries.map { it.group })
    }

    @Test
    fun `sans choix enregistre, Clair`() {
        assertEquals(AppTheme.CLAIR, AppTheme.current(context))
    }

    @Test
    fun `un theme supprime retombe sur Clair`() {
        for (old in listOf("REGISTRE", "STUDIO", "MAISON", "TABLEAU", "TERMINAL")) {
            Prefs.setTheme(context, old)
            assertEquals(AppTheme.CLAIR, AppTheme.current(context))
        }
    }

    @Test
    fun `un ancien theme enregistre retombe sur Clair`() {
        for (old in listOf("NUIT", "NEON", "CHAUD")) {
            Prefs.setTheme(context, old)
            assertEquals(AppTheme.CLAIR, AppTheme.current(context))
        }
    }

    @Test
    fun `Clair et Sombre sont les Cadran d avant la texture`() {
        Prefs.setTheme(context, "CLAIR")
        assertEquals(AppTheme.CLAIR, AppTheme.current(context))
        assertEquals(R.style.Palette_CadranClair, AppTheme.CLAIR.palette)
        assertEquals(R.style.Palette_CadranSombre, AppTheme.SOMBRE.palette)
    }

    @Test
    fun `Cadran nuit enregistre est garde`() {
        Prefs.setTheme(context, "CADRAN_NUIT")
        assertEquals(AppTheme.CADRAN_NUIT, AppTheme.current(context))
    }

    @Test
    fun `Retro enregistre est garde`() {
        Prefs.setTheme(context, "RETRO")
        assertEquals(AppTheme.RETRO, AppTheme.current(context))
    }

    @Test
    fun `Contraste enregistre est garde`() {
        Prefs.setTheme(context, "CONTRASTE")
        assertEquals(AppTheme.CONTRASTE, AppTheme.current(context))
    }

    @Test
    fun `un nom de theme inconnu retombe sur Clair`() {
        Prefs.setTheme(context, "CONTRASTE_ELEVE")
        assertEquals(AppTheme.CLAIR, AppTheme.current(context))
    }
}
