package app.mate.ui.home

import app.mate.net.MenuApi
import app.mate.net.MenuDay

enum class Meal { LUNCH, DINNER }

/** `CURRENT` : le repas en cours (ou le prochain à venir) ; `PAST` : fini, estompé ; `LATER` : à venir, après le repas en cours. */
enum class MealState { PAST, CURRENT, LATER }

/** `dayOffset` : 0 aujourd'hui, 1 demain ; `dish` vide quand rien n'est prévu. */
data class MealLine(val dayOffset: Int, val meal: Meal, val dish: String, val state: MealState)

/** Le déjeuner compte comme en cours jusqu'à 15 h, le dîner jusqu'à 22 h (en minutes depuis minuit). */
private const val LUNCH_END = 15 * 60
private const val DINNER_END = 22 * 60

/**
 * Les quatre repas à montrer : midi et soir d'aujourd'hui, puis de demain. `weekday` : 0 lundi … 6 dimanche ;
 * `minuteOfDay` : l'heure de la tablette. Le repas en cours est le premier qui n'est pas fini et qui a un plat : un repas
 * vide n'est jamais mis en avant. Passé le dîner, ceux d'aujourd'hui sont estompés et le déjeuner de demain prend le relais.
 */
fun mealLines(week: List<MenuDay>, weekday: Int, minuteOfDay: Int): List<MealLine> {
    val today = week.getOrElse(weekday) { MenuDay("", "") }
    val tomorrow = week.getOrElse((weekday + 1) % MenuApi.DAYS) { MenuDay("", "") }
    val meals = listOf(
        Triple(0, Meal.LUNCH, today.lunch) to (minuteOfDay >= LUNCH_END),
        Triple(0, Meal.DINNER, today.dinner) to (minuteOfDay >= DINNER_END),
        Triple(1, Meal.LUNCH, tomorrow.lunch) to false,
        Triple(1, Meal.DINNER, tomorrow.dinner) to false,
    )
    val current = meals.indexOfFirst { (meal, over) -> !over && meal.third.isNotEmpty() }
    return meals.mapIndexed { i, (meal, over) ->
        val state = when {
            over -> MealState.PAST
            i == current -> MealState.CURRENT
            else -> MealState.LATER
        }
        MealLine(meal.first, meal.second, meal.third, state)
    }
}

/**
 * Mise en page des Repas selon la forme posée (#181). `meals` : combien de repas montrer ; `side` : l'icône à côté
 * (Pastille, Ligne, un repas par case en plus), sinon au-dessus ; `title` : « Repas du jour » à côté de l'icône ;
 * `columns` : aujourd'hui et demain côte à côte ; `week` : la semaine entière (plein écran).
 */
data class MealsShape(val meals: Int, val side: Boolean, val title: Boolean, val columns: Int = 1, val week: Boolean = false) {
    companion object {
        private const val ALL = 4

        fun of(size: WidgetSize): MealsShape = when (size.family) {
            ShapeFamily.PILL -> MealsShape(meals = 1, side = true, title = false)
            ShapeFamily.ROW -> MealsShape(meals = size.w - 1, side = true, title = false)
            ShapeFamily.COLUMN -> MealsShape(meals = if (size.h >= 3) ALL else 2, side = false, title = false)
            ShapeFamily.BLOCK, ShapeFamily.LARGE -> MealsShape(meals = ALL, side = false, title = true, columns = if (size.w >= 3) 2 else 1)
            ShapeFamily.FULL -> MealsShape(meals = ALL, side = false, title = true, week = true)
        }
    }
}

/**
 * Les `count` repas à montrer : tous s'il y a la place, sinon à partir du repas en cours (un repas vide qui le précède
 * ne passe pas devant lui) ; sans repas en cours, à partir du premier qui n'est pas passé.
 */
fun visibleMeals(lines: List<MealLine>, count: Int): List<MealLine> {
    if (count >= lines.size) return lines
    val start = lines.indexOfFirst { it.state == MealState.CURRENT }.takeIf { it >= 0 } ?: lines.indexOfFirst { it.state != MealState.PAST }
    return lines.drop(start.coerceAtLeast(0)).take(count)
}

/** Un jour de la semaine affichée : `offset` 0 aujourd'hui, `weekday` 0 lundi … 6 dimanche. */
data class WeekDay(val offset: Int, val weekday: Int, val day: MenuDay)

/** La semaine en commençant par aujourd'hui (`weekday` : 0 lundi … 6 dimanche). */
fun weekFrom(week: List<MenuDay>, weekday: Int): List<WeekDay> = List(MenuApi.DAYS) { offset ->
    val day = (weekday + offset) % MenuApi.DAYS
    WeekDay(offset, day, week.getOrElse(day) { MenuDay("", "") })
}
