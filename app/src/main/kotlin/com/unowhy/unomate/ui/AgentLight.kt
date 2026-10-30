package com.unowhy.unomate.ui

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
import com.unowhy.unomate.R

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
        buildEdges()
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

    /** Dégradés des quatre bords, refaits quand la taille ou la couleur change (pas d'allocation dans onDraw). */
    private val edges = Array(4) { Paint(Paint.ANTI_ALIAS_FLAG) }

    private fun buildEdges() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val strong = ColorUtils.setAlphaComponent(color, 0xCC)
        val none = ColorUtils.setAlphaComponent(color, 0)
        edges[0].shader = LinearGradient(0f, 0f, 0f, band, strong, none, Shader.TileMode.CLAMP)
        edges[1].shader = LinearGradient(0f, h, 0f, h - band, strong, none, Shader.TileMode.CLAMP)
        edges[2].shader = LinearGradient(0f, 0f, band, 0f, strong, none, Shader.TileMode.CLAMP)
        edges[3].shader = LinearGradient(w, 0f, w - band, 0f, strong, none, Shader.TileMode.CLAMP)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buildEdges()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, band, edges[0])
        canvas.drawRect(0f, h - band, w, h, edges[1])
        canvas.drawRect(0f, 0f, band, h, edges[2])
        canvas.drawRect(w - band, 0f, w, h, edges[3])
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
