package com.unowhy.companion.ui

import com.unowhy.companion.Mode
import com.unowhy.companion.Prefs
import com.unowhy.companion.ui.home.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RailModelTest {

    private val tools = listOf(RailEntry.Tool(Mode.FOCUS), RailEntry.Tool(Mode.COMPUTER), RailEntry.Tool(Mode.CAMERA))

    /** Accueil, un écran ajouté (Deck), Réunion (rôle), puis `extra` profils de plus. */
    private fun layout(extra: Int = 0) = HomeLayout(mutableListOf(), 1).apply {
        addProfile("Deck")
        ensureRoles("Réunion")
        repeat(extra) { addProfile("P$it") }
    }

    @Test
    fun `trois profils hors appel, puis + pour ajouter un ecran`() {
        assertEquals(listOf(RailEntry.Profile(0, false), RailEntry.Profile(1, false), RailEntry.Add) + tools, RailModel.entries(layout(), inCall = false))
    }

    @Test
    fun `de base un seul ecran, l'Accueil, puis +`() {
        val single = HomeLayout(mutableListOf(), 1).apply { ensureRoles("Réunion") }
        assertEquals(listOf(RailEntry.Profile(0, false), RailEntry.Add) + tools, RailModel.entries(single, inCall = false))
    }

    @Test
    fun `en appel Reunion suit les profils, marquee isCall`() {
        assertEquals(
            listOf(RailEntry.Profile(0, false), RailEntry.Profile(1, false), RailEntry.Profile(2, true), RailEntry.Add) + tools,
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
    fun `seuls les outils choisis dans Reglages sont dans la barre`() {
        val entries = RailModel.entries(layout(), inCall = false, tools = listOf(Mode.FOCUS))
        assertEquals(listOf(RailEntry.Profile(0, false), RailEntry.Profile(1, false), RailEntry.Add, RailEntry.Tool(Mode.FOCUS)), entries)
    }

    @Test
    fun `direction dit si l'on descend ou monte dans la barre`() {
        assertEquals(1, RailModel.direction(0, 2))
        assertEquals(-1, RailModel.direction(2, 0))
        assertEquals(0, RailModel.direction(1, 1))
        // Rien de marqué avant (Réglages) : on arrive comme en descendant.
        assertEquals(1, RailModel.direction(-1, 3))
    }

    @Test
    fun `un profil cache derriere More selectionne More`() {
        val entries = RailModel.entries(HomeLayout(mutableListOf(), 7), inCall = false)
        assertEquals(entries.indexOf(RailEntry.More), RailModel.selected(entries, Mode.HOME, page = 6))
    }

    @Test
    fun `l'instantane de la barre change quand un profil est renomme sur place`() {
        val l = layout()
        val before = RailModel.snapshot(l, inCall = false, tools = Prefs.RAIL_TOOLS, labels = true)
        l.renameProfile(1, "Mon Deck")
        assertNotEquals(before, RailModel.snapshot(l, inCall = false, tools = Prefs.RAIL_TOOLS, labels = true))
    }

    @Test
    fun `l'instantane change quand un ecran est supprime ou qu'un appel commence`() {
        val l = layout()
        val before = RailModel.snapshot(l, inCall = false, tools = Prefs.RAIL_TOOLS, labels = true)
        assertNotEquals(before, RailModel.snapshot(l, inCall = true, tools = Prefs.RAIL_TOOLS, labels = true))
        l.removeProfile(1)
        assertNotEquals(before, RailModel.snapshot(l, inCall = false, tools = Prefs.RAIL_TOOLS, labels = true))
    }

    @Test
    fun `balayage vers le haut, profil suivant puis outils en sautant +`() {
        val entries = RailModel.entries(layout(), inCall = false)
        assertEquals(RailEntry.Profile(1, false), RailModel.neighbor(entries, Mode.HOME, 0, 1))
        assertEquals(RailEntry.Tool(Mode.FOCUS), RailModel.neighbor(entries, Mode.HOME, 1, 1))
        assertEquals(RailEntry.Tool(Mode.COMPUTER), RailModel.neighbor(entries, Mode.FOCUS, 0, 1))
    }

    @Test
    fun `balayage vers le bas, du premier outil au dernier profil`() {
        val entries = RailModel.entries(layout(), inCall = false)
        assertEquals(RailEntry.Profile(1, false), RailModel.neighbor(entries, Mode.FOCUS, 0, -1))
        assertEquals(RailEntry.Profile(0, false), RailModel.neighbor(entries, Mode.HOME, 1, -1))
    }

    @Test
    fun `rien au bout de la barre ni hors barre`() {
        val entries = RailModel.entries(layout(), inCall = false)
        assertEquals(null, RailModel.neighbor(entries, Mode.HOME, 0, -1))
        assertEquals(null, RailModel.neighbor(entries, Mode.CAMERA, 0, 1))
        assertEquals(null, RailModel.neighbor(entries, Mode.SETTINGS, 0, 1))
    }

    @Test
    fun `un seul profil, le balayage mene aux outils`() {
        val single = HomeLayout(mutableListOf(), 1).apply { ensureRoles("Réunion") }
        assertEquals(RailEntry.Tool(Mode.FOCUS), RailModel.neighbor(RailModel.entries(single, inCall = false), Mode.HOME, 0, 1))
    }
}
