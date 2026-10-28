package com.unowhy.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.unowhy.companion.R

/** Anneau de progression du minuteur Focus : un cercle de fond et un arc, redessinés une fois par seconde. */
class ProgressRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val minStroke = 4 * resources.displayMetrics.density
    private var stroke = minStroke
    private val bounds = RectF()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = themeColor(context, R.attr.c_surface_raised)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = themeColor(context, R.attr.c_accent)
    }

    /** Entre 0 et 1. */
    var progress = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) { field = clamped; invalidate() }
        }

    var color: Int
        get() = arc.color
        set(value) {
            if (value != arc.color) { arc.color = value; invalidate() }
        }

    /** Couleur du cercle de fond (par défaut `c_surface_raised`). */
    var trackColor: Int
        get() = track.color
        set(value) {
            if (value != track.color) { track.color = value; invalidate() }
        }

    /** Trait proportionnel à la taille : l'anneau sert en grand (Focus) et en petit (Accueil). */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        stroke = strokeFor(minOf(w, h).toFloat(), minStroke)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val left = (width - size) / 2 + stroke / 2
        val top = (height - size) / 2 + stroke / 2
        bounds.set(left, top, left + size - stroke, top + size - stroke)
        canvas.drawArc(bounds, 0f, 360f, false, track)
        if (progress > 0f) canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
    }

    companion object {
        /** Épaisseur du trait pour un anneau de `size` pixels (au moins `minStroke`) : de quoi placer un texte dedans. */
        fun strokeFor(size: Float, minStroke: Float): Float = maxOf(minStroke, size / 24f)
    }
}
