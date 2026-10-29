package com.unowhy.companion.ui

import android.content.Context
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.R
import com.unowhy.companion.focus.Moods

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
            .setIcon(mood.face)
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

/** Visage d'une humeur : Fluent Emoji 3D de Microsoft (licence MIT, `res/drawable-nodpi/mood_*.png`, 144 px). */
@get:DrawableRes
val Moods.Mood.face: Int
    get() = when (this) {
        Moods.Mood.STRESSED -> R.drawable.mood_stressed
        Moods.Mood.TIRED -> R.drawable.mood_tired
        Moods.Mood.SCATTERED -> R.drawable.mood_scattered
        Moods.Mood.MOTIVATED -> R.drawable.mood_motivated
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
