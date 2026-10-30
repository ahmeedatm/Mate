package com.unowhy.unomate.ui.home

import com.unowhy.unomate.net.HomeFeatures
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
        val layout = Template.DECK_CADRAN.build()
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
        for (type in listOf(WidgetType.NEXT, WidgetType.AGENDA, WidgetType.TASKS, WidgetType.FOCUS, WidgetType.APPS, WidgetType.KEYS)) {
            assertTrue("$type", WidgetSize.V in type.sizes)
            assertFalse("$type : V ne doit pas etre la taille d'un ajout", type.sizes.first() == WidgetSize.V)
        }
    }

    @Test
    fun `Accueil de bureau, agenda, Focus, applis et touches sous l'heure`() {
        val bottom = Template.WORK_CADRAN.build().widgets.filter { it.row == 2 }.sortedBy { it.col }.map { it.type to it.size }
        assertEquals(
            listOf(WidgetType.AGENDA, WidgetType.FOCUS, WidgetType.APPS, WidgetType.KEYS).map { it to WidgetSize.V },
            bottom,
        )
    }

    @Test
    fun `Accueil de bureau, la grande heure en haut`() {
        val top = Template.WORK_CADRAN.build().widgets.single { it.row == 0 }
        assertEquals(WidgetType.HERO to WidgetSize.W, top.type to top.size)
        assertEquals(WidgetType.DAY, WidgetType.of("day"))
        assertEquals(listOf(WidgetSize.W), WidgetType.DAY.sizes)
        assertFalse(WidgetType.DAY.computer)
        assertTrue(WidgetType.DAY.bare)
    }

    @Test
    fun `Jira et GitLab, widgets du Deck en XL, L, 3 x 2, 3 x 4 ou plein ecran, seulement si l'ordinateur les offre`() {
        assertEquals(WidgetType.JIRA, WidgetType.of("jira"))
        assertEquals(WidgetType.GITLAB, WidgetType.of("gitlab"))
        for (type in listOf(WidgetType.JIRA, WidgetType.GITLAB)) {
            assertEquals(listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), type.sizes)
            assertTrue(type.computer)
        }
        assertFalse(WidgetType.JIRA.offered(HomeFeatures()))
        assertFalse(WidgetType.GITLAB.offered(HomeFeatures()))
        assertTrue(WidgetType.JIRA.offered(HomeFeatures(jira = true)))
        assertTrue(WidgetType.GITLAB.offered(HomeFeatures(gitlab = true)))
    }

    @Test
    fun `Je m'absente, widget de l'ordinateur en M, L ou S, toujours propose, relu tel quel`() {
        assertEquals(WidgetType.AWAY, WidgetType.of("away"))
        assertEquals(listOf(WidgetSize.M, WidgetSize.L, WidgetSize.S), WidgetType.AWAY.sizes)
        assertTrue(WidgetType.AWAY.computer)
        assertTrue(WidgetType.AWAY.offered(HomeFeatures(off = setOf("open", "mixer", "pad"))))
        val context = com.unowhy.unomate.FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.AWAY, WidgetSize.L, 0, 1, 0)), 1))
        assertEquals(listOf(WidgetType.AWAY to WidgetSize.L), HomeLayout.load(context).widgets.map { it.type to it.size })
    }

    @Test
    fun `Pipelines, console de GitLab en tableau, proposee seulement quand GitLab l'est`() {
        assertEquals(WidgetType.PIPELINES, WidgetType.of("pipelines"))
        assertEquals(listOf(WidgetSize.XL, WidgetSize.L, WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), WidgetType.PIPELINES.sizes)
        assertTrue(WidgetType.PIPELINES.computer)
        assertFalse(WidgetType.PIPELINES.offered(HomeFeatures()))
        assertFalse(WidgetType.PIPELINES.offered(HomeFeatures(jira = true)))
        assertTrue(WidgetType.PIPELINES.offered(HomeFeatures(gitlab = true)))
    }

    @Test
    fun `Agents Claude seulement si l'ordinateur les offre (Mac avec Claude Code)`() {
        assertFalse(WidgetType.AGENTS.offered(HomeFeatures()))
        assertTrue(WidgetType.AGENTS.offered(HomeFeatures(agents = true)))
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
        val context = com.unowhy.unomate.FakeContext()
        context.getSharedPreferences("companion", 0).edit().putString(HomeLayout.KEY, old).apply()
        val layout = HomeLayout.load(context)
        assertEquals(listOf(WidgetType.CLOCK to WidgetSize.L, WidgetType.FOCUS to WidgetSize.S), layout.widgets.map { it.type to it.size })
    }

    @Test
    fun `une taille V enregistree se relit`() {
        val context = com.unowhy.unomate.FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.FOCUS, WidgetSize.V, 0, 3, 2)), 1))
        assertEquals(WidgetSize.V, HomeLayout.load(context).widgets.single().size)
    }

    @Test
    fun `chaque modele d'Accueil commence par un bandeau (heure ou Ma journee) puis la ligne du jour`() {
        for (template in Template.entries.filter { !it.role }) {
            val widgets = template.build().widgets
            val hero = widgets.single { it.type == WidgetType.HERO || it.type == WidgetType.DAY }
            val line = widgets.single { it.type == WidgetType.DAYLINE }
            assertEquals("$template heros", Triple(WidgetSize.W, 0, 0), Triple(hero.size, hero.col, hero.row))
            assertEquals("$template ligne du jour", Triple(WidgetSize.W, 0, 1), Triple(line.size, line.col, line.row))
        }
    }

    @Test
    fun `le modele Deck remplit toute la grille, sans case vide`() {
        val layout = Template.DECK_CADRAN.build()
        for (r in 0 until ROWS) for (c in 0 until COLS) assertTrue("case $c,$r", layout.widgets.any { it.covers(c, r) })
        assertTrue(layout.widgets.all { it.size in it.type.sizes })
    }

    @Test
    fun `le modele par defaut est Travail`() {
        val layout = HomeLayout.load(com.unowhy.unomate.FakeContext())
        assertEquals(Template.WORK_CADRAN.build().widgets.map { it.type }, layout.widgets.map { it.type })
        assertEquals(WidgetType.AGENDA to WidgetSize.V, layout.widgets.single { it.type == WidgetType.AGENDA }.let { it.type to it.size })
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
    fun `widget Presentation, en XL et L, propose a tous et a besoin de l'ordinateur`() {
        assertEquals(WidgetType.PRESENT, WidgetType.of("present"))
        assertEquals(listOf(WidgetSize.XL, WidgetSize.L), WidgetType.PRESENT.sizes)
        assertTrue(WidgetType.PRESENT.computer)
        assertTrue(WidgetType.PRESENT.offered(HomeFeatures()))
        assertTrue(WidgetType.PRESENT.offered(HomeFeatures(off = setOf("open", "mixer", "pad"))))
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

    @Test
    fun `widget maintenu, agrandi dans sa plus grande taille, la plus large a surface egale`() {
        assertEquals(WidgetSize.L, WidgetType.MEDIA.zoomSize)
        assertEquals(WidgetSize.XL, WidgetType.KEYS.zoomSize)
        // Tâches : Grand, Haut (2 × 4), Vertical, Moyen : Haut a la plus grande surface.
        assertEquals(WidgetSize.T, WidgetType.TASKS.zoomSize)
        assertEquals(WidgetSize.M, WidgetType.WATER.zoomSize)
        assertTrue(WidgetType.MEDIA.zoomable)
        assertFalse(WidgetType.HERO.zoomable)
    }

    @Test
    fun `widget Cloture, en L et Haut, tout sur la tablette, config gardee avec la disposition`() {
        assertEquals(WidgetType.CHECKLIST, WidgetType.of("checklist"))
        assertEquals(listOf(WidgetSize.L, WidgetSize.T), WidgetType.CHECKLIST.sizes)
        assertFalse(WidgetType.CHECKLIST.computer)
        assertTrue(WidgetType.CHECKLIST.offered(HomeFeatures()))
        assertEquals(WidgetSize.T, WidgetType.CHECKLIST.zoomSize)
        val config = Checklist.config("Bilan", 1_800_000_000_000L, listOf(ChecklistStep("Balance", true), ChecklistStep("TVA")))
        val context = com.unowhy.unomate.FakeContext()
        HomeLayout.save(context, HomeLayout(mutableListOf(Placed(WidgetType.CHECKLIST, WidgetSize.T, 0, 0, 0, config)), 1))
        val back = HomeLayout.load(context).widgets.single()
        assertEquals(WidgetType.CHECKLIST, back.type)
        assertEquals(Checklist.read(config), Checklist.read(back.config))
    }

    private val boards = listOf(WidgetType.AGENTS, WidgetType.JIRA, WidgetType.GITLAB, WidgetType.PIPELINES, WidgetType.OPEN, WidgetType.MIXER)

    @Test
    fun `les tailles 3 x 2, 3 x 4 et plein ecran sont reservees aux tableaux`() {
        assertEquals(listOf(3 to 2, 3 to 4, 4 to 4), listOf(WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL).map { it.w to it.h })
        for (type in WidgetType.entries) {
            val big = type.sizes.filter { it == WidgetSize.L3 || it == WidgetSize.T3 || it == WidgetSize.FULL }
            if (type in boards) {
                assertEquals("$type", listOf(WidgetSize.L3, WidgetSize.T3, WidgetSize.FULL), big)
                assertFalse("$type : l'ajout ne commence pas en plein ecran", type.sizes.first() == WidgetSize.FULL)
                // Maintenu, le tableau s'agrandit en plein écran.
                assertEquals("$type", WidgetSize.FULL, type.zoomSize)
            } else {
                assertTrue("$type", big.isEmpty())
            }
        }
    }

    @Test
    fun `une taille inconnue relue retombe sur la premiere taille du widget`() {
        val context = com.unowhy.unomate.FakeContext()
        val json = """{"v":2,"pages":1,"widgets":[{"t":"jira","s":"GEANT","p":0,"c":0,"r":0,"cfg":{}},{"t":"clock","s":"FULL","p":0,"c":0,"r":2,"cfg":{}}]}"""
        context.getSharedPreferences("companion", 0).edit().putString(HomeLayout.KEY, json).apply()
        val widgets = HomeLayout.load(context).widgets
        assertEquals(WidgetType.JIRA.sizes.first(), widgets.single { it.type == WidgetType.JIRA }.size)
        // Une taille connue mais refusée par ce widget : la première aussi.
        assertEquals(WidgetType.CLOCK.sizes.first(), widgets.single { it.type == WidgetType.CLOCK }.size)
    }

    @Test
    fun `la poignee choisit la taille permise la plus proche de la zone tiree`() {
        val jira = Placed(WidgetType.JIRA, WidgetSize.L, 0, 0, 0)
        val layout = HomeLayout(mutableListOf(jira), 1)
        assertEquals(WidgetSize.L3, layout.pick(jira, 3f, 2f))
        assertEquals(WidgetSize.FULL, layout.pick(jira, 4f, 4f))
        assertEquals(WidgetSize.T3, layout.pick(jira, 3.1f, 3.7f))
        assertEquals(WidgetSize.XL, layout.pick(jira, 3.6f, 1.2f))
        assertEquals(WidgetSize.L, layout.pick(jira, 0.5f, 0.5f))
        // Taille refusée par le widget : jamais choisie (une horloge n'a pas de plein écran).
        val clock = Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 2)
        layout.widgets += clock
        assertEquals(WidgetSize.XL, layout.pick(clock, 4f, 4f))
    }

    @Test
    fun `la poignee ne choisit que les tailles qui tiennent sans chevaucher`() {
        val agents = Placed(WidgetType.AGENTS, WidgetSize.L, 0, 0, 0)
        val neighbour = Placed(WidgetType.CLOCK, WidgetSize.S, 0, 3, 0)
        val layout = HomeLayout(mutableListOf(agents, neighbour), 1)
        // 4 × 2 et le plein écran touchent la case voisine : la plus proche qui tient est 3 × 4.
        assertEquals(WidgetSize.T3, layout.pick(agents, 4f, 4f))
        assertEquals(WidgetSize.L3, layout.pick(agents, 4f, 2f))
        // Zone tirée vers le bas et la droite : un widget en bas à droite ne grandit pas vers le haut.
        val corner = Placed(WidgetType.GITLAB, WidgetSize.L, 0, 2, 2)
        layout.widgets += corner
        assertEquals(WidgetSize.L, layout.pick(corner, 4f, 4f))
    }

    @Test
    fun `a distance egale la poignee prend la plus petite taille`() {
        val mixer = Placed(WidgetType.MIXER, WidgetSize.XL, 0, 0, 0)
        val layout = HomeLayout(mutableListOf(mixer), 1)
        // 3 × 2 et 4 × 2 sont à égale distance de 3,5 × 2.
        assertEquals(WidgetSize.L3, layout.pick(mixer, 3.5f, 2f))
    }

    @Test
    fun `aucune taille ne tient, rien ne change`() {
        val open = Placed(WidgetType.OPEN, WidgetSize.L, 0, 3, 0)
        // Colonne 2 prise de haut en bas : aucune taille d'Ouvertes (2 cases de large au moins) ne tient.
        val layout = HomeLayout(mutableListOf(open, Placed(WidgetType.FOCUS, WidgetSize.V, 0, 2, 0), Placed(WidgetType.FOCUS, WidgetSize.V, 0, 2, 2)), 1)
        assertNull(layout.pick(open, 4f, 4f))
    }

    @Test
    fun `au bord droit la poignee tiree vers la gauche agrandit vers la gauche`() {
        val keys = Placed(WidgetType.KEYS, WidgetSize.S, 0, 3, 1)
        val layout = HomeLayout(mutableListOf(keys), 1)
        // Doigt dans la colonne 2 : Moyen, case d'origine gardée à droite.
        assertEquals(Fit(WidgetSize.M, 2, 1), layout.pickSize(keys, 2.2f, 1f, 4f, 2f))
        // Doigt en haut à gauche : Grand, qui couvre encore la case d'origine.
        assertEquals(Fit(WidgetSize.L, 2, 0), layout.pickSize(keys, 2f, 0.1f, 4f, 2f))
        // Doigt dans la case d'origine : la taille ne change pas.
        assertEquals(Fit(WidgetSize.S, 3, 1), layout.pickSize(keys, 3f, 1f, 4f, 2f))
    }

    /** Zone tirée depuis la case du widget vers la droite et le bas (`w` × `h` cases) ; seule la taille compte. */
    private fun HomeLayout.pick(placed: Placed, w: Float, h: Float) =
        pickSize(placed, placed.col.toFloat(), placed.row.toFloat(), placed.col + w, placed.row + h)?.size
}
