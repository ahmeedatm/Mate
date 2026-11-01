package app.mate.ui.home

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.MenuDay
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Le menu d'aujourd'hui et de demain, midi et soir. Le menu de la semaine se règle sur la page locale de l'ordinateur ;
 * la tablette le relit chaque minute et choisit les repas avec sa propre horloge ([mealLines]) : le repas en cours est
 * en couleur et en gras, ceux qui sont passés sont estompés.
 */
class MealsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val title = cardTitle(R.string.widget_meals)
    private val status = label(14f, R.attr.c_text_dim)
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    override val view = LinearLayout(context).asCard(16).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(rows, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
    }

    private var week: List<MenuDay>? = null
    private var error: String? = null

    override fun setActive(active: Boolean) {
        if (active) host.scope.launch { load() }
    }

    override suspend fun load() {
        runCatchingCancellable { BackendClient(context).menu.week() }
            .onSuccess { week = it; error = null }
            .onFailure { error = context.errorText(it) }
        render()
    }

    /** Chaque minute : le repas en cours change avec l'heure, sans repasser par le réseau. */
    override fun onMinute() = render()

    private fun render() {
        val current = week
        rows.removeAllViews()
        val note = when {
            error != null -> error
            current != null && current.all { it.lunch.isEmpty() && it.dinner.isEmpty() } -> context.getString(R.string.meals_unset)
            else -> null
        }
        status.text = note
        status.visibility = if (note == null) View.GONE else View.VISIBLE
        if (current == null || note == context.getString(R.string.meals_unset)) return
        val now = Calendar.getInstance()
        val weekday = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lundi = 0
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        mealLines(current, weekday, minuteOfDay).forEach { rows.addView(row(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)) }
    }

    private fun row(line: MealLine) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        alpha = if (line.state == MealState.PAST) PAST_ALPHA else 1f
        val current = line.state == MealState.CURRENT
        addView(
            TextView(context).apply {
                setText(caption(line))
                textSize = 12f
                setTextColor(color(if (current) R.attr.c_accent else R.attr.c_text_dim))
            },
        )
        addView(
            TextView(context).apply {
                text = line.dish.ifEmpty { context.getString(R.string.meals_none) }
                textSize = if (placed.size == WidgetSize.XL) 20f else 16f
                setTextColor(color(if (line.dish.isEmpty()) R.attr.c_text_dim else if (current) R.attr.c_accent else R.attr.c_text))
                if (current) setTypeface(typeface, Typeface.BOLD)
                maxLines = if (placed.size == WidgetSize.L) 1 else 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
    }

    private fun caption(line: MealLine) = when (line.dayOffset to line.meal) {
        0 to Meal.LUNCH -> R.string.meals_today_lunch
        0 to Meal.DINNER -> R.string.meals_today_dinner
        1 to Meal.LUNCH -> R.string.meals_tomorrow_lunch
        else -> R.string.meals_tomorrow_dinner
    }

    private companion object {
        /** Un repas passé reste lisible mais recule. */
        const val PAST_ALPHA = 0.4f
    }
}
