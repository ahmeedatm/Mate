package app.mate.ui.home

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeColor
import kotlin.math.abs

/**
 * Un widget maintenu, en grand par-dessus l'écran Profils : un second exemplaire du widget, dans sa plus grande taille
 * ([WidgetType.zoomSize]), sur un voile du fond. Fermé par « Fermer », un toucher sur le voile, un double toucher sur
 * le widget hors de ses boutons ([DoubleTap]) ou Retour ; [onClosed]
 * arrête l'exemplaire agrandi.
 */
class WidgetZoom(context: Context) : FrameLayout(context) {
    private var widget: HomeWidget? = null
    private var onClosed: (() -> Unit)? = null

    private val close = Button(context, null, 0, R.style.Chip).apply {
        setText(R.string.widget_zoom_close)
        setCompoundDrawablesRelativeWithIntrinsicBounds(ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()?.apply {
            setTint(themeColor(context, R.attr.c_text))
        }, null, null, null)
        compoundDrawablePadding = dp(8)
        setPadding(dp(16), 0, dp(20), 0)
        setOnClickListener { hide() }
    }

    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // Le voile ferme ; un toucher dans la boîte, hors du widget, ne le traverse pas.
        isClickable = true
        addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { gravity = Gravity.END })
    }

    val isShowing get() = visibility == VISIBLE

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTap = DoubleTap(
        ViewConfiguration.getDoubleTapTimeout().toLong(),
        ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat(),
    )
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var tapTarget: HomeWidget? = null
    /** Fermé par un double toucher : la suite du geste ne va plus à rien. */
    private var ignoreUntilUp = false

    init {
        visibility = GONE
        setBackgroundColor(ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_bg), SCRIM_ALPHA))
        isClickable = true
        setOnClickListener { hide() }
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(MARGIN_DP), dp(TOP_DP), dp(MARGIN_DP), dp(BOTTOM_DP))
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downAt = SystemClock.uptimeMillis()
                ignoreUntilUp = false
                tapTarget = widgetAt(ev.x, ev.y)
                val target = tapTarget
                if (target != null && doubleTap.down(downAt, ev.x, ev.y, target)) {
                    tapTarget = null
                    ignoreUntilUp = true
                    hide()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val moved = abs(ev.x - downX) > slop || abs(ev.y - downY) > slop
                val tapped = tapTarget.takeIf { ev.actionMasked == MotionEvent.ACTION_UP && !ignoreUntilUp }
                doubleTap.up(SystemClock.uptimeMillis(), ev.x, ev.y, tapped, downAt, moved)
                tapTarget = null
                if (ignoreUntilUp) {
                    ignoreUntilUp = false
                    return true
                }
            }
        }
        if (ignoreUntilUp) return true
        return super.dispatchTouchEvent(ev)
    }

    /** Le widget agrandi sous le doigt, touché hors de ses boutons ; null ailleurs (voile, « Fermer », bouton). */
    private fun widgetAt(x: Float, y: Float): HomeWidget? {
        val shown = widget ?: return null
        val view = shown.view
        // Coordonnées de la boîte, où le widget est placé.
        val bx = x - box.left
        val by = y - box.top
        if (bx < view.left || bx >= view.right || by < view.top || by >= view.bottom) return null
        return shown.takeIf { !interactiveAt(view, bx, by) }
    }

    /** Widget large (4 colonnes) : toute la largeur ; les autres, une boîte resserrée au milieu, sans grand vide à droite. */
    private fun fitBox(size: WidgetSize) {
        (box.layoutParams as LayoutParams).width = if (size.w >= COLS) LayoutParams.MATCH_PARENT else dp(NARROW_DP)
        box.requestLayout()
    }

    fun show(widget: HomeWidget, onClosed: () -> Unit) {
        hide()
        doubleTap.reset()
        this.widget = widget
        this.onClosed = onClosed
        fitBox(widget.placed.size)
        box.addView(widget.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        visibility = VISIBLE
        alpha = 0f
        Look.navSpring(this, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
        announceForAccessibility(context.getString(widget.placed.type.title))
    }

    fun hide() {
        val shown = widget ?: return
        widget = null
        box.removeView(shown.view)
        visibility = GONE
        onClosed?.invoke()
        onClosed = null
    }

    /** L'exemplaire agrandi, à rafraîchir avec les autres chaque minute. */
    fun current(): HomeWidget? = widget

    private companion object {
        /** Voile du fond, sur 255 : l'écran se devine derrière sans gêner la lecture. */
        const val SCRIM_ALPHA = 0xEB
        const val MARGIN_DP = 40
        const val TOP_DP = 20
        const val BOTTOM_DP = 32
        /** Largeur d'un widget de 1 ou 2 colonnes agrandi (la place fait ~1100 dp sur la tablette). */
        const val NARROW_DP = 760
    }
}
