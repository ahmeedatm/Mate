package app.mate.ui.home

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.core.view.isVisible
import app.mate.R
import app.mate.net.BackendClient
import app.mate.net.MenuDay
import app.mate.net.runCatchingCancellable
import app.mate.ui.errorText
import kotlinx.coroutines.launch
import java.util.Calendar

/** Exemple des Repas pour le catalogue : sept déjeuners et sept dîners, du lundi au dimanche. */
class MealsSample(@ArrayRes val lunches: Int, @ArrayRes val dinners: Int) : WidgetSample

/**
 * Le menu de la semaine, réglé sur la page locale de l'ordinateur ; la tablette le relit chaque minute et choisit les
 * repas avec sa propre horloge ([mealLines]) : le repas en cours est en couleur et en gras, ceux qui sont passés sont
 * estompés. La forme posée ([MealsShape]) dit combien en montrer : le repas en cours en Pastille, un par case en Ligne,
 * midi et soir en Colonne, aujourd'hui et demain en Bloc et en Grand, la semaine en plein écran.
 */
class MealsWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val disc = hueDisc(context, R.drawable.ic_tile_restaurant, R.attr.c_hue_orange)
    private val title = cardTitle(R.string.widget_meals)
    private val head = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(disc, LinearLayout.LayoutParams(dp(HUE_DISC_DP), dp(HUE_DISC_DP)))
        addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
    }
    private val status = label(14f, R.attr.c_text_dim)
    private val body = LinearLayout(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(body, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    override val view = LinearLayout(context).asCard(16).apply {
        addView(head, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(content)
    }

    private var week: List<MenuDay>? = null
    private var error: String? = null
    private var shape = MealsShape.of(placed.size)

    /** Ce qui est dessiné (note, forme, repas) : une minute qui ne change rien ne refait pas les vues. */
    private var drawn: List<Any?>? = null

    /** Aperçu du catalogue : son menu reste affiché, sans réseau. */
    private var preview = false

    override fun setActive(active: Boolean) {
        if (active) host.scope.launch { load() }
    }

    override suspend fun load() {
        if (preview) return
        runCatchingCancellable { BackendClient(context).menu.week() }
            .onSuccess { week = it; error = null }
            .onFailure { error = context.errorText(it) }
        render()
    }

    override fun applySize() {
        shape = MealsShape.of(placed.size)
        view.orientation = if (shape.side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        view.gravity = if (shape.side) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        title.isVisible = shape.title
        content.layoutParams = if (shape.side) {
            LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(16) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) }
        }
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        val example = sample as? MealsSample ?: return
        val lunches = context.resources.getStringArray(example.lunches)
        val dinners = context.resources.getStringArray(example.dinners)
        preview = true
        week = List(lunches.size) { MenuDay(lunches[it], dinners.getOrElse(it) { "" }) }
        error = null
        render()
    }

    /** Chaque minute : le repas en cours change avec l'heure, sans repasser par le réseau. */
    override fun onMinute() {
        if (view.isShown) render()
    }

    private fun render() {
        val current = week
        val unset = current != null && current.all { it.lunch.isEmpty() && it.dinner.isEmpty() }
        val note = when {
            error != null -> error
            unset -> context.getString(R.string.meals_unset)
            else -> null
        }
        val now = Calendar.getInstance()
        val weekday = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lundi = 0
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val days = if (current == null || unset || !shape.week) null else weekFrom(current, weekday)
        val lines = if (current == null || unset || shape.week) null else visibleMeals(mealLines(current, weekday, minuteOfDay), shape.meals)
        val next = listOf(note, shape, placed.size, days, lines)
        if (next == drawn) return
        drawn = next
        body.removeAllViews()
        status.text = note
        status.isVisible = note != null
        if (days != null) return renderWeek(days)
        if (lines == null) return
        when {
            // Ligne : un repas par case en plus, côte à côte.
            shape.side -> {
                body.orientation = LinearLayout.HORIZONTAL
                lines.forEach { body.addView(mealCell(it), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(12) }) }
            }
            // Large : aujourd'hui à gauche, demain à droite.
            shape.columns > 1 -> {
                body.orientation = LinearLayout.HORIZONTAL
                lines.groupBy { it.dayOffset }.values.forEach { day ->
                    body.addView(column(day), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(16) })
                }
            }
            else -> {
                body.orientation = LinearLayout.VERTICAL
                lines.forEach { body.addView(mealCell(it), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)) }
            }
        }
    }

    private fun column(lines: List<MealLine>) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        lines.forEach { addView(mealCell(it), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)) }
    }

    /** Un repas : sa légende (« Aujourd'hui, midi »), puis le plat ; en cours en couleur et en gras, passé estompé. */
    private fun mealCell(line: MealLine) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val current = line.state == MealState.CURRENT
        // Un repas passé recule par la couleur « atténuée » du thème (contraste testé), pas par une transparence qui le rendrait illisible.
        val past = line.state == MealState.PAST
        addView(TextView(context).apply {
            setText(caption(line))
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(if (current) R.attr.c_accent else R.attr.c_text_dim))
        })
        addView(TextView(context).apply {
            text = line.dish.ifEmpty { context.getString(R.string.meals_none) }
            textSize = dishSp()
            setTextColor(color(if (line.dish.isEmpty() || past) R.attr.c_text_dim else if (current) R.attr.c_accent else R.attr.c_text))
            if (current) setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    private fun dishSp() = when (placed.size.family) {
        ShapeFamily.PILL, ShapeFamily.ROW -> 17f
        ShapeFamily.LARGE -> 20f
        else -> 18f
    }

    /** Plein écran : la semaine, aujourd'hui d'abord ; une ligne par jour, le midi puis le soir. */
    private fun renderWeek(days: List<WeekDay>) {
        body.orientation = LinearLayout.VERTICAL
        body.addView(weekRow(null, context.getString(R.string.meals_lunch), context.getString(R.string.meals_dinner), header = true))
        val names = context.resources.getStringArray(R.array.meals_weekdays)
        days.forEach { day ->
            val name = when (day.offset) {
                0 -> context.getString(R.string.meals_week_today)
                1 -> context.getString(R.string.meals_week_tomorrow)
                else -> names[day.weekday]
            }
            body.addView(weekRow(day, name, null, header = false), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun weekRow(day: WeekDay?, first: String, second: String?, header: Boolean) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        val today = day?.offset == 0
        fun cell(text: String, empty: Boolean = false) = TextView(context).apply {
            this.text = text
            textSize = if (header) 14f else 18f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(color(if (header || empty) R.attr.c_text_dim else R.attr.c_text))
        }
        addView(label(if (header) 14f else 16f, if (today) R.attr.c_accent else R.attr.c_text_dim).apply {
            text = if (header) "" else first
            if (today) setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(DAY_DP), WRAP_CONTENT))
        val none = context.getString(R.string.meals_none)
        val lunch = if (header) first else day?.day?.lunch.orEmpty()
        val dinner = if (header) second.orEmpty() else day?.day?.dinner.orEmpty()
        addView(cell(lunch.ifEmpty { none }, lunch.isEmpty()), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
        addView(cell(dinner.ifEmpty { none }, dinner.isEmpty()), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun caption(line: MealLine) = when (line.dayOffset to line.meal) {
        0 to Meal.LUNCH -> R.string.meals_today_lunch
        0 to Meal.DINNER -> R.string.meals_today_dinner
        1 to Meal.LUNCH -> R.string.meals_tomorrow_lunch
        else -> R.string.meals_tomorrow_dinner
    }

    private companion object {
        /** Colonne du nom du jour, en plein écran. */
        const val DAY_DP = 140
    }
}
