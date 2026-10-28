package com.unowhy.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.SpringAnimation
import com.unowhy.companion.R

/** Calculs du changement d'onglet, sans Android (testés). */
object TabMotion {
    /**
     * Bords gauche et droit de la pastille entre l'onglet quitté et l'onglet choisi : le bord du côté où l'on va suit
     * `lead` (ressort rapide), l'autre `trail` (plus lent). La pastille s'étire puis se resserre sur l'onglet.
     */
    fun edges(fromL: Float, fromR: Float, toL: Float, toR: Float, lead: Float, trail: Float): Pair<Float, Float> {
        val right = toL + toR >= fromL + fromR
        val l = fromL + (toL - fromL) * if (right) trail else lead
        val r = fromR + (toR - fromR) * if (right) lead else trail
        return Pair(l, r)
    }

    /** Côté d'où entre le contenu : 1 de la droite, -1 de la gauche, 0 sans onglet de départ (pas de glissement). */
    fun direction(from: Int, to: Int): Int = if (from < 0 || to < 0 || from == to) 0 else if (to > from) 1 else -1
}

/**
 * Rangée d'onglets du Deck : dessine elle-même le fond de chaque onglet, puis la pastille de l'onglet choisi, qui glisse
 * de l'ancien au nouveau sur deux ressorts ; le texte prend la couleur de l'onglet choisi au même rythme.
 * Les onglets (style `Chip.Tab`) n'ont de fond qu'appuyés.
 * Un onglet soulevé (déplacé par appui long, `elevation` > 0) est dessiné par-dessus les autres.
 */
class TabStrip @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val radius = dp(22).toFloat()
    private val pill = GradientDrawable().apply {
        cornerRadius = radius
        setColor(themeColor(context, R.attr.c_surface_raised))
    }
    private val mark = GradientDrawable().apply {
        cornerRadius = radius
        setColor(themeColor(context, R.attr.c_accent_soft))
        setStroke(dp(1), themeColor(context, R.attr.c_accent))
    }
    private var selected = -1
    /** Vue de l'onglet choisi : après une reconstruction de la rangée, l'ancienne n'y est plus. */
    private var selectedView: View? = null
    private var fromL = 0f
    private var fromR = 0f
    private var lead = 1f
    private var trail = 1f
    private var drawnL = 0f
    private var drawnR = 0f
    private var drawn = false
    private var springs: List<SpringAnimation?> = emptyList()
    private val text = themeColor(context, R.attr.c_text)
    private val accent = themeColor(context, R.attr.c_accent)
    /** Onglets dont le texte change de couleur pendant le glissement (quitté, choisi). */
    private var fading: List<TextView> = emptyList()

    /** Onglet choisi (-1 : aucun). La pastille part de là où elle est dessinée ; au premier affichage, elle y est déjà. */
    fun select(index: Int) {
        if (index == selected) return
        val animate = drawn && selected >= 0 && index >= 0
        val previous = selectedView?.takeIf { indexOfChild(it) >= 0 }
        selected = index
        selectedView = getChildAt(index)
        springs.forEach { it?.cancel() }
        springs = emptyList()
        restoreText()
        if (!animate) {
            lead = 1f
            trail = 1f
            invalidate()
            return
        }
        fromL = drawnL
        fromR = drawnR
        lead = 0f
        trail = 0f
        // Le texte quitté s'éteint avec le bord avant, le choisi s'allume quand la pastille arrive.
        val left = previous as? TextView
        val chosen = selectedView as? TextView
        fading = listOfNotNull(left, chosen)
        left?.setTextColor(blend(1f))
        chosen?.setTextColor(blend(0f))
        var ended = 0
        val settled = { if (++ended == 2) restoreText() }
        springs = listOf(
            Look.spring(context, Look.SPATIAL_FAST, 0f, 1f, { lead = it; left?.setTextColor(blend(1 - it)); invalidate() }, settled),
            Look.spring(context, Look.SPATIAL_DEFAULT, 0f, 1f, { trail = it; chosen?.setTextColor(blend(it)); invalidate() }, settled),
        )
    }

    /** Couleur du texte d'un onglet : `t` = 0 normal, 1 choisi. */
    private fun blend(t: Float) = ColorUtils.blendARGB(text, accent, t.coerceIn(0f, 1f))

    /** Fin du glissement : les onglets reprennent la couleur de leur état (`chip_text`). */
    private fun restoreText() {
        fading.forEach { it.setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text)) }
        fading = emptyList()
    }

    override fun onDetachedFromWindow() {
        springs.forEach { it?.cancel() }
        restoreText()
        lead = 1f
        trail = 1f
        super.onDetachedFromWindow()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val lifted = (0 until childCount).map(::getChildAt).firstOrNull { it.elevation > 0f }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child != lifted && child.visibility == View.VISIBLE) drawAt(canvas, pill, child)
        }
        // Onglet soulevé : par-dessus les autres ; s'il est le choisi, la pastille le suit, donc après lui.
        val chosen = getChildAt(selected)?.takeIf { it.visibility == View.VISIBLE }
        if (lifted != null && lifted == chosen) drawAt(canvas, pill, lifted)
        if (chosen != null) drawMark(canvas, chosen)
        if (lifted != null && lifted != chosen) drawAt(canvas, pill, lifted)
        super.dispatchDraw(canvas)
    }

    private fun drawMark(canvas: Canvas, chosen: View) {
        val (toL, toR) = horizontal(chosen)
        val (l, r) = TabMotion.edges(fromL, fromR, toL, toR, lead, trail)
        val (t, b) = vertical(chosen)
        mark.setBounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        mark.alpha = (chosen.alpha * 255).toInt()
        mark.draw(canvas)
        drawnL = l
        drawnR = r
        drawn = true
    }

    private fun drawAt(canvas: Canvas, shape: GradientDrawable, child: View) {
        val (l, r) = horizontal(child)
        val (t, b) = vertical(child)
        shape.setBounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        shape.alpha = (child.alpha * 255).toInt()
        shape.draw(canvas)
    }

    /** Bords d'un onglet tels qu'on le voit : décalage et agrandissement (appui, déplacement) compris. */
    private fun horizontal(child: View): Pair<Float, Float> {
        val half = child.width * child.scaleX / 2f
        val center = child.left + child.translationX + child.width / 2f
        return Pair(center - half, center + half)
    }

    private fun vertical(child: View): Pair<Float, Float> {
        val half = child.height * child.scaleY / 2f
        val center = child.top + child.translationY + child.height / 2f
        return Pair(center - half, center + half)
    }
}
