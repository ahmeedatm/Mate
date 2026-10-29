package com.unowhy.unomate.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.Button
import com.unowhy.unomate.R

/**
 * Bouton qu'il faut maintenir appuyé (1 s) pour valider : un remplissage le montre, lâcher avant la fin annule. Le clic
 * (`setOnClickListener`) n'arrive qu'au bout du maintien. Pour ce qui ne se rattrape pas (autoriser une commande à
 * risque) : un toucher distrait ne suffit pas.
 */
// Comme les autres boutons de l'appli : le style (PrimaryButton) se donne au constructeur, AppCompatButton n'en prend pas.
@SuppressLint("AppCompatCustomView")
class HoldButton(context: Context) : Button(context, null, 0, R.style.PrimaryButton) {

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_on_primary); alpha = FILL_ALPHA }
    private val rect = RectF()
    private val radius = context.dp(14).toFloat()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = HOLD_MS
                    addUpdateListener {
                        progress = it.animatedValue as Float
                        invalidate()
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (cancelled) return
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            reset()
                            performClick()
                        }
                    })
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animator?.cancel()
                reset()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun reset() {
        progress = 0f
        isPressed = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return
        rect.set(0f, 0f, width * progress, height.toFloat())
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        canvas.restore()
    }

    private companion object {
        const val HOLD_MS = 1_000L
        const val FILL_ALPHA = 70
    }
}
