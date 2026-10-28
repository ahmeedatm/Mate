package com.unowhy.companion.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.unowhy.companion.R

/**
 * Lumière sur le bord de l'écran quand un agent Claude a besoin de toi : ambre qui pulse tant qu'un agent attend, vert
 * bref quand il a fini, bleu bref à la limite. Aucune carte, rien ne coupe l'écran en cours. Les touchers passent au travers,
 * sauf sur le bord allumé : le toucher ouvre la demande ([onTouched]).
 */
class AgentLight(context: Context) : View(context) {

    enum class Kind { WAITING, DONE, LIMIT }

    var onTouched: (() -> Unit)? = null
    var kind: Kind? = null
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: Animator? = null
    private var color = 0
    private val band = context.dp(BAND_DP).toFloat()
    private val touchBand = context.dp(TOUCH_DP)

    init {
        visibility = GONE
        contentDescription = context.getString(R.string.agents_light_description)
        // Une couche matérielle : la pulsation ne fait que changer l'opacité, sans redessiner les dégradés.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun stopAnimation() {
        val old = animator
        animator = null
        old?.cancel()
    }

    fun show(kind: Kind) {
        stopAnimation()
        this.kind = kind
        color = themeColor(
            context,
            when (kind) {
                Kind.WAITING -> R.attr.c_agent_wait
                Kind.DONE -> R.attr.c_agent_done
                Kind.LIMIT -> R.attr.c_agent_limit
            },
        )
        visibility = VISIBLE
        bringToFront()
        invalidate()
        val reduced = Look.reduced(context)
        animator = when {
            // Attend : pulsation lente, jusqu'à la réponse ou la fin de l'attente.
            kind == Kind.WAITING && !reduced -> ObjectAnimator.ofFloat(this, ALPHA, 0.35f, 1f).apply {
                duration = 1_100
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            // Fini ou limite : apparaît, reste un instant, s'éteint.
            else -> ObjectAnimator.ofFloat(this, ALPHA, 0f, 1f, 1f, 0f).apply {
                duration = if (kind == Kind.LIMIT) 6_000L else 3_000L
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (animator === animation) hide()
                    }
                })
                start()
            }
        }
        if (kind == Kind.WAITING && reduced) alpha = 1f
    }

    fun hide() {
        stopAnimation()
        kind = null
        visibility = GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val strong = ColorUtils.setAlphaComponent(color, 0xCC)
        val none = ColorUtils.setAlphaComponent(color, 0)
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float, left: Float, top: Float, right: Float, bottom: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, strong, none, Shader.TileMode.CLAMP)
            canvas.drawRect(left, top, right, bottom, paint)
        }
        edge(0f, 0f, 0f, band, 0f, 0f, w, band)
        edge(0f, h, 0f, h - band, 0f, h - band, w, h)
        edge(0f, 0f, band, 0f, 0f, 0f, band, h)
        edge(w, 0f, w - band, 0f, w - band, 0f, w, h)
    }

    /** Ne prend que les touchers sur le bord allumé ; ailleurs, l'écran dessous répond comme d'habitude. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || kind == null) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return onEdge(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            onTouched?.invoke()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun onEdge(e: MotionEvent) =
        e.x < touchBand || e.y < touchBand || e.x > width - touchBand || e.y > height - touchBand

    private companion object {
        /** Épaisseur du dégradé lumineux, et du bord qui répond au toucher (plus large : un doigt n'est pas précis). */
        const val BAND_DP = 22
        const val TOUCH_DP = 56
    }
}
