package com.unowhy.companion.ui.home

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
    fun `compact retire les pages vides et renumerote`() {
        val far = Placed(WidgetType.NOTE, WidgetSize.S, 2, 0, 0)
        val layout = HomeLayout(mutableListOf(Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0), far), 3)
        layout.compact()
        assertEquals(2, layout.pages)
        assertEquals(1, far.page)
    }

    @Test
    fun `la taille V est acceptee par Agenda, Taches, Focus et Touches`() {
        for (type in listOf(WidgetType.NEXT, WidgetType.TASKS, WidgetType.FOCUS, WidgetType.KEYS)) {
            assertTrue("$type", WidgetSize.V in type.sizes)
            assertFalse("$type : V ne doit pas etre la taille d'un ajout", type.sizes.first() == WidgetSize.V)
        }
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
}
