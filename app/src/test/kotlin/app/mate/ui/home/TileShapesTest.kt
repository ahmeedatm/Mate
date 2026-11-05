package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class TileShapesTest {

    @Test
    fun `fit compte les elements qui tiennent, espaces compris`() {
        assertEquals(3, TileShapes.fit(room = 304, item = 96, gap = 8))
        assertEquals(1, TileShapes.fit(room = 199, item = 96, gap = 8))
        assertEquals(2, TileShapes.fit(room = 200, item = 96, gap = 8))
    }

    @Test
    fun `fit rend au moins un, meme sans place ou avant la mesure`() {
        assertEquals(1, TileShapes.fit(room = 0, item = 96, gap = 8))
        assertEquals(1, TileShapes.fit(room = 40, item = 96, gap = 8))
    }

    @Test
    fun `grandes touches, une par case en ligne`() {
        assertEquals(1, TileShapes.bigColumns(WidgetSize.S))
        assertEquals(2, TileShapes.bigColumns(WidgetSize.M))
        assertEquals(3, TileShapes.bigColumns(WidgetSize.M3))
        assertEquals(4, TileShapes.bigColumns(WidgetSize.W))
    }

    @Test
    fun `grandes touches, deux de front en colonne`() {
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) assertEquals(size.name, 2, TileShapes.bigColumns(size))
    }

    @Test
    fun `la grille sert aux blocs, aux grands et au plein ecran`() {
        val grid = WidgetSize.entries.filter { TileShapes.usesGrid(it) }.map { it.family }.toSet()
        assertEquals(setOf(ShapeFamily.BLOCK, ShapeFamily.LARGE, ShapeFamily.FULL), grid)
    }
}
