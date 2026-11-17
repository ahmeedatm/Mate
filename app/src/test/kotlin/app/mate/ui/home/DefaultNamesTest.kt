package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultNamesTest {

    private val french = DefaultNames.Names(listOf("Accueil", "Réunion"), "Écran %d")
    private val english = DefaultNames.Names(listOf("Home", "Meeting"), "Screen %d")
    private val all = listOf(french, english)

    @Test
    fun `nom par defaut enregistre dans une langue, montre dans l'autre`() {
        assertEquals("Home", DefaultNames.shown("Accueil", all, english))
        assertEquals("Accueil", DefaultNames.shown("Home", all, french))
        assertEquals("Meeting", DefaultNames.shown("Réunion", all, english))
        assertEquals("Écran 3", DefaultNames.shown("Screen 3", all, french))
        assertEquals("Screen 12", DefaultNames.shown("Écran 12", all, english))
    }

    @Test
    fun `nom saisi par l'utilisateur garde tel quel`() {
        assertEquals("Mon bureau", DefaultNames.shown("Mon bureau", all, english))
        assertEquals("Écran du soir", DefaultNames.shown("Écran du soir", all, english))
        assertEquals("Accueil 2", DefaultNames.shown("Accueil 2", all, english))
    }
}
