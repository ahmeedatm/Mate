package com.unowhy.companion.ui.home

import com.unowhy.companion.net.HomeFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {

    @Test
    fun `chaque modele tient dans la grille sans chevauchement`() {
        for (template in Template.entries) {
            val layout = HomeLayout(mutableListOf(), 1)
            for (w in template.build().widgets) {
                assertTrue("$template : ${w.type} hors grille ou chevauche", layout.fits(w.page, w.col, w.row, w.size))
                assertTrue("$template : taille ${w.size} refusée par ${w.type}", w.size in w.type.sizes)
                layout.widgets += w
            }
        }
    }

    @Test
    fun `refuse une case hors de la grille`() {
        val layout = HomeLayout(mutableListOf(), 1)
        assertFalse(layout.fits(0, 3, 0, WidgetSize.M))
        assertFalse(layout.fits(0, 0, 3, WidgetSize.L))
        assertFalse(layout.fits(0, -1, 0, WidgetSize.S))
        assertTrue(layout.fits(0, 0, 0, WidgetSize.XL))
    }

    @Test
    fun `premiere case libre de gauche a droite puis de haut en bas`() {
        val layout = HomeLayout(mutableListOf(Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0)), 1)
        assertEquals(2 to 0, layout.freeSpot(0, WidgetSize.L))
        assertEquals(0 to 2, layout.freeSpot(0, WidgetSize.W))
        assertEquals(0 to 0, layout.freeSpot(1, WidgetSize.XL))
    }

    @Test
    fun `aucune case libre sur une page pleine`() {
        val layout = Template.WORK.build()
        assertNull(layout.freeSpot(0, WidgetSize.L))
    }

    @Test
    fun `un widget deplace ne se gene pas lui-meme`() {
        val clock = Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0)
        val layout = HomeLayout(mutableListOf(clock), 1)
        assertFalse(layout.fits(0, 1, 0, WidgetSize.L))
        assertTrue(layout.fits(0, 1, 0, WidgetSize.L, ignore = clock))
    }

    @Test
    fun `les modeles construisent un seul profil sans nom`() {
        assertEquals(listOf<String?>(null), Template.WORK_CADRAN.build().names)
    }

    @Test
    fun `la taille V est acceptee par Agenda, Taches, Focus, Applis et Touches`() {
        for (type in listOf(WidgetType.NEXT, WidgetType.TASKS, WidgetType.FOCUS, WidgetType.APPS, WidgetType.KEYS)) {
            assertTrue("$type", WidgetSize.V in type.sizes)
            assertFalse("$type : V ne doit pas etre la taille d'un ajout", type.sizes.first() == WidgetSize.V)
        }
    }

    @Test
    fun `Accueil de bureau, les applis du travail a la place du Focus`() {
        val bottom = Template.WORK_CADRAN.build().widgets.filter { it.row == 2 }.sortedBy { it.col }.map { it.type to it.size }
        assertEquals(
            listOf(WidgetType.NEXT, WidgetType.TASKS, WidgetType.APPS, WidgetType.KEYS).map { it to WidgetSize.V },
            bottom,
        )
    }

    @Test
    fun `Accueil de bureau, Ma journee a la place de la grande heure`() {
        val top = Template.WORK_CADRAN.build().widgets.single { it.row == 0 }
        assertEquals(WidgetType.DAY to WidgetSize.W, top.type to top.size)
        assertEquals(WidgetType.DAY, WidgetType.of("day"))
        assertEquals(listOf(WidgetSize.W), WidgetType.DAY.sizes)
        assertFalse(WidgetType.DAY.computer)
        assertTrue(WidgetType.DAY.bare)
    }

    @Test
    fun `Jira et GitLab, widgets du Deck en XL ou L, seulement si l'ordinateur les offre`() {
        assertEquals(WidgetType.JIRA, WidgetType.of("jira"))
        assertEquals(WidgetType.GITLAB, WidgetType.of("gitlab"))
        for (type in listOf(WidgetType.JIRA, WidgetType.GITLAB)) {
            assertEquals(listOf(WidgetSize.XL, WidgetSize.L), type.sizes)
            assertTrue(type.computer)
        }
        assertFalse(WidgetType.JIRA.offered(HomeFeatures()))
        assertFalse(WidgetType.GITLAB.offered(HomeFeatures()))
        assertTrue(WidgetType.JIRA.offered(HomeFeatures(jira = true)))
        assertTrue(WidgetType.GITLAB.offered(HomeFeatures(gitlab = true)))
    }

    @Test
    fun `un plugin coupe retire son widget du catalogue, les autres restent`() {
        val off = HomeFeatures(off = setOf("open", "mixer", "pad"))
        assertFalse(WidgetType.OPEN.offered(off))
        assertFalse(WidgetType.MIXER.offered(off))
        assertFalse(WidgetType.PAD.offered(off))
        assertTrue(WidgetType.OPEN.offered(HomeFeatures()))
        assertTrue(WidgetType.CLOCK.offered(off))
        assertTrue(WidgetType.KEYS.offered(off))
    }

    @Test
    fun `V occupe une colonne sur deux lignes`() {
        assertEquals(1, WidgetSize.V.w)
        assertEquals(2, WidgetSize.V.h)
        val layout = HomeLayout(mutableListOf(), 1)
        assertTrue(layout.fits(0, 3, 2, WidgetSize.V))
        assertFalse(layout.fits(0, 3, 3, WidgetSize.V))
        layout.widgets += Placed(WidgetType.FOCUS, WidgetSize.V, 0, 3, 2)
        assertFalse(layout.fits(0, 3, 3, WidgetSize.S))
        assertTrue(layout.fits(0, 2, 2, WidgetSize.V))
    }

    @Test
    fun `une disposition enregistree sans V se relit a l'identique`() {
        val old = """{"v":1,"pages":1,"widgets":[{"t":"clock","s":"L","p":0,"c":0,"r":0,"cfg":{}},{"t":"focus","s":"S","p":0,"c":0,"r":3,"cfg":{}}]}"""
        val context = com.unowhy.companion.FakeContext()
        context.getSharedPreferences("companion", 0).edit().putString("home_layout", old).apply()
        val layout = HomeLayout.load(context, cadran = false)
        assertEquals(listOf(WidgetType.CLOCK to WidgetSize.L, WidgetType.FOCUS to WidgetSize.S), layout.widgets.map { it.type to it.size })
    }

    @Test
    fun `une taille V enregistree se relit`() {
        val context = com.unowhy.companion.FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.FOCUS, WidgetSize.V, 0, 3, 2)), 1), cadran = true)
        assertEquals(WidgetSize.V, HomeLayout.load(context, cadran = true).widgets.single().size)
    }

    @Test
    fun `chaque modele Cadran commence par un bandeau (heure ou Ma journee) puis la ligne du jour`() {
        for (template in Template.entries.filter { it.cadran && !it.role }) {
            val widgets = template.build().widgets
            val hero = widgets.single { it.type == WidgetType.HERO || it.type == WidgetType.DAY }
            val line = widgets.single { it.type == WidgetType.DAYLINE }
            assertEquals("$template heros", Triple(WidgetSize.W, 0, 0), Triple(hero.size, hero.col, hero.row))
            assertEquals("$template ligne du jour", Triple(WidgetSize.W, 0, 1), Triple(line.size, line.col, line.row))
        }
    }

    @Test
    fun `les deux modeles existent aussi en Cadran, avec les memes titres`() {
        val plain = Template.entries.filter { !it.cadran }.map { it.title }
        val cadran = Template.entries.filter { it.cadran && !it.role }.map { it.title }
        assertEquals(plain, cadran)
    }

    @Test
    fun `le modele Deck remplit toute la grille, sans case vide`() {
        val layout = Template.DECK_CADRAN.build()
        for (r in 0 until ROWS) for (c in 0 until COLS) assertTrue("case $c,$r", layout.widgets.any { it.covers(c, r) })
        assertTrue(layout.widgets.all { it.size in it.type.sizes })
    }

    @Test
    fun `le modele par defaut de Cadran est Travail Cadran, celui des autres reste Travail`() {
        assertTrue(Template.defaultFor(true).cadran)
        assertEquals(Template.WORK.title, Template.defaultFor(true).title)
        assertEquals(WidgetType.NEXT to WidgetSize.V, Template.defaultFor(true).build().widgets.single { it.type == WidgetType.NEXT }.let { it.type to it.size })
        assertEquals(Template.WORK, Template.defaultFor(false))
    }

    @Test
    fun `le heros et la ligne du jour n'ont besoin que de la largeur de la grille`() {
        assertEquals(listOf(WidgetSize.W), WidgetType.HERO.sizes)
        assertEquals(listOf(WidgetSize.W), WidgetType.DAYLINE.sizes)
        assertFalse(WidgetType.HERO.computer)
        assertFalse(WidgetType.DAYLINE.computer)
    }

    @Test
    fun `widget d'appel generique, en XL et L, remplit le modele Reunion`() {
        assertEquals(WidgetType.CALL, WidgetType.of("call"))
        assertEquals(listOf(WidgetSize.XL, WidgetSize.L), WidgetType.CALL.sizes)
        val layout = Template.CALL_CADRAN.build()
        assertTrue(layout.widgets.any { it.type == WidgetType.CALL && it.size == WidgetSize.XL })
        for (r in 0 until ROWS) for (c in 0 until COLS) assertTrue("case $c,$r", layout.widgets.any { it.covers(c, r) })
    }

    @Test
    fun `config de base bureau, les touches de l'Accueil et du Deck arrivent remplies`() {
        for (template in listOf(Template.WORK_CADRAN, Template.DECK_CADRAN)) {
            val keys = template.build().widgets.single { it.type == WidgetType.KEYS }
            assertEquals("$template", "Raccourcis", keys.config.optString(KeyRef.DEFAULT_PAGE))
        }
    }

    @Test
    fun `config de base bureau, le Deck montre les fenetres, la musique reste dans le header`() {
        val deck = Template.DECK_CADRAN.build().widgets.map { it.type }
        assertTrue(WidgetType.OPEN in deck)
        assertFalse(WidgetType.MEDIA in deck)
    }
}
