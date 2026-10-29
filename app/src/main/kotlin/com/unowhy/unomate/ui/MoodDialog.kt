package com.unowhy.unomate.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.unowhy.unomate.R
import com.unowhy.unomate.focus.Moods

/** Proposition d'une humeur ([Moods.planFor]) en une phrase, puis « C'est parti ». Rien n'est gardé. */
object MoodDialog {
    fun propose(context: Context, mood: Moods.Mood, onGo: () -> Unit) {
        val plan = Moods.planFor(mood)
        val pad = context.dp(24)
        val text = TextView(context).apply {
            textSize = 22f
            typeface = themeFont(context, R.attr.c_font_regular)
            setTextColor(themeColor(context, R.attr.c_text))
            setPadding(pad, pad / 3, pad, 0)
            this.text = context.getString(mood.intro) + "\n\n" + describe(context, plan)
        }
        AlertDialog.Builder(context)
            .setTitle(mood.label)
            .setIcon(mood.drawable(context, context.dp(32)))
            .setView(text)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.mood_go) { _, _ -> onGo() }
            .showImmersive()
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
