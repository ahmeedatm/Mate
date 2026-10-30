package com.unowhy.unomate.ui

import com.unowhy.unomate.FakeContext
import com.unowhy.unomate.Prefs
import com.unowhy.unomate.R
import com.unowhy.unomate.ui.home.Demo
import com.unowhy.unomate.ui.home.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {
    private val context = FakeContext()

    @Test
    fun `Clair d'abord, puis par groupe, sobres, retro, accessibilite et personas`() {
        assertEquals(AppTheme.CLAIR, AppTheme.entries.first())
        assertEquals(
            mapOf(
                ThemeGroup.SOBRE to listOf(AppTheme.CLAIR, AppTheme.SOMBRE),
                ThemeGroup.RETRO to listOf(AppTheme.CADRAN, AppTheme.CADRAN_NUIT, AppTheme.RETRO),
                ThemeGroup.ACCESSIBILITE to listOf(AppTheme.CONTRASTE),
                ThemeGroup.PERSONAS to listOf(AppTheme.REGISTRE, AppTheme.STUDIO, AppTheme.MAISON, AppTheme.TABLEAU, AppTheme.TERMINAL),
            ),
            AppTheme.entries.groupBy { it.group },
        )
        // Groupes dans l'ordre de la liste : chaque groupe d'un seul tenant.
        assertEquals(AppTheme.entries.map { it.group }.sorted(), AppTheme.entries.map { it.group })
        assertEquals(
            listOf(AppTheme.REGISTRE, AppTheme.STUDIO, AppTheme.MAISON, AppTheme.TABLEAU, AppTheme.TERMINAL),
            AppTheme.entries.filter { it.demo },
        )
    }

    @Test
    fun `chaque persona a son theme, de demo sauf Sam`() {
        val expected = mapOf(
            Persona.NOUR to AppTheme.REGISTRE, Persona.GWEN to AppTheme.STUDIO, Persona.CAMILLE to AppTheme.MAISON,
            Persona.AIMEE to AppTheme.TABLEAU, Persona.SAM to AppTheme.CONTRASTE, Persona.SACHA to AppTheme.TERMINAL,
        )
        assertEquals(Persona.entries.toSet(), expected.keys)
        for ((persona, theme) in expected) assertEquals(persona.name, theme.name, persona.theme)
        assertEquals(AppTheme.entries.filter { it.demo }.toSet(), Persona.entries.mapNotNull { p -> AppTheme.entries.first { it.name == p.theme }.takeIf { it.demo } }.toSet())
    }

    @Test
    fun `un theme de persona choisi sans demo est garde`() {
        for (theme in AppTheme.entries.filter { it.demo }) {
            Prefs.setTheme(context, theme.name)
            assertEquals(theme, AppTheme.current(context))
        }
    }

    @Test
    fun `quitter la demo rend le theme de persona choisi avant`() {
        Prefs.setTheme(context, AppTheme.STUDIO.name)
        val persona = Persona.SACHA
        Demo.start(context, persona, persona.layout(persona.profiles.map { it.name.toString() }, "Réunion", emptyMap(), null))
        assertEquals(AppTheme.TERMINAL, AppTheme.current(context))
        assertTrue(Demo.quit(context))
        assertEquals(AppTheme.STUDIO, AppTheme.current(context))
    }

    @Test
    fun `le theme du persona s'applique pendant la demo et Clair revient en la quittant`() {
        for (persona in Persona.entries.filter { it.theme != "CONTRASTE" }) {
            val layout = persona.layout(persona.profiles.map { it.name.toString() }, "Réunion", emptyMap(), null)
            Demo.start(context, persona, layout)
            assertEquals(persona.theme, AppTheme.current(context).name)
            assertTrue(AppTheme.current(context).demo)
            assertTrue(Demo.quit(context))
            assertEquals(AppTheme.CLAIR, AppTheme.current(context))
        }
        assertFalse(Demo.quit(context))
    }

    @Test
    fun `sans choix enregistre, Clair`() {
        assertEquals(AppTheme.CLAIR, AppTheme.current(context))
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
