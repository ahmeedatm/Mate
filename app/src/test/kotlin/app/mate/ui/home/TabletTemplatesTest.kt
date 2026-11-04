package app.mate.ui.home

import app.mate.FakeContext
import app.mate.Mode
import app.mate.Prefs
import app.mate.net.HomeFeatures
import app.mate.ui.topbar.TopBarModel
import app.mate.ui.topbar.TopItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabletTemplatesTest {

    private val all = HomeFeatures(agents = true, gitlab = true, jira = true)

    private fun layout(template: TabletTemplate, features: HomeFeatures?) =
        template.layout({ template.screens.first { s -> s.name == it }.let { s -> "écran ${template.screens.indexOf(s)}" } }, "Réunion", features)

    private fun shape(layout: HomeLayout) = layout.widgets.map { listOf(it.type.key, it.size.name, it.page, it.col, it.row) }

    @Test
    fun `deux templates aux ids uniques`() {
        assertEquals(listOf("bureau", "dev"), TabletTemplate.entries.map { it.id })
        assertEquals(TabletTemplate.DEV, TabletTemplate.of("dev"))
        assertNull(TabletTemplate.of("inconnu"))
    }

    @Test
    fun `dev pose l'Accueil du jour puis un ecran plein par outil, Reunion en dernier`() {
        val layout = layout(TabletTemplate.DEV, all)
        assertEquals(listOf("écran 0", "écran 1", "écran 2", "écran 3", "écran 4", "Réunion"), layout.names)
        assertEquals(listOf(null, "agents", "present", "bolt", "doc", null), layout.names.indices.map { layout.iconOf(it) })
        assertEquals(0, layout.homeProfile)
        assertEquals(-1, layout.deckProfile)
        assertEquals(5, layout.callProfile)
        assertEquals(
            listOf(
                listOf("day", "W", 0, 0, 0),
                listOf("dayline", "W", 0, 0, 1),
                listOf("next", "V", 0, 0, 2),
                listOf("tasks", "V", 0, 1, 2),
                listOf("apps", "V", 0, 2, 2),
                listOf("keys", "V", 0, 3, 2),
                listOf("agents", "FULL", 1, 0, 0),
                listOf("gitlab", "FULL", 2, 0, 0),
                listOf("pipelines", "FULL", 3, 0, 0),
                listOf("jira", "FULL", 4, 0, 0),
                listOf("call", "XL", 5, 0, 0),
                listOf("next", "L", 5, 0, 2),
                listOf("audio", "L", 5, 2, 2),
            ),
            shape(layout),
        )
        // Touches de départ : les 9 premières de la page Raccourcis de l'ordinateur (KeyRef.seed).
        assertEquals("Raccourcis", layout.widgets.first { it.type == WidgetType.KEYS }.config.getString(KeyRef.DEFAULT_PAGE))
    }

    @Test
    fun `dev saute les ecrans que l'ordinateur ne propose pas`() {
        assertEquals(listOf("écran 0", "Réunion"), layout(TabletTemplate.DEV, HomeFeatures()).names)
        assertEquals(listOf("écran 0", "écran 2", "écran 3", "Réunion"), layout(TabletTemplate.DEV, HomeFeatures(gitlab = true)).names)
        // Ordinateur injoignable : tout est posé.
        assertEquals(6, layout(TabletTemplate.DEV, null).names.size)
    }

    @Test
    fun `bureau rend la config d'une tablette neuve`() {
        val fresh = HomeLayout.load(FakeContext()).also { it.ensureRoles("Réunion") }
        assertEquals(shape(fresh), shape(layout(TabletTemplate.BUREAU, all)))
    }

    @Test
    fun `chaque template se relit tel quel`() {
        for (template in TabletTemplate.entries) {
            val layout = layout(template, all)
            val context = FakeContext()
            HomeLayout.save(context, layout)
            // `parse` refuse un widget hors grille, d'une taille non permise ou qui en chevauche un autre.
            assertEquals(template.name, shape(layout), shape(HomeLayout.load(context)))
        }
    }

    @Test
    fun `apply pose disposition, theme, bandeau et barre, et l'arrivee une seule fois`() {
        val context = FakeContext()
        Prefs.setTopBar(context, TopBarModel.serialize(listOf(TopItem.CLOCK)))
        Prefs.setRailLabels(context, false)
        Prefs.setRailTool(context, Mode.FOCUS, false)
        val layout = layout(TabletTemplate.DEV, all)
        TabletTemplates.apply(context, TabletTemplate.DEV, layout)
        assertEquals(shape(layout), shape(HomeLayout.load(context)))
        assertEquals("CADRAN_NUIT", Prefs.theme(context))
        assertEquals(TopBarModel.DEFAULT, TopBarModel.parse(Prefs.topBar(context)))
        assertTrue(Prefs.railLabels(context))
        assertEquals(listOf(Mode.FOCUS, Mode.COMPUTER), Prefs.railTools(context))
        assertTrue(TabletTemplates.takeArrival(context))
        assertFalse(TabletTemplates.takeArrival(context))

        TabletTemplates.apply(context, TabletTemplate.BUREAU, layout(TabletTemplate.BUREAU, all))
        assertNull(Prefs.theme(context))
    }
}
