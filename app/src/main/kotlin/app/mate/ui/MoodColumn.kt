package app.mate.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import app.mate.R
import app.mate.focus.Moods

/**
 * Humeur, sur le côté de Focus : une icône par humeur (Material Symbols, teinte de l'humeur, en grand) ; toucher = [onPick].
 * Thèmes à tubes : boutons et titre sur le fond translucide des widgets de l'Accueil, textes détourés (les tubes passent derrière).
 */
class MoodColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onPick: ((Moods.Mood) -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(
            TitleView(context).apply {
                setText(R.string.mood_title)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(8) },
        )
        val size = context.dp(44)
        for (mood in Moods.Mood.entries) {
            addView(
                Button(context, null, 0, R.style.Chip).apply {
                    setText(mood.label)
                    setCompoundDrawables(null, mood.drawable(context, size), null, null)
                    compoundDrawablePadding = context.dp(2)
                    gravity = Gravity.CENTER
                    setPadding(paddingLeft, context.dp(8), paddingRight, context.dp(6))
                    setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))
                    setOnClickListener { onPick?.invoke(mood) }
                },
                LayoutParams(context.dp(108), context.dp(108)).apply { topMargin = context.dp(5); bottomMargin = context.dp(5) },
            )
        }
    }
}
