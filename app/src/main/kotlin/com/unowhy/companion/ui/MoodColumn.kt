package com.unowhy.companion.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.unowhy.companion.R
import com.unowhy.companion.focus.Moods

/** Humeur, sur le côté de Focus : un visage par humeur (Fluent Emoji 3D, en grand : lu de loin) ; toucher = [onPick]. */
class MoodColumn @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var onPick: ((Moods.Mood) -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(
            TitleView(context).apply { setText(R.string.mood_title) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(8) },
        )
        val size = context.dp(56)
        for (mood in Moods.Mood.entries) {
            addView(
                Button(context, null, 0, R.style.Chip).apply {
                    setText(mood.label)
                    val face = ContextCompat.getDrawable(context, mood.face)!!.apply { setBounds(0, 0, size, size) }
                    setCompoundDrawables(null, face, null, null)
                    compoundDrawablePadding = context.dp(2)
                    gravity = Gravity.CENTER
                    setPadding(paddingLeft, context.dp(8), paddingRight, context.dp(6))
                    setOnClickListener { onPick?.invoke(mood) }
                },
                LayoutParams(context.dp(108), context.dp(108)).apply { topMargin = context.dp(5); bottomMargin = context.dp(5) },
            )
        }
    }
}
