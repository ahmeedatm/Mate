package com.unowhy.unomate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import com.unowhy.unomate.R
import com.unowhy.unomate.ui.Look
import com.unowhy.unomate.ui.dp
import com.unowhy.unomate.ui.themeColor

/**
 * Un widget maintenu, en grand par-dessus l'écran Profils : un second exemplaire du widget, dans sa plus grande taille
 * ([WidgetType.zoomSize]), sur un voile du fond. Fermé par « Fermer », un toucher sur le voile ou Retour ; [onClosed]
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

    init {
        visibility = GONE
        setBackgroundColor(ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_bg), SCRIM_ALPHA))
        isClickable = true
        setOnClickListener { hide() }
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(MARGIN_DP), dp(TOP_DP), dp(MARGIN_DP), dp(BOTTOM_DP))
        })
    }

    /** Widget large (4 colonnes) : toute la largeur ; les autres, une boîte resserrée au milieu, sans grand vide à droite. */
    private fun fitBox(size: WidgetSize) {
        (box.layoutParams as LayoutParams).width = if (size.w >= COLS) LayoutParams.MATCH_PARENT else dp(NARROW_DP)
        box.requestLayout()
    }

    fun show(widget: HomeWidget, onClosed: () -> Unit) {
        hide()
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
