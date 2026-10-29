package com.unowhy.companion.ui

import com.unowhy.companion.Mode
import com.unowhy.companion.ui.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class RailModelTest {

    private val tools = listOf(RailEntry.Tool(Mode.FOCUS), RailEntry.Tool(Mode.COMPUTER), RailEntry.Tool(Mode.CAMERA))

    /** Accueil, Deck, Réunion (rôles posés), puis `extra` profils de plus. */
    private fun layout(extra: Int = 0) = HomeLayout(mutableListOf(), 1).apply {
        ensureRoles("Deck", "Réunion")
        repeat(extra) { addProfile("P$it") }
    }

    @Test
    fun `trois profils hors appel`() {
        assertEquals(listOf(RailEntry.Profile(0, false), RailEntry.Profile(1, false)) + tools, RailModel.entries(layout(), inCall = false))
    }

    @Test
    fun `en appel Reunion suit les profils, marquee isCall`() {
        assertEquals(
            listOf(RailEntry.Profile(0, false), RailEntry.Profile(1, false), RailEntry.Profile(2, true)) + tools,
            RailModel.entries(layout(), inCall = true),
        )
    }

    @Test
    fun `au dela de MAX_PROFILES une entree More remplace les derniers`() {
        val plain = HomeLayout(mutableListOf(), 7)
        val profiles = RailModel.entries(plain, inCall = false).dropLast(tools.size)
        assertEquals(listOf(0, 1, 2, 3).map { RailEntry.Profile(it, false) } + RailEntry.More, profiles)
    }

    @Test
    fun `en appel avec beaucoup de profils Reunion reste visible`() {
        // 0 Accueil, 1 Deck, 2 Réunion, 3 à 6 en plus.
        val profiles = RailModel.entries(layout(extra = 4), inCall = true).dropLast(tools.size)
        assertEquals(
            listOf(RailEntry.Profile(0, false), RailEntry.Profile(1, false), RailEntry.Profile(3, false), RailEntry.More, RailEntry.Profile(2, true)),
            profiles,
        )
    }

    @Test
    fun `selected montre le profil affiche ou l'outil`() {
        val entries = RailModel.entries(layout(), inCall = false)
        assertEquals(1, RailModel.selected(entries, Mode.HOME, page = 1))
        assertEquals(entries.indexOf(RailEntry.Tool(Mode.FOCUS)), RailModel.selected(entries, Mode.FOCUS, page = 0))
        assertEquals(-1, RailModel.selected(entries, Mode.SETTINGS, page = 0))
    }

    @Test
    fun `un profil cache derriere More selectionne More`() {
        val entries = RailModel.entries(HomeLayout(mutableListOf(), 7), inCall = false)
        assertEquals(entries.indexOf(RailEntry.More), RailModel.selected(entries, Mode.HOME, page = 6))
    }
}
