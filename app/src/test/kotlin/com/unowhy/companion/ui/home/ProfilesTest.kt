package com.unowhy.companion.ui.home

import com.unowhy.companion.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {

    private fun layoutOf(pages: Int, vararg widgets: Placed) = HomeLayout(widgets.toMutableList(), pages)

    @Test
    fun `une disposition v1 se relit avec un profil par page, sans nom`() {
        val context = FakeContext()
        val v1 = """{"v":1,"pages":2,"widgets":[{"t":"note","s":"S","p":1,"c":0,"r":0,"cfg":{}}]}"""
        context.getSharedPreferences("companion", 0).edit().putString("home_layout_cadran", v1).apply()
        val layout = HomeLayout.load(context, cadran = true)
        assertEquals(listOf<String?>(null, null), layout.names)
        assertEquals(-1, layout.gameProfile)
        assertEquals(1, layout.widgets.single().page)
    }

    @Test
    fun `noms et profil de jeu survivent a l'enregistrement`() {
        val context = FakeContext()
        val layout = HomeLayout(mutableListOf(), mutableListOf("Bureau", "Gamer"), 1)
        HomeLayout.save(context, layout, cadran = true)
        val back = HomeLayout.load(context, cadran = true)
        assertEquals(listOf<String?>("Bureau", "Gamer"), back.names)
        assertEquals(1, back.gameProfile)
    }

    @Test
    fun `un nom absent reste null apres enregistrement`() {
        val context = FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(), mutableListOf(null, "Gamer"), -1), cadran = true)
        assertEquals(listOf<String?>(null, "Gamer"), HomeLayout.load(context, cadran = true).names)
    }

    @Test
    fun `ajouter un profil le met a la fin avec ses widgets`() {
        val layout = Template.WORK_CADRAN.build()
        val items = Template.HOME_CADRAN.widgets(0)
        assertEquals(1, layout.addProfile("Gamer", items))
        assertEquals(listOf<String?>(null, "Gamer"), layout.names)
        assertEquals(items.size, layout.on(1).size)
        assertTrue(layout.on(1).all { it.page == 1 })
    }

    @Test
    fun `un profil vide est garde`() {
        val context = FakeContext()
        val layout = Template.WORK_CADRAN.build()
        layout.addProfile(null)
        HomeLayout.save(context, layout, cadran = true)
        assertEquals(2, HomeLayout.load(context, cadran = true).pages)
    }

    @Test
    fun `supprimer un profil decale les suivants`() {
        val far = Placed(WidgetType.NOTE, WidgetSize.S, 2, 0, 0)
        val layout = layoutOf(3, Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0), far)
        assertTrue(layout.removeProfile(1))
        assertEquals(1, far.page)
        assertEquals(2, layout.names.size)
    }

    @Test
    fun `supprimer un profil retire ses widgets`() {
        val layout = layoutOf(2, Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0), Placed(WidgetType.NOTE, WidgetSize.S, 1, 0, 0))
        layout.removeProfile(1)
        assertEquals(listOf(WidgetType.CLOCK), layout.widgets.map { it.type })
    }

    @Test
    fun `supprimer le profil de jeu retire le marquage`() {
        val layout = layoutOf(3)
        layout.gameProfile = 1
        layout.removeProfile(1)
        assertEquals(-1, layout.gameProfile)
    }

    @Test
    fun `supprimer avant le profil de jeu le suit`() {
        val layout = layoutOf(3)
        layout.gameProfile = 2
        layout.removeProfile(0)
        assertEquals(1, layout.gameProfile)
    }

    @Test
    fun `le dernier profil ne se supprime pas`() {
        val layout = layoutOf(1)
        assertFalse(layout.removeProfile(0))
        assertEquals(1, layout.pages)
    }

    @Test
    fun `deplacer un profil emporte ses widgets et le marquage`() {
        val note = Placed(WidgetType.NOTE, WidgetSize.S, 0, 0, 0)
        val layout = HomeLayout(mutableListOf(note), mutableListOf("A", "B", "C"), 0)
        layout.moveProfile(0, 2)
        assertEquals(2, note.page)
        assertEquals(2, layout.gameProfile)
        assertEquals(listOf<String?>("B", "C", "A"), layout.names)
    }

    @Test
    fun `deplacer un profil vers le haut emporte ses widgets et le marquage`() {
        val first = Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0)
        val third = Placed(WidgetType.NOTE, WidgetSize.S, 2, 0, 0)
        val layout = HomeLayout(mutableListOf(first, third), mutableListOf("A", "B", "C"), 2)
        layout.moveProfile(2, 0)
        assertEquals(0, third.page)
        assertEquals(1, first.page)
        assertEquals(0, layout.gameProfile)
        assertEquals(listOf<String?>("C", "A", "B"), layout.names)
    }

    @Test
    fun `un seul profil de jeu`() {
        val layout = layoutOf(3)
        layout.setGameProfile(1)
        layout.setGameProfile(2)
        assertEquals(2, layout.gameProfile)
    }

    @Test
    fun `renommer change le nom du profil`() {
        val layout = layoutOf(2)
        layout.renameProfile(1, "Gamer")
        assertEquals(listOf<String?>(null, "Gamer"), layout.names)
    }

    @Test
    fun `copy est independant`() {
        val layout = layoutOf(1)
        layout.gameProfile = 0
        val copy = layout.copy()
        assertEquals(0, copy.gameProfile)
        copy.renameProfile(0, "X")
        assertEquals(listOf<String?>(null), layout.names)
        val widget = Placed(WidgetType.NOTE, WidgetSize.S, 0, 1, 0)
        val withWidget = HomeLayout(mutableListOf(widget), 1).copy()
        withWidget.widgets.single().col = 3
        assertEquals(1, widget.col)
    }

    @Test
    fun `un nouveau nom par defaut prend le premier numero libre`() {
        val layout = HomeLayout(mutableListOf(), mutableListOf("Bureau", "Profil 2", "Profil 3"))
        layout.removeProfile(1)
        layout.addProfile(null)
        assertTrue(layout.nameMissing("Bureau", "Profil %d"))
        assertEquals(listOf<String?>("Bureau", "Profil 3", "Profil 2"), layout.names)
        assertEquals("Profil 4", layout.freeName("Profil %d"))
    }

    @Test
    fun `une disposition sans nom donne Bureau au premier puis Profil 2 et Profil 3`() {
        val layout = layoutOf(3)
        assertTrue(layout.nameMissing("Bureau", "Profil %d"))
        assertEquals(listOf<String?>("Bureau", "Profil 2", "Profil 3"), layout.names)
        assertFalse(layout.nameMissing("Bureau", "Profil %d"))
    }

    // Rôles des profils (Deck V2) : Accueil, Deck, Réunion.

    @Test
    fun `ensureRoles sur une disposition d'un seul profil ajoute Deck en 2e et Reunion en dernier`() {
        val layout = layoutOf(1, Placed(WidgetType.NOTE, WidgetSize.S, 0, 0, 0))
        assertTrue(layout.ensureRoles("Deck", "Réunion"))
        assertEquals(listOf<String?>(null, "Deck", "Réunion"), layout.names)
        assertEquals(0, layout.homeProfile)
        assertEquals(1, layout.deckProfile)
        assertEquals(2, layout.callProfile)
        assertTrue(layout.on(1).any { it.type == WidgetType.APPS })
        assertFalse(layout.ensureRoles("Deck", "Réunion"))
        assertEquals(3, layout.pages)
    }

    @Test
    fun `ensureRoles decale le profil de jeu place apres l'Accueil`() {
        val layout = layoutOf(2, Placed(WidgetType.NOTE, WidgetSize.S, 1, 0, 0))
        layout.gameProfile = 1
        layout.ensureRoles("Deck", "Réunion")
        assertEquals(2, layout.gameProfile)
        assertEquals(2, layout.widgets.single { it.type == WidgetType.NOTE }.page)
        assertEquals(1, layout.deckProfile)
        assertEquals(3, layout.callProfile)
    }

    @Test
    fun `ensureRoles ne recree pas un Deck supprime`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Deck", "Réunion")
        assertTrue(layout.removeProfile(layout.deckProfile))
        assertFalse(layout.ensureRoles("Deck", "Réunion"))
        assertEquals(-1, layout.deckProfile)
        assertEquals(2, layout.pages)
    }

    @Test
    fun `roles survivent a l'enregistrement`() {
        val context = FakeContext()
        val layout = layoutOf(1)
        layout.ensureRoles("Deck", "Réunion")
        HomeLayout.save(context, layout, cadran = true)
        val back = HomeLayout.load(context, cadran = true)
        assertEquals(0, back.homeProfile)
        assertEquals(1, back.deckProfile)
        assertEquals(2, back.callProfile)
        assertFalse(back.ensureRoles("Deck", "Réunion"))
    }

    @Test
    fun `Accueil et Reunion ne se suppriment pas`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Deck", "Réunion")
        assertFalse(layout.canRemove(layout.homeProfile))
        assertFalse(layout.canRemove(layout.callProfile))
        assertTrue(layout.canRemove(layout.deckProfile))
        assertFalse(layout.removeProfile(layout.callProfile))
        assertFalse(layout.removeProfile(layout.homeProfile))
        assertEquals(3, layout.pages)
    }

    @Test
    fun `supprimer le Deck renvoie deckTarget vers l'Accueil`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Deck", "Réunion")
        assertEquals(1, layout.deckTarget())
        layout.removeProfile(layout.deckProfile)
        assertEquals(-1, layout.deckProfile)
        assertEquals(layout.homeProfile, layout.deckTarget())
        assertEquals(1, layout.callProfile)
    }

    @Test
    fun `profilesInOrder cache Reunion hors appel`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Deck", "Réunion")
        layout.addProfile("Maison")
        assertEquals(listOf(0, 1, 3), layout.profilesInOrder(inCall = false))
        assertEquals(listOf(0, 1, 3, 2), layout.profilesInOrder(inCall = true))
    }

    @Test
    fun `nameOf donne le nom du profil ou son nom par defaut`() {
        val layout = HomeLayout(mutableListOf(), mutableListOf(null, "Deck", null))
        assertEquals("Accueil", layout.nameOf(0, "Accueil", "Profil %d"))
        assertEquals("Deck", layout.nameOf(1, "Accueil", "Profil %d"))
        assertEquals("Profil 3", layout.nameOf(2, "Accueil", "Profil %d"))
    }

    @Test
    fun `deplacer un profil suit les roles`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Deck", "Réunion")
        layout.moveProfile(1, 0)
        assertEquals(1, layout.homeProfile)
        assertEquals(0, layout.deckProfile)
        assertEquals(2, layout.callProfile)
    }
}
