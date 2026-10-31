package app.mate.ui.home

import app.mate.FakeContext
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
        val layout = HomeLayout.load(context)
        assertEquals(listOf<String?>(null, null), layout.names)
        assertEquals(1, layout.widgets.single().page)
    }

    @Test
    fun `noms survivent a l'enregistrement`() {
        val context = FakeContext()
        val layout = HomeLayout(mutableListOf(), mutableListOf("Bureau", "Maison"))
        HomeLayout.save(context, layout)
        val back = HomeLayout.load(context)
        assertEquals(listOf<String?>("Bureau", "Maison"), back.names)
    }

    @Test
    fun `une disposition enregistree avec un profil de jeu se relit sans lui`() {
        val context = FakeContext()
        val old = """{"v":2,"pages":2,"names":["Bureau","Maison"],"game":1,"widgets":[]}"""
        context.getSharedPreferences("companion", 0).edit().putString("home_layout_cadran", old).apply()
        val back = HomeLayout.load(context)
        assertEquals(listOf<String?>("Bureau", "Maison"), back.names)
        assertFalse(back.toJson().contains("\"game\""))
    }

    @Test
    fun `un nom absent reste null apres enregistrement`() {
        val context = FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(), mutableListOf(null, "Maison")))
        assertEquals(listOf<String?>(null, "Maison"), HomeLayout.load(context).names)
    }

    @Test
    fun `ajouter un profil le met a la fin avec ses widgets`() {
        val layout = Template.WORK_CADRAN.build()
        val items = Template.STANDBY_CADRAN.widgets(0)
        assertEquals(1, layout.addProfile("Maison", items))
        assertEquals(listOf<String?>(null, "Maison"), layout.names)
        assertEquals(items.size, layout.on(1).size)
        assertTrue(layout.on(1).all { it.page == 1 })
    }

    @Test
    fun `un profil vide est garde`() {
        val context = FakeContext()
        val layout = Template.WORK_CADRAN.build()
        layout.addProfile(null)
        HomeLayout.save(context, layout)
        assertEquals(2, HomeLayout.load(context).pages)
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
    fun `le dernier profil ne se supprime pas`() {
        val layout = layoutOf(1)
        assertFalse(layout.removeProfile(0))
        assertEquals(1, layout.pages)
    }

    @Test
    fun `deplacer un profil emporte ses widgets`() {
        val note = Placed(WidgetType.NOTE, WidgetSize.S, 0, 0, 0)
        val layout = HomeLayout(mutableListOf(note), mutableListOf("A", "B", "C"))
        layout.moveProfile(0, 2)
        assertEquals(2, note.page)
        assertEquals(listOf<String?>("B", "C", "A"), layout.names)
    }

    @Test
    fun `deplacer un profil vers le haut emporte ses widgets`() {
        val first = Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0)
        val third = Placed(WidgetType.NOTE, WidgetSize.S, 2, 0, 0)
        val layout = HomeLayout(mutableListOf(first, third), mutableListOf("A", "B", "C"))
        layout.moveProfile(2, 0)
        assertEquals(0, third.page)
        assertEquals(1, first.page)
        assertEquals(listOf<String?>("C", "A", "B"), layout.names)
    }

    @Test
    fun `renommer change le nom du profil`() {
        val layout = layoutOf(2)
        layout.renameProfile(1, "Maison")
        assertEquals(listOf<String?>(null, "Maison"), layout.names)
    }

    @Test
    fun `copy est independant`() {
        val layout = layoutOf(1)
        val copy = layout.copy()
        copy.renameProfile(0, "X")
        assertEquals(listOf<String?>(null), layout.names)
        val widget = Placed(WidgetType.NOTE, WidgetSize.S, 0, 1, 0)
        val withWidget = HomeLayout(mutableListOf(widget), 1).copy()
        withWidget.widgets.single().col = 3
        assertEquals(1, widget.col)
    }

    @Test
    fun `un nouvel ecran prend le plus petit numero libre`() {
        val layout = HomeLayout(mutableListOf(), mutableListOf("Accueil", "Écran 1", "Écran 2", "Deck"))
        layout.addProfile(null)
        assertTrue(layout.nameMissing("Accueil", "Écran %d"))
        assertEquals("Écran 3", layout.names[4])
        // Écran 1 et 2 supprimés : les suivants les reprennent, pas « Écran 4 » et « Écran 5 ».
        layout.removeProfile(1)
        layout.removeProfile(1)
        layout.addProfile(null)
        layout.addProfile(null)
        layout.nameMissing("Accueil", "Écran %d")
        assertEquals(listOf<String?>("Accueil", "Deck", "Écran 3", "Écran 1", "Écran 2"), layout.names)
        assertEquals("Écran 4", layout.freeName("Écran %d"))
    }

    @Test
    fun `les anciens noms automatiques Profil N deviennent Ecran N`() {
        val layout = HomeLayout(mutableListOf(), mutableListOf("Bureau", "Profil 2", null))
        assertTrue(layout.nameMissing("Accueil", "Écran %d"))
        assertEquals(listOf<String?>("Bureau", "Écran 2", "Écran 1"), layout.names)
        assertFalse(layout.nameMissing("Accueil", "Écran %d"))
    }

    @Test
    fun `l Accueil nomme Bureau par l ancien format devient Accueil`() {
        val layout = HomeLayout(mutableListOf(), mutableListOf("Bureau", "Profil 2"))
        layout.rolesSet = true
        assertTrue(layout.nameMissing("Accueil", "Écran %d"))
        assertEquals(listOf<String?>("Accueil", "Écran 2"), layout.names)
    }

    @Test
    fun `deux ecrans du meme modele sont numerotes`() {
        val layout = HomeLayout(mutableListOf(), mutableListOf("Accueil", "Bureau"))
        assertEquals("Bureau 2", layout.uniqueName("Bureau"))
        assertEquals("Veille", layout.uniqueName("Veille"))
    }

    @Test
    fun `une disposition sans nom donne Bureau au premier puis Profil 1 et Profil 2`() {
        val layout = layoutOf(3)
        assertTrue(layout.nameMissing("Bureau", "Profil %d"))
        assertEquals(listOf<String?>("Bureau", "Profil 1", "Profil 2"), layout.names)
        assertFalse(layout.nameMissing("Bureau", "Profil %d"))
    }

    // Rôles des profils : Accueil et Réunion. Un seul écran de base ; d'autres seulement si on en ajoute.

    @Test
    fun `ensureRoles sur un seul profil n'ajoute que Reunion, en dernier`() {
        val layout = layoutOf(1, Placed(WidgetType.NOTE, WidgetSize.S, 0, 0, 0))
        assertTrue(layout.ensureRoles("Réunion"))
        assertEquals(listOf<String?>(null, "Réunion"), layout.names)
        assertEquals(0, layout.homeProfile)
        assertEquals(-1, layout.deckProfile)
        assertEquals(1, layout.callProfile)
        assertFalse(layout.ensureRoles("Réunion"))
        assertEquals(2, layout.pages)
    }

    @Test
    fun `ensureRoles laisse les autres profils a leur place`() {
        val layout = layoutOf(2, Placed(WidgetType.NOTE, WidgetSize.S, 1, 0, 0))
        layout.ensureRoles("Réunion")
        assertEquals(1, layout.widgets.single { it.type == WidgetType.NOTE }.page)
        assertEquals(2, layout.callProfile)
    }

    /** Disposition du premier Deck V2 : Accueil, Deck (rôle), Réunion. */
    private fun withDeck(vararg extra: Placed) = HomeLayout(mutableListOf(), mutableListOf(null, "Deck", "Réunion")).apply {
        widgets += Template.DECK_CADRAN.widgets(1)
        widgets += Template.CALL_CADRAN.widgets(2)
        widgets += extra
        deckProfile = 1
        callProfile = 2
        rolesSet = true
    }

    @Test
    fun `un Deck jamais modifie est retire, une seule fois`() {
        val layout = withDeck()
        assertTrue(layout.dropDefaultDeck())
        assertEquals(listOf<String?>(null, "Réunion"), layout.names)
        assertEquals(-1, layout.deckProfile)
        assertEquals(1, layout.callProfile)
        assertTrue(layout.on(1).any { it.type == WidgetType.CALL })
        assertFalse(layout.dropDefaultDeck())
    }

    @Test
    fun `un Deck aux touches deja remplies compte comme jamais modifie, donc retire`() {
        val layout = withDeck()
        layout.on(1).single { it.type == WidgetType.KEYS }.config.put("byComputer", org.json.JSONObject())
        assertTrue(layout.dropDefaultDeck())
        assertEquals(2, layout.pages)
    }

    @Test
    fun `un Deck personnalise reste, comme un ecran ordinaire`() {
        val layout = withDeck(Placed(WidgetType.NOTE, WidgetSize.S, 1, 0, 0))
        layout.widgets.removeAll { it.page == 1 && it.type == WidgetType.OPEN }
        assertTrue(layout.dropDefaultDeck())
        assertEquals(listOf<String?>(null, "Deck", "Réunion"), layout.names)
        assertEquals(-1, layout.deckProfile)
        assertTrue(layout.canRemove(1))
        assertFalse(layout.dropDefaultDeck())
    }

    @Test
    fun `roles survivent a l'enregistrement`() {
        val context = FakeContext()
        val layout = layoutOf(1)
        layout.ensureRoles("Réunion")
        HomeLayout.save(context, layout)
        val back = HomeLayout.load(context)
        assertEquals(0, back.homeProfile)
        assertEquals(-1, back.deckProfile)
        assertEquals(1, back.callProfile)
        assertFalse(back.ensureRoles("Réunion"))
    }

    @Test
    fun `l'Accueil ne se supprime pas, Reunion et un ecran ajoute si`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Réunion")
        val maison = layout.addProfile("Maison")
        assertFalse(layout.canRemove(layout.homeProfile))
        assertTrue(layout.canRemove(layout.callProfile))
        assertTrue(layout.canRemove(maison))
        assertFalse(layout.removeProfile(layout.homeProfile))
        assertEquals(3, layout.pages)
    }

    @Test
    fun `Reunion supprime n'est pas recree et plus aucun ecran d'appel`() {
        val context = FakeContext()
        val layout = layoutOf(1)
        layout.ensureRoles("Réunion")
        val maison = layout.addProfile("Maison")
        assertTrue(layout.removeProfile(layout.callProfile))
        assertEquals(-1, layout.callProfile)
        assertEquals(listOf(0, 1), layout.profilesInOrder(inCall = true))
        assertEquals(0, layout.homeProfile)
        assertEquals("Maison", layout.names[maison - 1])
        HomeLayout.save(context, layout)
        val back = HomeLayout.load(context)
        assertFalse(back.ensureRoles("Réunion"))
        assertEquals(-1, back.callProfile)
        assertEquals(2, back.pages)
    }

    @Test
    fun `recreer Reunion depuis le modele reprend le role d'appel, une seule fois`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Réunion")
        layout.removeProfile(layout.callProfile)
        val index = layout.addFromTemplate(Template.CALL_CADRAN, "Réunion")
        assertEquals(index, layout.callProfile)
        assertTrue(layout.on(index).any { it.type == WidgetType.CALL })
        val second = layout.addFromTemplate(Template.CALL_CADRAN, "Réunion")
        assertEquals(index, layout.callProfile)
        assertEquals("Réunion 2", layout.names[second])
    }

    @Test
    fun `sans Deck, deckTarget est l'Accueil`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Réunion")
        assertEquals(layout.homeProfile, layout.deckTarget())
    }

    @Test
    fun `profilesInOrder cache Reunion hors appel`() {
        val layout = layoutOf(1)
        layout.ensureRoles("Réunion")
        layout.addProfile("Maison")
        assertEquals(listOf(0, 2), layout.profilesInOrder(inCall = false))
        assertEquals(listOf(0, 2, 1), layout.profilesInOrder(inCall = true))
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
        layout.ensureRoles("Réunion")
        layout.addProfile("Maison")
        layout.moveProfile(2, 0)
        assertEquals(1, layout.homeProfile)
        assertEquals(2, layout.callProfile)
    }
}
