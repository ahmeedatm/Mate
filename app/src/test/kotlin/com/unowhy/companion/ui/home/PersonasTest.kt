package com.unowhy.companion.ui.home

import com.unowhy.companion.FakeContext
import com.unowhy.companion.Mode
import com.unowhy.companion.Prefs
import com.unowhy.companion.net.parseDemoKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonasTest {

    private val keys = listOf(KeyRef("k-0", "Excel", "📊", "#1D6F42"), KeyRef("k-1", "Enregistrer"))
    private val sixKeys = (0 until 6).map { KeyRef("k-$it", "Touche $it") }

    private fun layout(persona: Persona, ofKeys: List<KeyRef> = keys) =
        persona.layout(persona.profiles.map { "${persona.name} ${it.name}" }, ofKeys, "ordi")

    private fun types(persona: Persona) = persona.profiles.flatMap { p -> p.slots.map { it.type } }.toSet()

    @Test
    fun `six personas aux ids uniques`() {
        assertEquals(6, Persona.entries.size)
        assertEquals(Persona.entries.size, Persona.entries.map { it.id }.toSet().size)
        assertEquals(Persona.SACHA, Persona.of("sacha"))
        assertNull(Persona.of("inconnu"))
    }

    @Test
    fun `chaque persona donne ses profils nommes, relisibles tels quels`() {
        for (persona in Persona.entries) {
            val layout = layout(persona)
            assertEquals(persona.profiles.map { "${persona.name} ${it.name}" }, layout.names)
            assertEquals(persona.profiles.size, layout.pages)
            assertTrue(persona.profiles.all { it.slots.isNotEmpty() })
            val context = FakeContext()
            HomeLayout.save(context, layout, cadran = true)
            // `parse` refuse un widget hors grille ou qui en chevauche un autre : la taille de chacun est aussi gardée.
            val back = HomeLayout.load(context, cadran = true)
            assertEquals(persona.name, layout.widgets.size, back.widgets.size)
            assertEquals(layout.widgets.map { it.size }, back.widgets.map { it.size })
            assertEquals(layout.widgets.map { it.page }, back.widgets.map { it.page })
            assertTrue(layout.widgets.all { it.size in it.type.sizes })
        }
    }

    @Test
    fun `les accueils ne se ressemblent pas`() {
        // Le héros et la ligne du jour sont à deux personas au plus.
        assertTrue(Persona.entries.count { WidgetType.HERO in types(it) } <= 2)
        assertEquals(Persona.entries.size, Persona.entries.map { p -> p.profiles.map { pr -> pr.slots.map { it.type to it.size } } }.toSet().size)
        assertEquals(3, Persona.GWEN.profiles.size)
        assertEquals(2, Persona.CAMILLE.profiles.size)
        assertEquals(1, Persona.AIMEE.profiles.size)
        assertEquals(setOf(WidgetType.NEXT, WidgetType.TASKS, WidgetType.OPEN, WidgetType.AGENDA, WidgetType.KEYS), types(Persona.AIMEE))
        assertEquals(setOf(WidgetType.TASKS, WidgetType.AGENDA, WidgetType.KEYS), types(Persona.NOUR))
        assertEquals(setOf(WidgetType.AGENTS, WidgetType.KEYS, WidgetType.TASKS), types(Persona.SACHA))
    }

    @Test
    fun `un profil de plus que de noms est refuse`() {
        val failed = runCatching { Persona.GWEN.layout(listOf("Gwen"), keys, "ordi") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `un widget Touches ne montre que les touches choisies pour son profil`() {
        val layout = layout(Persona.GWEN, sixKeys)
        val shown = layout.widgets.filter { it.type == WidgetType.KEYS }.map { w -> KeyRef.listFor(w.config, "ordi").map { it.id } }
        assertEquals(listOf(listOf("k-0", "k-1", "k-2", "k-3"), listOf("k-0", "k-4", "k-1", "k-2"), listOf("k-5", "k-3")), shown)
        // Moins de touches que de rangs choisis : les rangs absents sont ignorés.
        val few = layout(Persona.GWEN, keys).widgets.filter { it.type == WidgetType.KEYS }.map { KeyRef.listFor(it.config, "ordi").size }
        assertEquals(listOf(2, 2, 0), few)
    }

    @Test
    fun `l'ecran d'arrivee et les onglets du Deck changent d'un persona a l'autre`() {
        assertEquals(setOf(Persona.NOUR, Persona.SACHA), Persona.entries.filter { it.arrival == Mode.DECK }.toSet())
        assertTrue(Persona.entries.filter { it.arrival == Mode.HOME }.size == 4)
        assertEquals(listOf("page:Excel", "page:Libellés", "page:Dossiers clients", "page:Apps", "open"), Persona.NOUR.deckTabs)
        assertEquals(
            listOf("page:Diapos", "page:Réseaux", "page:Modèles", "page:Réunion", "page:Apps", "page:Média"),
            Persona.GWEN.deckTabs,
        )
        assertEquals(listOf("page:Travail", "page:Maison", "page:Réunion", "page:Apps", "page:Média"), Persona.CAMILLE.deckTabs)
        assertEquals(listOf("open", "page:Projets", "page:Réunion", "jira", "page:Apps"), Persona.AIMEE.deckTabs)
        assertEquals(listOf("page:Mes touches", "page:Apps"), Persona.SAM.deckTabs)
        assertEquals(
            listOf("page:Git", "page:Tests", "page:Déploiement", "gitlab", "jira", "agents", "open"),
            Persona.SACHA.deckTabs,
        )
        assertEquals(Persona.entries.size, Persona.entries.map { it.deckTabs.first() }.toSet().size)
    }

    @Test
    fun `aucun persona n'a l'onglet Perso, GitLab et Agents seulement chez Sacha`() {
        assertTrue(Persona.entries.none { "page:Perso" in it.deckTabs })
        assertEquals(listOf(Persona.SACHA), Persona.entries.filter { "gitlab" in it.deckTabs })
        assertEquals(listOf(Persona.SACHA), Persona.entries.filter { "agents" in it.deckTabs })
        assertEquals(setOf(Persona.AIMEE, Persona.SACHA), Persona.entries.filter { "jira" in it.deckTabs }.toSet())
        // Pas d'onglet en double.
        assertTrue(Persona.entries.all { it.deckTabs.size == it.deckTabs.toSet().size })
    }

    @Test
    fun `les noms des personas`() {
        assertEquals(listOf("nour", "gwen", "camille", "aimee", "sam", "sacha"), Persona.entries.map { it.id })
        for (old in listOf("sylvie", "julie", "thomas", "karim", "leo")) assertNull(old, Persona.of(old))
    }

    @Test
    fun `Sam a le plus grand texte et le mot d'eveil, les autres le texte normal`() {
        assertEquals(1.5f, Persona.SAM.textScale)
        assertEquals(true, Persona.SAM.wakeWord)
        for (persona in Persona.entries - Persona.SAM) {
            assertEquals(1f, persona.textScale)
            assertNull(persona.wakeWord)
        }
    }

    @Test
    fun `les widgets de Sam sont tous en L ou XL`() {
        assertTrue(layout(Persona.SAM).widgets.all { it.size == WidgetSize.L || it.size == WidgetSize.XL })
    }

    @Test
    fun `les touches du persona vont au widget Touches de l'ordinateur actif`() {
        val widget = layout(Persona.NOUR).widgets.single { it.type == WidgetType.KEYS }
        assertEquals(keys, KeyRef.listFor(widget.config, "ordi"))
    }

    @Test
    fun `deux appels ne partagent pas leurs widgets`() {
        val first = layout(Persona.GWEN)
        val second = layout(Persona.GWEN)
        first.widgets.first().col = 3
        assertEquals(0, second.widgets.first().col)
    }

    @Test
    fun `les reponses du compagnon donnent les touches posees`() {
        val json = """{"persona":"sacha","keys":[{"id":"k-0","label":"Tests","glyph":"🧪","color":"#2F855A"},{"id":"k-1","label":"Build"}]}"""
        val parsed = parseDemoKeys(json)
        assertEquals(listOf("k-0", "k-1"), parsed.map { it.id })
        assertEquals("🧪", parsed[0].glyph)
        assertNull(parsed[1].glyph)
    }

    @Test
    fun `la demo remplace l'Accueil puis rend celui d'avant`() {
        val context = FakeContext()
        val mine = HomeLayout(mutableListOf(Placed(WidgetType.NOTE, WidgetSize.S, 0, 3, 3)), 1)
        HomeLayout.save(context, mine, cadran = true)
        Prefs.setTheme(context, "RETRO")

        Demo.start(context, Persona.NOUR, layout(Persona.NOUR), cadran = true)
        assertEquals(Persona.NOUR, Demo.active(context))
        assertEquals(layout(Persona.NOUR).names, HomeLayout.load(context, cadran = true).names)

        // Un autre persona garde la sauvegarde d'origine.
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertEquals(Persona.SAM, Demo.active(context))

        assertTrue(Demo.quit(context, cadran = true))
        assertNull(Demo.active(context))
        assertEquals(listOf(WidgetType.NOTE), HomeLayout.load(context, cadran = true).widgets.map { it.type })
        assertEquals("RETRO", Prefs.theme(context))
        assertFalse(Demo.quit(context, cadran = true))
    }

    @Test
    fun `quitter rend le modele par defaut quand rien n'etait enregistre`() {
        val context = FakeContext()
        Demo.start(context, Persona.SACHA, layout(Persona.SACHA), cadran = true)
        assertTrue(Demo.quit(context, cadran = true))
        assertNull(HomeLayout.raw(context, cadran = true))
        assertNull(Prefs.theme(context))
        assertEquals(Template.defaultFor(true).build().widgets.map { it.type }, HomeLayout.load(context, cadran = true).widgets.map { it.type })
    }

    @Test
    fun `le theme du persona s'applique puis est rendu`() {
        val context = FakeContext()
        Prefs.setTheme(context, "CADRAN")
        // Persona sans thème : le thème ne bouge pas.
        Demo.start(context, Persona.AIMEE, layout(Persona.AIMEE), cadran = true)
        assertEquals("CADRAN", Prefs.theme(context))
        // Sam reçoit le thème Contraste élevé ; le persona suivant, sans thème, rend celui d'avant la démo.
        assertEquals("CONTRASTE", Persona.SAM.theme)
        assertTrue(Persona.entries.filter { it.theme != null } == listOf(Persona.SAM))
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertEquals("CONTRASTE", Prefs.theme(context))
        Demo.start(context, Persona.NOUR, layout(Persona.NOUR), cadran = true)
        assertEquals("CADRAN", Prefs.theme(context))
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        Demo.quit(context, cadran = true)
        assertEquals("CADRAN", Prefs.theme(context))
    }

    @Test
    fun `les onglets du Deck ne se limitent aux onglets du persona que pendant la demo`() {
        val context = FakeContext()
        assertNull(Demo.deckTabs(context))
        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertEquals(Persona.SAM.deckTabs, Demo.deckTabs(context))
        Demo.quit(context, cadran = true)
        assertNull(Demo.deckTabs(context))
    }

    @Test
    fun `la demo garde puis rend l'ordre des onglets, la taille du texte et le mot d'eveil`() {
        val context = FakeContext()
        Prefs.setDeckTabOrder(context, listOf("page:Perso", "open"))
        Prefs.setTextScale(context, 1.15f)
        Prefs.setWakeWord(context, false)

        Demo.start(context, Persona.SAM, layout(Persona.SAM), cadran = true)
        assertEquals(Persona.SAM.deckTabs, Prefs.deckTabOrder(context))
        assertEquals(1.5f, Prefs.textScale(context))
        assertTrue(Prefs.wakeWord(context))

        // Un persona sans mot d'éveil rend celui d'avant la démo ; le texte redevient normal.
        Demo.start(context, Persona.AIMEE, layout(Persona.AIMEE), cadran = true)
        assertEquals(Persona.AIMEE.deckTabs, Prefs.deckTabOrder(context))
        assertEquals(1f, Prefs.textScale(context))
        assertFalse(Prefs.wakeWord(context))

        assertTrue(Demo.quit(context, cadran = true))
        assertEquals(listOf("page:Perso", "open"), Prefs.deckTabOrder(context))
        assertEquals(1.15f, Prefs.textScale(context))
        assertFalse(Prefs.wakeWord(context))
    }

    @Test
    fun `quitter rend l'ordre des onglets par defaut quand rien n'etait choisi`() {
        val context = FakeContext()
        Demo.start(context, Persona.NOUR, layout(Persona.NOUR), cadran = true)
        assertEquals(Persona.NOUR.deckTabs, Prefs.deckTabOrder(context))
        Demo.quit(context, cadran = true)
        assertEquals(emptyList<String>(), Prefs.deckTabOrder(context))
        assertEquals(1f, Prefs.textScale(context))
        assertTrue(Prefs.wakeWord(context))
    }

    @Test
    fun `l'arrivee et l'onglet de depart se lisent une seule fois`() {
        val context = FakeContext()
        assertNull(Demo.takeArrival(context))
        assertEquals(emptyList<String>(), Demo.takeStartTabs(context))

        Demo.start(context, Persona.SACHA, layout(Persona.SACHA), cadran = true)
        assertEquals(Mode.DECK, Demo.takeArrival(context))
        assertNull(Demo.takeArrival(context))
        assertEquals(Persona.SACHA.deckTabs, Demo.takeStartTabs(context))
        assertEquals(emptyList<String>(), Demo.takeStartTabs(context))

        Demo.start(context, Persona.GWEN, layout(Persona.GWEN), cadran = true)
        assertEquals(Mode.HOME, Demo.takeArrival(context))
        // Quitter efface ce qui n'a pas été lu.
        Demo.quit(context, cadran = true)
        assertNull(Demo.takeArrival(context))
        assertEquals(emptyList<String>(), Demo.takeStartTabs(context))
    }
}
