package app.mate.ui.home

import app.mate.FakeContext
import app.mate.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutKeyTest {

    @Test
    fun `la cle d'avant reste lue`() {
        assertEquals("home_layout_cadran", HomeLayout.KEY)
    }

    @Test
    fun `sans disposition enregistree, le modele Travail du theme s'applique`() {
        val context = FakeContext()
        assertNull(HomeLayout.raw(context))
        assertEquals(Template.WORK_CADRAN.build().widgets.map { it.type }, HomeLayout.load(context).widgets.map { it.type })
        Prefs.setTheme(context, "INSTRUMENT")
        assertEquals(Template.WORK_INSTRUMENT.build().widgets.map { it.type }, HomeLayout.load(context).widgets.map { it.type })
    }

    @Test
    fun `has lit la disposition enregistree`() {
        val context = FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.WATER, WidgetSize.S, 0, 0, 0)), 1))
        assertTrue(HomeLayout.has(context, WidgetType.WATER))
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.NOTE, WidgetSize.S, 0, 0, 0)), 1))
        assertFalse(HomeLayout.has(context, WidgetType.WATER))
    }

    @Test
    fun `sur Maison, l'Accueil par defaut montre l'heure, la meteo, les transports et la maison, sans rien du PC`() {
        val context = FakeContext()
        app.mate.Computers.setHome(context)
        val types = HomeLayout.load(context).widgets.map { it.type }
        assertEquals(setOf(WidgetType.CLOCK, WidgetType.WEATHER, WidgetType.TRANSPORT, WidgetType.HA), types.toSet())
        Prefs.setTheme(context, "INSTRUMENT")
        assertEquals(setOf(WidgetType.CLOCK, WidgetType.WEATHER, WidgetType.TRANSPORT, WidgetType.HA), HomeLayout.load(context).widgets.map { it.type }.toSet())
    }
}
