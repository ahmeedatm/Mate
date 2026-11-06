package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockFaceTest {

    @Test
    fun `pastille et colonne dessinent le cadran, la pastille y met la date courte`() {
        val pill = ClockFace.of(WidgetSize.S)
        assertTrue(pill.dial)
        assertTrue(pill.dateInDial)
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) {
            val column = ClockFace.of(size)
            assertTrue(size.name, column.dial)
            assertFalse(size.name, column.dateInDial)
        }
    }

    @Test
    fun `ligne et bloc sans cadran, avec la salutation`() {
        for (size in WidgetSize.entries.filter { it.family == ShapeFamily.ROW || it.family == ShapeFamily.BLOCK }) {
            val face = ClockFace.of(size)
            assertFalse(size.name, face.dial)
            assertTrue(size.name, face.greeting)
            assertFalse(size.name, face.stacked)
            assertFalse(size.name, face.seconds)
        }
    }

    @Test
    fun `grand etroit empile heures et minutes, grand large les garde sur une ligne`() {
        assertTrue(ClockFace.of(WidgetSize.T).stacked)
        assertTrue(ClockFace.of(WidgetSize.T3).stacked)
        assertFalse(ClockFace.of(WidgetSize.XL).stacked)
        assertFalse(ClockFace.of(WidgetSize.XL3).stacked)
    }

    @Test
    fun `seul le plein ecran montre les secondes`() {
        assertEquals(listOf(WidgetSize.FULL), WidgetSize.entries.filter { ClockFace.of(it).seconds })
    }

    @Test
    fun `une graduation allumee par minute passee`() {
        assertEquals(0, ClockFace.lit(0))
        assertEquals(32, ClockFace.lit(32))
        assertEquals(59, ClockFace.lit(59))
        assertEquals(ClockFace.TICKS - 1, ClockFace.lit(75))
    }
}
