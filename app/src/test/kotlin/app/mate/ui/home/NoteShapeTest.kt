package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteShapeTest {

    @Test
    fun `pastille et ligne, l'icone a cote du texte, sans titre`() {
        for (size in listOf(WidgetSize.S, WidgetSize.M, WidgetSize.M3, WidgetSize.W)) {
            val shape = NoteShape.of(size)
            assertTrue("$size", shape.side)
            assertFalse("$size", shape.title)
        }
    }

    @Test
    fun `colonne, l'icone au-dessus du texte qui passe a la ligne`() {
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) {
            val shape = NoteShape.of(size)
            assertFalse("$size", shape.side)
            assertFalse("$size", shape.title)
        }
    }

    @Test
    fun `plus la forme est grande, plus le texte peut grossir`() {
        val max = WidgetSize.entries.associateWith { NoteShape.of(it).maxSp }
        assertTrue(max.getValue(WidgetSize.S) < max.getValue(WidgetSize.L))
        assertTrue(max.getValue(WidgetSize.L) < max.getValue(WidgetSize.XL))
        assertTrue(max.getValue(WidgetSize.XL) < max.getValue(WidgetSize.FULL))
        assertEquals(max.values.max(), max.getValue(WidgetSize.FULL))
        for (size in WidgetSize.entries) assertTrue("$size", NoteShape.of(size).minSp < NoteShape.of(size).maxSp)
    }

    @Test
    fun `bloc et plus grand, l'icone et le titre en tete`() {
        for (size in WidgetSize.entries.filter { it.family in setOf(ShapeFamily.BLOCK, ShapeFamily.LARGE, ShapeFamily.FULL) }) {
            val shape = NoteShape.of(size)
            assertFalse("$size", shape.side)
            assertTrue("$size", shape.title)
        }
    }
}
