package app.mate.ui

import app.mate.ui.home.WidgetSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListShapeTest {
    @Test
    fun `la pastille montre la tete seule`() {
        val shape = ListShape.of(WidgetSize.S)
        assertFalse(shape.list)
        assertFalse(shape.addRow)
    }

    @Test
    fun `la ligne met la tete a gauche et une colonne de liste par case en plus`() {
        assertEquals(listOf(1, 2, 3), listOf(WidgetSize.M, WidgetSize.M3, WidgetSize.W).map { ListShape.of(it).columns })
        assertTrue(ListShape.of(WidgetSize.W).sideHead)
        assertFalse(ListShape.of(WidgetSize.M).addRow)
    }

    @Test
    fun `la colonne coche d'un toucher sur la ligne, ajoute des 1 x 3`() {
        val narrow = ListShape.of(WidgetSize.V)
        assertTrue(narrow.list)
        assertFalse(narrow.checks)
        assertFalse(narrow.addRow)
        assertTrue(ListShape.of(WidgetSize.V3).addRow)
        assertEquals(1, ListShape.of(WidgetSize.V4).columns)
    }

    @Test
    fun `plus large, plus de colonnes`() {
        assertEquals(1, ListShape.of(WidgetSize.L).columns)
        assertEquals(1, ListShape.of(WidgetSize.L23).columns)
        assertEquals(2, ListShape.of(WidgetSize.L3).columns)
        assertEquals(2, ListShape.of(WidgetSize.L33).columns)
        assertEquals(1, ListShape.of(WidgetSize.T).columns)
        assertEquals(2, ListShape.of(WidgetSize.XL).columns)
        assertEquals(2, ListShape.of(WidgetSize.T3).columns)
        assertEquals(2, ListShape.of(WidgetSize.XL3).columns)
        assertEquals(3, ListShape.of(WidgetSize.FULL).columns)
    }

    @Test
    fun `des le bloc, cases et ajout sont la, tete au-dessus`() {
        for (size in WidgetSize.entries.filter { it.w >= 2 && it.h >= 2 }) {
            val shape = ListShape.of(size)
            assertTrue(size.name, shape.checks && shape.addRow && shape.list && !shape.sideHead)
        }
    }

    @Test
    fun `la tete se resserre seulement en pastille ou en ligne, dans une rangee basse`() {
        assertTrue(ListShape.of(WidgetSize.S).compact(44))
        assertTrue(ListShape.of(WidgetSize.W).compact(44))
        assertFalse(ListShape.of(WidgetSize.S).compact(136))
        assertFalse(ListShape.of(WidgetSize.S).compact(0))
        assertFalse(ListShape.of(WidgetSize.V).compact(44))
        assertFalse(ListShape.of(WidgetSize.L).compact(44))
    }

    @Test
    fun `les lignes se rangent ligne par ligne`() {
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5)), ListShape.rows(listOf(1, 2, 3, 4, 5), columns = 3))
        assertEquals(listOf(listOf(1), listOf(2)), ListShape.rows(listOf(1, 2), columns = 1))
        assertEquals(emptyList<List<Int>>(), ListShape.rows(emptyList<Int>(), columns = 2))
    }
}
