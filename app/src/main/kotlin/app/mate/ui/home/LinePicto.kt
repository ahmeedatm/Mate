package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.content.res.ResourcesCompat
import app.mate.R
import app.mate.ui.dp

/**
 * Picto d'une ligne façon afficheur RATP / IDFM : forme, rapport et couleurs viennent de [PictoSpec] (les seules
 * couleurs officielles de l'écran). Dessiné à la main, sans bitmap : une vue par passage, à 4 cœurs A35.
 */
class LinePicto(context: Context) : View(context) {
    private var spec = PictoSpec.of(null, 0, 0)
    private var text = ""
    private var side = 0
    private val box = RectF()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.barlow_semicondensed_600) // controle: permis picto officiel de ligne, identique dans tous les thèmes
    }

    /** [sizeDp] : hauteur du picto ; la largeur suit [PictoSpec.widthRatio]. */
    fun set(spec: PictoSpec, text: String, sizeDp: Int) {
        this.spec = spec
        this.text = text
        side = context.dp(sizeDp)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((side * spec.widthRatio).toInt(), side)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val radius = spec.radiusRatio * height
        fill.color = spec.fill
        canvas.drawRoundRect(box, radius, radius, fill)
        if (spec.bars) drawBars(canvas, box, radius)
        letters.color = spec.text
        letters.textSize = height * textScale()
        canvas.drawText(text, box.centerX(), box.centerY() - (letters.ascent() + letters.descent()) / 2, letters)
    }

    /** Tram : barres haut et bas à la couleur de la ligne, rognées aux coins du picto. */
    private fun drawBars(canvas: Canvas, box: RectF, radius: Float) {
        val bar = maxOf(context.dp(2).toFloat(), height / BAR_DIV)
        fill.color = spec.barColor
        canvas.save()
        clip.rewind()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, box.right, bar, fill)
        canvas.drawRect(0f, box.bottom - bar, box.right, box.bottom, fill)
        canvas.restore()
    }

    private fun textScale() = when {
        text.length > 2 -> SMALL
        text.length == 2 -> MEDIUM
        else -> LARGE
    }

    private companion object {
        const val BAR_DIV = 10f
        const val SMALL = 0.42f
        const val MEDIUM = 0.5f
        const val LARGE = 0.62f
    }
}
