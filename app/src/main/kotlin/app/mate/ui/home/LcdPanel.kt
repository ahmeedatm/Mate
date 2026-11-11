package app.mate.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor

/**
 * Écran d'afficheur de quai : fond « écran » du thème (`c_mx_lcd_bg`), coins arrondis, trame de points fixe posée
 * par-dessus les enfants (un petit bitmap répété, rien n'est animé).
 */
class LcdPanel(context: Context) : LinearLayout(context) {
    private val grid = Paint().apply { shader = BitmapShader(tile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val radius = context.dp(RADIUS_DP).toFloat()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(themeColor(context, R.attr.c_mx_lcd_bg))
            cornerRadius = radius
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, grid)
    }

    private fun tile(): Bitmap {
        val side = context.dp(PITCH_DP)
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(Color.BLACK, DOT_ALPHA) }
            Canvas(it).drawCircle(side / 2f, side / 2f, side * DOT_RATIO, dot)
        }
    }

    private companion object {
        const val RADIUS_DP = 13
        const val PITCH_DP = 3
        const val DOT_ALPHA = 0x47
        const val DOT_RATIO = 0.37f
    }
}

/** Texte de l'écran : Departure Mono, capitales, couleur `c_mx_lcd_text` et léger halo de LED. `maxLines = 1`, jamais `isSingleLine` : il remplacerait les capitales. */
fun lcdText(context: Context, sp: Float): TextView = TextView(context).apply {
    val ink = themeColor(context, R.attr.c_mx_lcd_text)
    textSize = sp
    setTextColor(ink)
    typeface = ResourcesCompat.getFont(context, R.font.departure_mono_400)
    isAllCaps = true
    setShadowLayer(context.dp(3).toFloat(), 0f, 0f, ink)
}

/** Trait pointillé de l'écran (séparateur de rangées, ou de passages côte à côte quand [vertical]). */
class DashDivider(context: Context, private val vertical: Boolean) : View(context) {
    private val paint = Paint().apply {
        color = ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_mx_lcd_text), DASH_ALPHA)
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(context.dp(3).toFloat(), context.dp(3).toFloat()), 0f)
    }

    init {
        // Pointillés fiables sur les Android anciens : trait dessiné en logiciel, la vue est minuscule.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        if (vertical) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        else canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
    }

    private companion object {
        const val DASH_ALPHA = 0x80
    }
}

/** Jauge d'approche : piste pâle de l'écran, remplie à [fraction] (0 à 1) de la couleur [fill] de la ligne. */
class ApproachBar(context: Context, fill: Int) : View(context) {
    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_mx_lcd_text), TRACK_ALPHA)
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
        if (fraction > 0f) canvas.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat(), r, r, bar)
    }

    private companion object {
        const val TRACK_ALPHA = 0x40
    }
}
