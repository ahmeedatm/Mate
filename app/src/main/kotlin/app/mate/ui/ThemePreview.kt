package app.mate.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.ContextThemeWrapper
import android.view.View
import androidx.core.graphics.ColorUtils
import app.mate.R

/**
 * Vignette d'un thème dans Réglages > Personnalisation : un petit écran peint avec ses propres couleurs (fond, barre de
 * gauche, carte avec « Aa » dans sa police de titres, pastille d'accent). Choisi : cadre de l'accent du thème
 * affiché. Thème à fond animé (Hush, c_backdrop) : un reflet violet et un rose sous la carte en verre. Dessinée une
 * fois, rien ne bouge.
 */
class ThemePreview(context: Context, theme: AppTheme, private val chosen: Boolean) : View(context) {
    // Depuis le contexte de l'appli, pas celui de l'écran : un thème part de zéro, sans hériter du thème affiché.
    private val themed = ContextThemeWrapper(context.applicationContext, theme.base).apply { theme.palette?.let { this.theme.applyStyle(it, true) } }
    private fun color(attr: Int) = themeColor(themed, attr)

    private val bg = color(R.attr.c_bg)
    private val rail = color(R.attr.c_rail)
    private val surface = color(R.attr.c_surface)
    private val accent = color(R.attr.c_accent)
    private val text = color(R.attr.c_text)
    private val dim = color(R.attr.c_text_dim)
    private val backdrop = themeBool(themed, R.attr.c_backdrop)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow2 = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Cadre du thème choisi : accent du thème affiché (celui des Réglages), pas celui de la vignette. */
    private val frame = themeColor(context, R.attr.c_accent)
    private val outline = themeColor(context, R.attr.c_stroke)

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!backdrop) return
        fun halo(color: Int, x: Float, y: Float) = RadialGradient(
            x, y, w * 0.6f, ColorUtils.setAlphaComponent(color, 0x60), ColorUtils.setAlphaComponent(color, 0), Shader.TileMode.CLAMP,
        )
        glow.shader = halo(color(R.attr.c_hue_violet), w * 0.15f, h * 0.95f)
        glow2.shader = halo(accent, w * 0.9f, h * 0.05f)
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
        if (backdrop) {
            canvas.drawRect(rect, glow)
            canvas.drawRect(rect, glow2)
        }
        // Barre de gauche, un peu à part du fond comme dans l'appli.
        fill.color = rail
        canvas.drawRect(pad, pad, pad + w * 0.12f, pad + h, fill)
        fill.color = accent
        canvas.drawRoundRect(pad + w * 0.04f, pad + h * 0.2f, pad + w * 0.08f, pad + h * 0.38f, 2 * d, 2 * d, fill)
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
        if (!chosen) {
            // Filet fin : une vignette claire (Contraste élevé) ne se perd pas sur une carte blanche.
            stroke.color = outline
            stroke.strokeWidth = d
            rect.set(pad, pad, pad + w, pad + h)
            canvas.drawRoundRect(rect, radius, radius, stroke)
            stroke.strokeWidth = 3 * d
        }
        if (chosen) {
            stroke.color = frame
            rect.set(pad / 2 + 1.5f * d, pad / 2 + 1.5f * d, width - pad / 2 - 1.5f * d, height - pad / 2 - 1.5f * d)
            canvas.drawRoundRect(rect, radius + pad / 2, radius + pad / 2, stroke)
        }
    }
}
