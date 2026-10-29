package com.unowhy.companion.ui.home

import com.unowhy.companion.FakeContext
import com.unowhy.companion.Prefs
import com.unowhy.companion.net.parseDemoKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonasTest {

    private val keys = listOf(KeyRef("k-0", "Excel", "📊", "#1D6F42"), KeyRef("k-1", "Enregistrer"))

    @Test
    fun `six personas aux ids uniques`() {
        assertEquals(6, Persona.entries.size)
        assertEquals(Persona.entries.size, Persona.entries.map { it.id }.toSet().size)
        assertEquals(Persona.LEO, Persona.of("leo"))
        assertNull(Persona.of("inconnu"))
    }

    @Test
    fun `chaque persona donne un seul profil nomme, relisible tel quel`() {
        for (persona in Persona.entries) {
            val layout = persona.layout(persona.name, keys, "ordi")
            assertEquals(listOf<String?>(persona.name), layout.names)
            assertTrue(layout.widgets.isNotEmpty())
            val context = FakeContext()
            HomeLayout.save(context, layout, cadran = true)
            // `parse` refuse un widget hors grille ou qui en chevauche un autre : la taille de chacun est aussi gardée.
            val back = HomeLayout.load(context, cadran = true)
            assertEquals(persona.name, layout.widgets.size, back.widgets.size)
            assertEquals(layout.widgets.map { it.size }, back.widgets.map { it.size })
            assertTrue(layout.widgets.all { it.size in it.type.sizes })
        }
    }

    @Test
    fun `les widgets de Karim sont tous en L ou XL`() {
        assertTrue(Persona.KARIM.layout("Karim", keys, "ordi").widgets.all { it.size == WidgetSize.L || it.size == WidgetSize.XL })
    }

    @Test
    fun `les touches du persona vont au widget Touches de l'ordinateur actif`() {
        val widget = Persona.SYLVIE.layout("Sylvie", keys, "ordi").widgets.single { it.type == WidgetType.KEYS }
        assertEquals(keys, KeyRef.listFor(widget.config, "ordi"))
    }

    @Test
    fun `deux appels ne partagent pas leurs widgets`() {
        val first = Persona.JULIE.layout("Julie", keys, "ordi")
        val second = Persona.JULIE.layout("Julie", keys, "ordi")
        first.widgets.first().col = 3
        assertEquals(0, second.widgets.first().col)
    }

    @Test
    fun `les reponses du compagnon donnent les touches posees`() {
        val json = """{"persona":"leo","keys":[{"id":"k-0","label":"Tests","glyph":"🧪","color":"#2F855A"},{"id":"k-1","label":"Build"}]}"""
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

        Demo.start(context, Persona.SYLVIE, Persona.SYLVIE.layout("Sylvie", keys, "ordi"), cadran = true)
        assertEquals(Persona.SYLVIE, Demo.active(context))
        assertEquals(listOf<String?>("Sylvie"), HomeLayout.load(context, cadran = true).names)

        // Un autre persona garde la sauvegarde d'origine.
        Demo.start(context, Persona.KARIM, Persona.KARIM.layout("Karim", keys, "ordi"), cadran = true)
        assertEquals(Persona.KARIM, Demo.active(context))

        assertTrue(Demo.quit(context, cadran = true))
        assertNull(Demo.active(context))
        assertEquals(listOf(WidgetType.NOTE), HomeLayout.load(context, cadran = true).widgets.map { it.type })
        assertEquals("RETRO", Prefs.theme(context))
        assertFalse(Demo.quit(context, cadran = true))
    }

    @Test
    fun `quitter rend le modele par defaut quand rien n'etait enregistre`() {
        val context = FakeContext()
        Demo.start(context, Persona.LEO, Persona.LEO.layout("Léo", keys, "ordi"), cadran = true)
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
        Demo.start(context, Persona.THOMAS, Persona.THOMAS.layout("Thomas", keys, "ordi"), cadran = true)
        assertEquals("CADRAN", Prefs.theme(context))
        Demo.quit(context, cadran = true)
        assertEquals("CADRAN", Prefs.theme(context))
    }
}
