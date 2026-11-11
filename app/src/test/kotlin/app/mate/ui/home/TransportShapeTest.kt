package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportShapeTest {

    @Test
    fun `chaque forme montre au moins un passage`() {
        for (size in WidgetSize.entries) {
            val shape = TransportShape.of(size)
            assertTrue("${size.name} : ${shape.rows} rangée(s)", shape.rows >= 1)
        }
    }

    @Test
    fun `en ligne pour Pastille et Ligne, empilé et titré sinon`() {
        for (size in WidgetSize.entries) {
            val shape = TransportShape.of(size)
            val inRow = size.family == ShapeFamily.PILL || size.family == ShapeFamily.ROW
            assertEquals("${size.name} : côté", inRow, shape.side)
            assertEquals("${size.name} : titre", !inRow, shape.title)
        }
    }

    @Test
    fun `une ligne montre un passage, deux seulement en 4 x 1`() {
        assertEquals(1, TransportShape.of(WidgetSize.S).rows)
        assertEquals(1, TransportShape.of(WidgetSize.M).rows)
        assertEquals(1, TransportShape.of(WidgetSize.M3).rows)
        assertEquals(2, TransportShape.of(WidgetSize.W).rows)
    }
}
