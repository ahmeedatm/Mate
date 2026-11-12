package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Jauge verticale de luminosité du panneau d'une lumière (maquette A « Tiroir ») : une colonne arrondie, remplie depuis
 * le bas dans la couleur de la lampe, le pourcentage écrit en bas. Glisser n'importe où règle la valeur (1 à 100) ;
 * [onChange] reçoit chaque pas, puis `final` au lâcher. Dessinée à la main : une seule vue, rien à gonfler.
 */
class LightFader(context: Context) : View(context) {

    var onChange: ((value: Int, final: Boolean) -> Unit)? = null

    /** Nom de la lumière, pour la lecture d'écran. */
    var label: String = ""
        set(v) {
            field = v
            describe()
        }

    var value: Int = 0
        set(v) {
            field = v.coerceIn(0, MAX)
            describe()
            invalidate()
        }

    /** Couleur du remplissage : celle de la lampe, ou l'accent si elle n'en a pas. */
    var fill: Int = themeColor(context, R.attr.c_accent)
        set(v) {
            field = v
            invalidate()
        }

    private val radius = dp(RADIUS_DP).toFloat()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(context, R.attr.c_surface_raised) }
    private val filled = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(TEXT_DP).toFloat()
        typeface = themeFont(context, R.attr.c_font_medium)
    }
    private val inkDark = themeColor(context, R.attr.c_text)
    private val inkLight = themeColor(context, R.attr.c_surface)
    private val box = RectF()
    private val clip = Path()

    init {
        isFocusable = true
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.ha_light_brighter)) { _, _ -> step(STEP) }
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.ha_light_dimmer)) { _, _ -> step(-STEP) }
    }

    private fun step(delta: Int): Boolean {
        value = (value + delta).coerceIn(1, MAX)
        onChange?.invoke(value, true)
        return true
    }

    private fun describe() {
        contentDescription = context.getString(R.string.ha_light_brightness, label, value)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(box, track)
        val top = height * (1f - value / MAX.toFloat())
        filled.color = fill
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), filled)
        canvas.restore()
        val baseline = height - dp(TEXT_BOTTOM_DP).toFloat()
        // Le chiffre est posé sur le remplissage dès qu'il le couvre, sinon sur la piste vide.
        val under = if (top < baseline - text.textSize) fill else track.color
        text.color = readableInk(under, inkDark, inkLight)
        canvas.drawText(context.getString(R.string.ha_percent, value), width / 2f, baseline, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val at = ((1f - event.y / height.coerceAtLeast(1)) * MAX).toInt().coerceIn(1, MAX)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = at
                onChange?.invoke(value, false)
            }
            MotionEvent.ACTION_MOVE -> if (at != value) {
                value = at
                onChange?.invoke(value, false)
            }
            MotionEvent.ACTION_UP -> {
                value = at
                onChange?.invoke(value, true)
            }
            MotionEvent.ACTION_CANCEL -> onChange?.invoke(value, true)
        }
        return true
    }

    private companion object {
        const val MAX = 100
        const val STEP = 10
        const val RADIUS_DP = 32
        const val TEXT_DP = 30
        const val TEXT_BOTTOM_DP = 22
    }
}
