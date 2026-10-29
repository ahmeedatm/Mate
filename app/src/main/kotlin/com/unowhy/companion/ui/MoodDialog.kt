package com.unowhy.companion.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.unowhy.companion.R
import com.unowhy.companion.focus.Moods

/**
 * « Comment ça va ? » : six humeurs en grand, puis la proposition qui va avec ([Moods.planFor]) et « C'est parti ».
 * Rien n'est gardé : l'humeur ne sert qu'à choisir la proposition.
 */
object MoodDialog {
    fun show(context: Context, onGo: (Moods.Plan) -> Unit) {
        val pad = context.dp(24)
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 3, pad, 0) }
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val proposal = TextView(context).apply {
            textSize = 22f
            typeface = themeFont(context, R.attr.c_font_regular)
            setTextColor(themeColor(context, R.attr.c_text))
            visibility = View.GONE
        }
        box.addView(grid)
        box.addView(proposal)
        var plan: Moods.Plan? = null
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.mood_title)
            .setView(box)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.mood_other, null)
            .setPositiveButton(R.string.mood_go) { _, _ -> plan?.let(onGo) }
            .create()

        fun pick(mood: Moods.Mood?) {
            plan = mood?.let { Moods.planFor(it) }
            grid.visibility = if (mood == null) View.VISIBLE else View.GONE
            proposal.visibility = if (mood == null) View.GONE else View.VISIBLE
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility = proposal.visibility
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = proposal.visibility
            if (mood != null) proposal.text = context.getString(mood.intro) + "\n\n" + describe(context, plan!!)
        }

        Moods.Mood.entries.chunked(3).forEach { row ->
            val line = LinearLayout(context)
            row.forEach { mood ->
                val button = Button(context, null, 0, R.style.Chip).apply {
                    // Visage deux fois plus grand que le nom : lu de loin.
                    text = SpannableString(context.getString(R.string.mood_choice, mood.emoji, context.getString(mood.label))).apply {
                        setSpan(RelativeSizeSpan(2f), 0, mood.emoji.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setOnClickListener { pick(mood) }
                }
                line.addView(button, LinearLayout.LayoutParams(0, context.dp(112), 1f).apply { setMargins(context.dp(6), context.dp(6), context.dp(6), context.dp(6)) })
            }
            grid.addView(line)
        }
        dialog.setOnShowListener {
            // « Autre humeur » revient au choix sans fermer la boîte.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pick(null) }
            pick(null)
        }
        dialog.showImmersive()
    }

    private fun describe(context: Context, plan: Moods.Plan): String {
        val ambiance = context.getString(plan.ambiance.label)
        return when (plan) {
            is Moods.Plan.Breathe -> context.getString(R.string.mood_plan_breathe, context.getString(plan.pattern.label), plan.minutes, ambiance)
            is Moods.Plan.Work -> context.getString(R.string.mood_plan_work, plan.preset.focus, plan.preset.pause, ambiance)
        }
    }

    @get:StringRes
    private val Moods.Mood.label: Int
        get() = when (this) {
            Moods.Mood.STRESSED -> R.string.mood_stressed
            Moods.Mood.ANXIOUS -> R.string.mood_anxious
            Moods.Mood.TIRED -> R.string.mood_tired
            Moods.Mood.SCATTERED -> R.string.mood_scattered
            Moods.Mood.FINE -> R.string.mood_fine
            Moods.Mood.MOTIVATED -> R.string.mood_motivated
        }

    @get:StringRes
    private val Moods.Mood.intro: Int
        get() = when (this) {
            Moods.Mood.STRESSED -> R.string.mood_stressed_intro
            Moods.Mood.ANXIOUS -> R.string.mood_anxious_intro
            Moods.Mood.TIRED -> R.string.mood_tired_intro
            Moods.Mood.SCATTERED -> R.string.mood_scattered_intro
            Moods.Mood.FINE -> R.string.mood_fine_intro
            Moods.Mood.MOTIVATED -> R.string.mood_motivated_intro
        }
}
