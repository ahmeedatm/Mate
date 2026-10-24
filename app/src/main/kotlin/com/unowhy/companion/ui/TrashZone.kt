package com.unowhy.companion.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.unowhy.companion.R

/**
 * Corbeille d'un glisser-déposer (vue « Ouvertes ») : visible seulement pendant un glisser. Contre les accidents,
 * lâcher dessus ne suffit pas : il faut y garder la fenêtre [ARM_MS] (la corbeille se remplit de rouge, puis vibre
 * et dit « Lâche pour fermer ») ; ressortir avant remet tout à zéro, et un lâcher trop tôt ne fait rien.
 */
class TrashZone(context: Context, private val onDrop: (payload: Any?) -> Unit) : LinearLayout(context) {

    private val icon = ImageView(context)
    private val label = TextView(context)
    private val fill = ClipDrawable(rounded(R.color.ko_soft), Gravity.START, ClipDrawable.HORIZONTAL)
    private val frame = rounded(R.color.surface_raised)
    private var armed = false
    private val arming = ValueAnimator.ofInt(0, MAX_LEVEL).apply {
        duration = ARM_MS
        addUpdateListener { fill.level = it.animatedValue as Int }
        addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationStart(animation: Animator) { cancelled = false }
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) { if (!cancelled) arm() }
        })
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(20), 0, dp(24), 0)
        minimumWidth = dp(300)
        background = LayerDrawable(arrayOf(frame, fill))
        icon.setImageResource(R.drawable.ic_delete)
        addView(icon, LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        label.textSize = 16f
        addView(label)
        visibility = GONE
        reset()
        setOnDragListener { _, event -> onDrag(event) }
    }

    private fun onDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> { reset(); visibility = VISIBLE }
            DragEvent.ACTION_DRAG_ENTERED -> {
                label.setText(R.string.trash_hold)
                tint(R.color.ko)
                arming.start()
            }
            DragEvent.ACTION_DRAG_EXITED -> reset()
            DragEvent.ACTION_DROP -> {
                val confirmed = armed
                reset()
                if (confirmed) onDrop(event.localState)
            }
            DragEvent.ACTION_DRAG_ENDED -> { reset(); visibility = GONE }
        }
        return true
    }

    private fun arm() {
        armed = true
        label.setText(R.string.trash_release)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun reset() {
        arming.cancel()
        armed = false
        fill.level = 0
        label.setText(R.string.trash_idle)
        tint(R.color.text_dim)
    }

    private fun tint(colorRes: Int) {
        val c = ContextCompat.getColor(context, colorRes)
        icon.imageTintList = ColorStateList.valueOf(c)
        label.setTextColor(c)
        frame.setStroke(dp(2), c)
    }

    private fun rounded(colorRes: Int) = GradientDrawable().apply {
        setColor(ContextCompat.getColor(context, colorRes))
        cornerRadius = dp(32).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Temps à garder la fenêtre sur la corbeille avant que le lâcher la ferme. */
        const val ARM_MS = 900L
        const val MAX_LEVEL = 10_000
    }
}
