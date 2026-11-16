package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor

/**
 * Picto d'une ligne façon afficheur RATP / IDFM : forme, rapport et couleurs viennent de [PictoSpec] (les seules
 * couleurs officielles de l'écran). Dessiné à la main, sans bitmap : une vue par passage, à 4 cœurs A35. Une ligne
 * perturbée porte une pastille dans son coin haut droit ([traffic]), détourée de la couleur du fond où le picto est posé
 * (l'écran, ou la carte d'info trafic) ; elle déborde un peu du picto, ses parents ne le rognent pas ([TransportBoard]).
 */
class LinePicto(context: Context) : View(context) {
    private var spec = PictoSpec.of(null, 0, 0)
    private var text = ""
    private var side = 0
    private var traffic: TransportTraffic.Level? = null
    private val box = RectF()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.barlow_semicondensed_600) // controle: permis picto officiel de ligne, identique dans tous les thèmes
    }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ring = 0

    /**
     * [sizeDp] : hauteur du picto ; la largeur suit [PictoSpec.widthRatio]. [traffic] : pastille d'info trafic, ou rien ;
     * [onCard] : posé sur la carte d'info trafic (fond et encre du thème) plutôt que sur l'écran à points.
     */
    fun set(spec: PictoSpec, text: String, sizeDp: Int, traffic: TransportTraffic.Level? = null, onCard: Boolean = false) {
        this.spec = spec
        this.text = text
        this.traffic = traffic
        val ground = themeColor(context, R.attr.c_bg)
        // Écran de Hush translucide : posé sur le fond pour que le détourage soit opaque.
        cut.color = if (onCard) ground else ColorUtils.compositeColors(themeColor(context, R.attr.c_mx_lcd_bg), ground)
        ring = themeColor(context, if (onCard) R.attr.c_text else R.attr.c_mx_lcd_text)
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
        traffic?.let { drawBadge(canvas, it) }
    }

    /**
     * Pastille, centrée sur le coin haut droit (elle en déborde) : pleine et marquée (« ! » perturbé, croix interrompu,
     * marques de [trafficInk]), ou simple anneau à l'encre du fond pour « à venir ».
     */
    private fun drawBadge(canvas: Canvas, level: TransportTraffic.Level) {
        val r = maxOf(height * BADGE_RATIO, context.dp(BADGE_MIN_DP).toFloat())
        val cx = width - r * BADGE_INSET
        val cy = r * BADGE_INSET
        canvas.drawCircle(cx, cy, r + maxOf(1f, r * CUT_RATIO), cut)
        if (level == TransportTraffic.Level.SOON) {
            badge.style = Paint.Style.STROKE
            badge.color = ring
            badge.strokeWidth = r * RING_RATIO
            canvas.drawCircle(cx, cy, r - badge.strokeWidth / 2, badge)
            return
        }
        badge.style = Paint.Style.FILL
        badge.color = trafficColor(context, level)
        canvas.drawCircle(cx, cy, r, badge)
        mark.color = trafficInk(level)
        mark.strokeWidth = r * MARK_RATIO
        val m = r * MARK_SPAN
        if (level == TransportTraffic.Level.INTERRUPTED) {
            canvas.drawLine(cx - m, cy - m, cx + m, cy + m, mark)
            canvas.drawLine(cx - m, cy + m, cx + m, cy - m, mark)
        } else {
            canvas.drawLine(cx, cy - m * 1.3f, cx, cy + m * 0.35f, mark)
            canvas.drawPoint(cx, cy + m * 1.2f, mark)
        }
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
        const val BADGE_RATIO = 0.21f
        const val BADGE_INSET = 0.6f
        const val BADGE_MIN_DP = 7
        const val CUT_RATIO = 0.35f
        const val RING_RATIO = 0.4f
        const val MARK_RATIO = 0.3f
        const val MARK_SPAN = 0.42f
    }
}
