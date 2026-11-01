package app.mate.ui.home

import app.mate.net.MenuDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealsModelTest {
    private val week = listOf(
        MenuDay("Pâtes", "Soupe"), // lundi
        MenuDay("Riz", "Pizza"), // mardi
        MenuDay("", ""),
        MenuDay("", ""),
        MenuDay("", ""),
        MenuDay("", ""),
        MenuDay("Rôti", "Salade"), // dimanche
    )

    private fun at(weekday: Int, hour: Int, minute: Int = 0) = mealLines(week, weekday, hour * 60 + minute)

    @Test
    fun showsTodayAndTomorrowLunchThenDinner() {
        val lines = at(0, 9)
        assertEquals(listOf("Pâtes", "Soupe", "Riz", "Pizza"), lines.map { it.dish })
        assertEquals(listOf(0, 0, 1, 1), lines.map { it.dayOffset })
        assertEquals(listOf(Meal.LUNCH, Meal.DINNER, Meal.LUNCH, Meal.DINNER), lines.map { it.meal })
    }

    @Test
    fun beforeLunchLunchIsCurrentAndNothingIsPast() {
        assertEquals(listOf(MealState.CURRENT, MealState.LATER, MealState.LATER, MealState.LATER), at(0, 9).map { it.state })
    }

    @Test
    fun lunchStaysCurrentUntilItsEnd() {
        assertEquals(MealState.CURRENT, at(0, 14, 59)[0].state)
        assertEquals(MealState.PAST, at(0, 15)[0].state)
        assertEquals(MealState.CURRENT, at(0, 15)[1].state)
    }

    @Test
    fun afterDinnerTodayIsPastAndTomorrowLunchTakesOver() {
        assertEquals(listOf(MealState.PAST, MealState.PAST, MealState.CURRENT, MealState.LATER), at(0, 22).map { it.state })
    }

    @Test
    fun emptyMealIsNeverCurrentNorHighlighted() {
        // Mercredi : rien de prévu, ni midi ni soir ; demain jeudi aussi.
        val lines = at(2, 9)
        assertTrue(lines.all { it.dish.isEmpty() })
        assertTrue(lines.none { it.state == MealState.CURRENT })
    }

    @Test
    fun currentSkipsAnEmptyMeal() {
        // Lundi sans repas du midi : à 9 h, le soir est le repas en cours.
        val monday = week.toMutableList().also { it[0] = MenuDay("", "Soupe") }
        val lines = mealLines(monday, 0, 9 * 60)
        assertEquals(listOf(MealState.LATER, MealState.CURRENT, MealState.LATER, MealState.LATER), lines.map { it.state })
    }

    @Test
    fun sundayTomorrowIsMonday() {
        val lines = at(6, 9)
        assertEquals(listOf("Rôti", "Salade", "Pâtes", "Soupe"), lines.map { it.dish })
    }
}
