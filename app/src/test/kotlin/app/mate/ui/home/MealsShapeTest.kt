package app.mate.ui.home

import app.mate.net.MenuDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealsShapeTest {

    @Test
    fun `pastille le repas en cours, ligne un repas par case en plus`() {
        assertEquals(1, MealsShape.of(WidgetSize.S).meals)
        assertTrue(MealsShape.of(WidgetSize.S).side)
        assertEquals(1, MealsShape.of(WidgetSize.M).meals)
        assertEquals(2, MealsShape.of(WidgetSize.M3).meals)
        assertEquals(3, MealsShape.of(WidgetSize.W).meals)
        assertTrue(MealsShape.of(WidgetSize.W).side)
    }

    @Test
    fun `colonne, midi et soir empiles, les quatre des 1 x 3`() {
        assertEquals(2, MealsShape.of(WidgetSize.V).meals)
        assertEquals(4, MealsShape.of(WidgetSize.V3).meals)
        assertFalse(MealsShape.of(WidgetSize.V).side)
        assertFalse(MealsShape.of(WidgetSize.V).title)
    }

    @Test
    fun `bloc et grand, les quatre repas, aujourd'hui et demain cote a cote des 3 cases de large`() {
        assertEquals(4, MealsShape.of(WidgetSize.L).meals)
        assertEquals(1, MealsShape.of(WidgetSize.L).columns)
        assertEquals(2, MealsShape.of(WidgetSize.XL).columns)
        assertEquals(2, MealsShape.of(WidgetSize.L33).columns)
        assertTrue(MealsShape.of(WidgetSize.T).title)
        assertFalse(MealsShape.of(WidgetSize.XL).week)
    }

    @Test
    fun `plein ecran, la semaine`() {
        assertTrue(MealsShape.of(WidgetSize.FULL).week)
    }

    @Test
    fun `les repas a montrer partent du repas en cours`() {
        val week = List(7) { MenuDay("midi $it", "soir $it") }
        // Mardi (1) à 16 h : le déjeuner est passé.
        val lines = mealLines(week, weekday = 1, minuteOfDay = 16 * 60)
        assertEquals(listOf("soir 1", "midi 2"), visibleMeals(lines, 2).map { it.dish })
        assertEquals(listOf("soir 1"), visibleMeals(lines, 1).map { it.dish })
        // Les quatre : rien n'est retiré, le passé reste estompé.
        assertEquals(4, visibleMeals(lines, 4).size)
    }

    @Test
    fun `la semaine commence aujourd'hui`() {
        val week = List(7) { MenuDay("midi $it", "") }
        val days = weekFrom(week, weekday = 5)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), days.map { it.offset })
        assertEquals(listOf(5, 6, 0, 1, 2, 3, 4), days.map { it.weekday })
        assertEquals("midi 5", days.first().day.lunch)
    }
}
