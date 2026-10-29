package com.unowhy.companion.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.unowhy.companion.Computers
import com.unowhy.companion.R
import com.unowhy.companion.audio.Crunch

/**
 * Bande du bas de la vue « Ouvertes » : les instructions au repos, une grande corbeille pendant un glisser.
 * Contre les accidents, lâcher dessus ne suffit pas : il faut y garder la fenêtre [ARM_MS] (la corbeille se remplit
 * de rouge, puis tremble, vibre et dit « Lâche pour fermer ») ; ressortir avant remet tout à zéro, et un lâcher trop
 * tôt ne fait rien. Fenêtre avalée : petit croc (son généré, [Crunch]) et « Miam ! ».
 * Toujours VISIBLE (seule l'opacité change) : Android ne prévient pas une vue masquée qu'un glisser commence.
 */
class TrashZone(context: Context, private val onDrop: (payload: Any?) -> Unit) : FrameLayout(context) {

    private val hint = TextView(context)
    private val bin = LinearLayout(context)
    private val icon = ImageView(context)
    private val label = TextView(context)
    private val fill = ClipDrawable(rounded(R.attr.c_ko_soft), Gravity.START, ClipDrawable.HORIZONTAL)
    private val frame = rounded(R.attr.c_surface)
    private var armed = false
    private var wobble: Animator? = null
    /** Retour aux instructions après un glisser ; annulé si un autre glisser commence avant. */
    private val backToHint = Runnable { show(dragging = false) }
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
        hint.apply {
            setText(if (Computers.active(context)?.os == "mac") R.string.deck_open_hint_mac else R.string.deck_open_hint)
            textSize = 15f
            setTextColor(color(R.attr.c_text_dim))
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
        }
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        bin.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = LayerDrawable(arrayOf(frame, fill))
            alpha = 0f
        }
        icon.setImageResource(R.drawable.ic_delete)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        bin.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        label.textSize = 20f
        label.typeface = Typeface.DEFAULT_BOLD
        bin.addView(label)
        addView(bin, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        reset()
        setOnDragListener { _, event -> onDrag(event) }
    }

    private fun onDrag(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> { removeCallbacks(backToHint); reset(); show(dragging = true) }
            DragEvent.ACTION_DRAG_ENTERED -> {
                label.setText(R.string.trash_hold)
                tint(R.attr.c_ko)
                icon.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
                arming.start()
            }
            DragEvent.ACTION_DRAG_EXITED -> reset()
            DragEvent.ACTION_DROP -> {
                if (armed) swallow(event.localState) else reset()
            }
            // Après « Miam ! », on laisse voir la corbeille un instant avant de remettre les instructions.
            // (Toujours : un glisser annulé pendant que la corbeille tremble la laissait affichée.)
            DragEvent.ACTION_DRAG_ENDED -> postDelayed(backToHint, if (label.text == context.getString(R.string.trash_done)) DONE_MS else 0)
        }
        return true
    }

    private fun arm() {
        armed = true
        label.setText(R.string.trash_release)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Prête à avaler : la corbeille tremble (pas avec Réduire les animations : le texte suffit).
        if (Look.still(context)) return
        wobble = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, -12f, 12f, -8f, 8f, 0f).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun swallow(payload: Any?) {
        stopWobble()
        armed = false
        label.setText(R.string.trash_done)
        Crunch.play(context)
        icon.animate().scaleX(1.5f).scaleY(1.5f).setDuration(110).withEndAction {
            icon.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
        }.start()
        onDrop(payload)
    }

    private fun show(dragging: Boolean) {
        hint.animate().alpha(if (dragging) 0f else 1f).setDuration(FADE_MS).start()
        bin.animate().alpha(if (dragging) 1f else 0f).setDuration(FADE_MS).start()
        if (!dragging) reset()
    }

    private fun reset() {
        arming.cancel()
        stopWobble()
        armed = false
        fill.level = 0
        icon.animate().cancel()
        icon.scaleX = 1f
        icon.scaleY = 1f
        label.setText(R.string.trash_idle)
        tint(R.attr.c_text_dim)
    }

    private fun stopWobble() {
        wobble?.cancel()
        wobble = null
        icon.rotation = 0f
    }

    private fun tint(colorRes: Int) {
        val c = color(colorRes)
        icon.imageTintList = ColorStateList.valueOf(c)
        label.setTextColor(c)
        frame.setStroke(dp(2), c, dp(10).toFloat(), dp(6).toFloat())
    }

    private fun rounded(colorRes: Int) = GradientDrawable().apply {
        setColor(color(colorRes))
        cornerRadius = dp(16).toFloat()
    }

    private fun color(res: Int) = themeColor(context, res)

    private companion object {
        /** Temps à garder la fenêtre sur la corbeille avant que le lâcher la ferme. */
        const val ARM_MS = 900L
        const val MAX_LEVEL = 10_000
        const val FADE_MS = 150L
        const val DONE_MS = 700L
    }
}
