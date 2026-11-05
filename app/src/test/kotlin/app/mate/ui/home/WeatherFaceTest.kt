package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherFaceTest {

    private fun of(size: WidgetSize) = WeatherFace.of(size)

    @Test
    fun `pastille, la temperature et une petite courbe, sans details`() {
        val pill = of(WidgetSize.S)
        assertEquals(WeatherFace.Curve.ACROSS, pill.curve)
        assertFalse(pill.details)
        assertFalse(pill.days)
    }

    @Test
    fun `ligne, les details, la courbe seulement en 4 x 1`() {
        for (size in listOf(WidgetSize.M, WidgetSize.M3)) {
            assertTrue(size.name, of(size).details)
            assertEquals(size.name, WeatherFace.Curve.NONE, of(size).curve)
        }
        assertEquals(WeatherFace.Curve.ACROSS, of(WidgetSize.W).curve)
    }

    @Test
    fun `colonne, les prochaines heures de haut en bas`() {
        for (size in listOf(WidgetSize.V, WidgetSize.V3, WidgetSize.V4)) {
            assertEquals(size.name, WeatherFace.Curve.DOWN, of(size).curve)
            assertFalse(size.name, of(size).days)
        }
    }

    @Test
    fun `bloc, les jours suivants, et les heures des qu'il a 3 cases d'un cote`() {
        assertTrue(of(WidgetSize.L).days)
        assertEquals(WeatherFace.Curve.NONE, of(WidgetSize.L).curve)
        for (size in listOf(WidgetSize.L3, WidgetSize.L23, WidgetSize.L33)) {
            assertTrue(size.name, of(size).days)
            assertEquals(size.name, WeatherFace.Curve.ACROSS, of(size).curve)
        }
    }

    @Test
    fun `grand et plein ecran, tout, qualite de l'air comprise`() {
        for (size in WidgetSize.entries.filter { it.family == ShapeFamily.LARGE || it.family == ShapeFamily.FULL }) {
            val face = of(size)
            assertEquals(size.name, WeatherFace.Curve.ACROSS, face.curve)
            assertTrue(size.name, face.days && face.details && face.air)
        }
        assertFalse(of(WidgetSize.S).air)
    }

    @Test
    fun `large et basse, la courbe passe a cote de la temperature`() {
        assertEquals(listOf(WidgetSize.XL, WidgetSize.L3), WidgetSize.entries.filter { of(it).curveBeside })
    }
}
