package com.unowhy.companion.ui

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import com.unowhy.companion.R
import com.unowhy.companion.focus.Moods

/** Humeur, sur le côté de Focus : un visage par humeur (visage en grand, lu de loin) ; toucher = [onPick]. */
class MoodColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onPick: ((Moods.Mood) -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(
            TitleView(context).apply { setText(R.string.mood_title) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(8) },
        )
        for (mood in Moods.Mood.entries) {
            val label = "${mood.emoji}\n${context.getString(mood.label)}"
            addView(
                Button(context, null, 0, R.style.Chip).apply {
                    text = SpannableString(label).apply {
                        setSpan(RelativeSizeSpan(2f), 0, mood.emoji.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    gravity = Gravity.CENTER
                    setOnClickListener { onPick?.invoke(mood) }
                },
                LayoutParams(context.dp(108), context.dp(88)).apply { topMargin = context.dp(6); bottomMargin = context.dp(6) },
            )
        }
    }
}
