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
}
