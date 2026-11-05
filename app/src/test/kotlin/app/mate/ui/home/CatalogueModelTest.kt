package app.mate.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueModelTest {

    /** Horloge en haut à gauche (2 × 2) : la moitié droite et le bas sont libres. */
    private fun layout() = HomeLayout(mutableListOf(Placed(WidgetType.CLOCK, WidgetSize.L, 0, 0, 0)), 2)

    @Test
    fun `une forme qui tient a la case touchee s y pose`() {
        val choice = layout().shapeChoices(WidgetType.WEATHER, 0, 2, 0).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 2, 0), choice.spot)
    }

    @Test
    fun `une case pres du bord recule pour laisser tenir la forme`() {
        val choice = layout().shapeChoices(WidgetType.WEATHER, 0, 3, 3).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 2, 2), choice.spot)
    }

    @Test
    fun `une forme sans place a la case propose la premiere place libre`() {
        // Bandeau 4 × 1 touché en (2, 0) : la rangée 0 est prise à gauche, la première rangée libre est la 2.
        val choice = layout().shapeChoices(WidgetType.CLOCK, 0, 2, 0).first { it.size == WidgetSize.W }
        assertFalse(choice.atCase)
        assertEquals(Fit(WidgetSize.W, 0, 2), choice.spot)
    }

    @Test
    fun `une forme qui ne tient nulle part n a pas de place`() {
        val full = HomeLayout(mutableListOf(Placed(WidgetType.CLOCK, WidgetSize.FULL, 0, 0, 0)), 1)
        full.shapeChoices(WidgetType.CLOCK, 0, 1, 1).forEach {
            assertFalse(it.atCase)
            assertNull(it.spot)
        }
    }

    @Test
    fun `seules les formes du widget sont proposees, dans son ordre`() {
        val sizes = layout().shapeChoices(WidgetType.WEATHER, 0, 2, 0).map { it.size }
        assertEquals(WidgetType.WEATHER.sizes, sizes)
    }

    @Test
    fun `sans case touchee, toute forme qui tient quelque part est posable`() {
        val choice = layout().shapeChoices(WidgetType.CLOCK, 0, -1, -1).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 2, 0), choice.spot)
    }

    @Test
    fun `l autre page ne gene pas`() {
        val choice = layout().shapeChoices(WidgetType.CLOCK, 1, 0, 0).first { it.size == WidgetSize.L }
        assertTrue(choice.atCase)
        assertEquals(Fit(WidgetSize.L, 0, 0), choice.spot)
    }
}
