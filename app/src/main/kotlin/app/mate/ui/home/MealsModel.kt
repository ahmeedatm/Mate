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
