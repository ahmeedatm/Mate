package com.unowhy.unomate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.ContextThemeWrapper
import android.view.View
import com.unowhy.unomate.R

/**
 * Vignette d'un thème dans Réglages > Personnalisation : un petit écran peint avec ses propres couleurs (fond, barre de
 * gauche, carte avec « Aa » dans sa police de titres, pastille d'accent) et ses tubes s'il en a. Choisi : cadre de
 * l'accent du thème affiché. Dessinée une fois, rien ne bouge.
 */
class ThemePreview(context: Context, theme: AppTheme, private val chosen: Boolean) : View(context) {
    // Depuis le contexte de l'appli, pas celui de l'écran : un thème part de zéro, sans hériter des tubes ou du grain
    // du thème affiché.
    private val themed = ContextThemeWrapper(context.applicationContext, theme.base).apply { this.theme.applyStyle(theme.palette, true) }
    private fun color(attr: Int) = themeColor(themed, attr)

    private val bg = color(R.attr.c_bg)
    private val rail = color(R.attr.c_rail)
    private val surface = color(R.attr.c_surface)
    private val accent = color(R.attr.c_accent)
    private val text = color(R.attr.c_text)
    private val dim = color(R.attr.c_text_dim)
    private val flow = if (themeBool(themed, R.attr.c_flow)) {
        listOf(R.attr.c_flow_dark, R.attr.c_flow_red, R.attr.c_flow_orange, R.attr.c_flow_cream).map(::color)
    } else {
        emptyList()
    }

    /** Cadre du thème choisi : accent du thème affiché (celui des Réglages), pas celui de la vignette. */
    private val frame = themeColor(context, R.attr.c_accent)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
    }
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = themeFont(themed, R.attr.c_font_title)
        textSize = context.dp(22).toFloat()
    }
    private val rect = RectF()
    private val clip = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val pad = 4 * d
        val w = width - 2 * pad
        val h = height - 2 * pad
        val radius = 14 * d
        canvas.save()
        rect.set(pad, pad, pad + w, pad + h)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        fill.color = bg
        canvas.drawRect(rect, fill)
        // Barre de gauche, un peu à part du fond comme dans l'appli.
        fill.color = rail
        canvas.drawRect(pad, pad, pad + w * 0.12f, pad + h, fill)
        fill.color = accent
        canvas.drawRoundRect(pad + w * 0.04f, pad + h * 0.2f, pad + w * 0.08f, pad + h * 0.38f, 2 * d, 2 * d, fill)
        // Tubes à droite : trois bandes de leurs couleurs.
        flow.take(3).forEachIndexed { i, c ->
            fill.color = c
            val band = w * 0.06f
            canvas.drawRect(pad + w * 0.8f + i * band, pad, pad + w * 0.8f + (i + 1) * band, pad + h, fill)
        }
        // Carte : « Aa » en couleur de texte, pastille d'accent, ligne de texte secondaire.
        fill.color = surface
        rect.set(pad + w * 0.18f, pad + h * 0.16f, pad + w * 0.76f, pad + h * 0.84f)
        canvas.drawRoundRect(rect, 10 * d, 10 * d, fill)
        letters.color = text
        canvas.drawText("Aa", rect.left + 10 * d, rect.top + rect.height() * 0.55f, letters)
        fill.color = accent
        canvas.drawCircle(rect.right - 14 * d, rect.top + 14 * d, 6 * d, fill)
        fill.color = dim
        canvas.drawRoundRect(rect.left + 10 * d, rect.bottom - 14 * d, rect.left + rect.width() * 0.6f, rect.bottom - 10 * d, 2 * d, 2 * d, fill)
        canvas.restore()
        if (chosen) {
            stroke.color = frame
            rect.set(pad / 2 + 1.5f * d, pad / 2 + 1.5f * d, width - pad / 2 - 1.5f * d, height - pad / 2 - 1.5f * d)
            canvas.drawRoundRect(rect, radius + pad / 2, radius + pad / 2, stroke)
        }
    }
}
