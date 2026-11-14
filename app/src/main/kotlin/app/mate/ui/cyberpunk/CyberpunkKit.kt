package app.mate.ui.cyberpunk

import android.animation.AnimatorInflater
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeFont

/**
 * Fond aux coins coupés en biais (haut droit, et bas gauche si `both`), avec une barre d'accent à gauche en option :
 * le cadre de la DA du jeu. Dessiné à la taille de la vue, sans image.
 */
class CutDrawable(
    private val fill: Int,
    private val cut: Float,
    private val both: Boolean = true,
    private val accent: Int = 0,
    private val accentWidth: Float = 0f,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(w - cut, 0f)
        path.lineTo(w, cut)
        path.lineTo(w, h)
        if (both) {
            path.lineTo(cut, h)
            path.lineTo(0f, h - cut)
        } else {
            path.lineTo(0f, h)
        }
        path.close()
    }

    override fun draw(canvas: Canvas) {
        paint.color = fill
        canvas.drawPath(path, paint)
        if (accentWidth > 0f) {
            canvas.save()
            canvas.clipPath(path)
            paint.color = accent
            canvas.drawRect(0f, 0f, accentWidth, bounds.height().toFloat(), paint)
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Barre fine (santé) : fond sombre, remplissage de 0 à 100 %. */
class CyberpunkBar(context: Context, private val fillColor: Int, private val emptyColor: Int) : View(context) {
    private val paint = Paint()
    var percent = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        paint.color = emptyColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = fillColor
        canvas.drawRect(0f, 0f, width * percent / 100f, height.toFloat(), paint)
    }
}

/** Segments en biais d'un attribut (jusqu'à `max`), comme les barres du jeu. */
class CyberpunkSegments(context: Context, private val on: Int, private val off: Int, private val value: Int, private val max: Int = 20) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val segW = dp(7).toFloat()
    private val gap = dp(3).toFloat()
    private val skew = dp(3).toFloat()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(((segW + gap) * max + skew).toInt(), dp(18))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        for (i in 0 until max) {
            val x = i * (segW + gap)
            path.reset()
            path.moveTo(x + skew, 0f)
            path.lineTo(x + skew + segW, 0f)
            path.lineTo(x + segW, h)
            path.lineTo(x, h)
            path.close()
            paint.color = if (i < value) on else off
            canvas.drawPath(path, paint)
        }
    }
}

/**
 * Fabrique de vues de la page Cyberpunk : couleurs `cp_*` (R.color), polices par les rôles `c_font_*` du thème
 * `Fonts.Cyberpunk` (le contexte doit l'avoir), cadres coupés. Tailles en dp : l'écran de la tablette est en mdpi.
 */
class CyberpunkKit(val context: Context) {
    fun color(res: Int) = ContextCompat.getColor(context, res)
    fun dp(value: Int) = context.dp(value)

    val bold: Typeface = themeFont(context, R.attr.c_font_title)
    val medium: Typeface = themeFont(context, R.attr.c_font_medium)
    val regular: Typeface = themeFont(context, R.attr.c_font_regular)
    val mono: Typeface = themeFont(context, R.attr.c_font_display)

    fun panel(fillRes: Int = R.color.cp_panneau, accentRes: Int? = null, accentDp: Int = 0, cutDp: Int = 14, both: Boolean = true) =
        CutDrawable(color(fillRes), dp(cutDp).toFloat(), both, accentRes?.let(::color) ?: 0, dp(accentDp).toFloat())

    fun text(
        text: CharSequence, sizeSp: Float, colorRes: Int = R.color.cp_texte, font: Typeface = regular, spacing: Float = 0f, upper: Boolean = false,
    ) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color(colorRes))
        typeface = font
        letterSpacing = spacing
        isAllCaps = upper
        includeFontPadding = false
    }

    /** Petite étiquette en capitales espacées. */
    fun label(text: CharSequence, colorRes: Int = R.color.cp_etiquette) = text(text, 13f, colorRes, medium, spacing = 0.18f, upper = true)

    /** Bouton plein (rouge ou cyan) ou creux ; au moins 48 dp de haut, il s'enfonce à l'appui. */
    fun button(text: CharSequence, description: CharSequence, fillRes: Int = R.color.cp_rouge, textRes: Int = R.color.cp_sur_rouge, minWidthDp: Int = 0): TextView =
        text(text, 17f, textRes, bold, spacing = 0.08f, upper = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
            minWidth = dp(minWidthDp)
            setPadding(dp(20), 0, dp(20), 0)
            background = panel(fillRes, cutDp = 8, both = false)
            contentDescription = description
            isClickable = true
            isFocusable = true
            pressable()
        }

    fun View.pressable() {
        stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press)
    }

    fun lp(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(58)
    }

    fun divider() = View(context).apply { setBackgroundColor(color(R.color.cp_trait)) }

    fun rarityColor(r: CyberpunkItems.Rarity) = color(
        when (r) {
            CyberpunkItems.Rarity.COMMON -> R.color.cp_rare_commun
            CyberpunkItems.Rarity.UNCOMMON -> R.color.cp_rare_peu
            CyberpunkItems.Rarity.RARE -> R.color.cp_rare_rare
            CyberpunkItems.Rarity.EPIC -> R.color.cp_rare_epique
            CyberpunkItems.Rarity.LEGENDARY -> R.color.cp_rare_legende
        },
    )

    /** Fine barre verticale de la couleur de rareté, en tête d'une ligne. */
    fun rarityBar(colorInt: Int) = View(context).apply { setBackgroundColor(colorInt) }

    companion object {
        val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
