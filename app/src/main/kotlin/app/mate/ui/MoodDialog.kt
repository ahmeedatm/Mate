package app.mate.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.focus.Moods

/** Proposition d'une humeur ([Moods.planFor]) en une phrase, puis « C'est parti ». Rien n'est gardé. */
object MoodDialog {
    fun propose(context: Context, mood: Moods.Mood, onGo: () -> Unit) {
        val plan = Moods.planFor(mood)
        // Le programme dans une ligne comme celles de la boîte Profils ; l'intention en phrase d'aide sous le titre.
        val text = TextView(context).apply {
            textSize = 20f
            typeface = themeFont(context, R.attr.c_font_regular)
            setTextColor(themeColor(context, R.attr.c_text))
            setBackgroundResource(R.drawable.row_bg)
            setPadding(context.dp(20), context.dp(16), context.dp(20), context.dp(16))
            this.text = describe(context, plan)
        }
        showConsoleDialog(
            context,
            title = context.getString(mood.label),
            subtitle = context.getString(mood.intro),
            icon = Look.badge(context, mood.icon, themeColor(context, mood.hue), sizeDp = 48, iconDp = 26),
            body = text,
            primary = R.string.mood_go,
            widthDp = 560,
        ) { onGo() }
    }

    private fun describe(context: Context, plan: Moods.Plan): String {
        val ambiance = context.getString(plan.ambiance.label)
        return when (plan) {
            is Moods.Plan.Breathe -> context.getString(R.string.mood_plan_breathe, context.getString(plan.pattern.label), plan.minutes, ambiance)
            is Moods.Plan.Work -> context.getString(R.string.mood_plan_work, plan.preset.focus, plan.preset.pause, ambiance)
        }
    }

    @get:StringRes
    private val Moods.Mood.intro: Int
        get() = when (this) {
            Moods.Mood.STRESSED -> R.string.mood_stressed_intro
            Moods.Mood.TIRED -> R.string.mood_tired_intro
            Moods.Mood.SCATTERED -> R.string.mood_scattered_intro
            Moods.Mood.MOTIVATED -> R.string.mood_motivated_intro
        }
}

/** Icône d'une humeur (Material Symbols Rounded, `ic_mood_*`). */
@get:DrawableRes
val Moods.Mood.icon: Int
    get() = when (this) {
        Moods.Mood.STRESSED -> R.drawable.ic_mood_stressed
        Moods.Mood.TIRED -> R.drawable.ic_mood_tired
        Moods.Mood.SCATTERED -> R.drawable.ic_mood_scattered
        Moods.Mood.MOTIVATED -> R.drawable.ic_mood_motivated
    }

/** Teinte d'une humeur (`c_hue_*`), toujours avec son nom : la couleur n'est jamais seule. */
@get:AttrRes
val Moods.Mood.hue: Int
    get() = when (this) {
        Moods.Mood.STRESSED -> R.attr.c_hue_orange
        Moods.Mood.TIRED -> R.attr.c_hue_violet
        Moods.Mood.SCATTERED -> R.attr.c_hue_cyan
        Moods.Mood.MOTIVATED -> R.attr.c_hue_red
    }

/** Icône teinte de l'humeur, à la taille demandée. */
fun Moods.Mood.drawable(context: Context, size: Int): Drawable =
    ContextCompat.getDrawable(context, icon)!!.mutate().apply {
        setTint(themeColor(context, hue))
        setBounds(0, 0, size, size)
    }

/** Nom d'une humeur (colonne de Focus, proposition). */
@get:StringRes
val Moods.Mood.label: Int
    get() = when (this) {
        Moods.Mood.STRESSED -> R.string.mood_stressed
        Moods.Mood.TIRED -> R.string.mood_tired
        Moods.Mood.SCATTERED -> R.string.mood_scattered
        Moods.Mood.MOTIVATED -> R.string.mood_motivated
    }
