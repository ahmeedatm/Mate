package app.mate.ui.mixer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeBool
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Touche de console (scènes de l'onglet Son) : relief, enfoncée au toucher, voyant allumé sur la scène rappelée.
 * Table nue (`c_mx_bare`, Hush) : pilule plate, sans relief ; la scène rappelée est cerclée d'accent avec un point.
 */
class ConsoleKey(context: Context) : AppCompatTextView(context) {

    /** Touche à voyant (scène) ; sans voyant : « Enregistrer ». */
    var led = true

    /** Voyant allumé : c'est la dernière scène rappelée. */
    var lit = false
        set(value) {
            if (field == value) return
            field = value
            if (bare) padFlat()
            invalidate()
        }

    private val bare = themeBool(context, R.attr.c_mx_bare)
    private val cAccent = themeColor(context, R.attr.c_accent)
    private val cFlat = ColorUtils.setAlphaComponent(themeColor(context, R.attr.c_mx_text), FLAT_ALPHA)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cTop = themeColor(context, R.attr.c_mx_key_top)
    private val cBottom = themeColor(context, R.attr.c_mx_key_bottom)
    private val cShadow = themeColor(context, R.attr.c_mx_shadow)
    private val cHighlight = themeColor(context, R.attr.c_mx_highlight)
    private val cLed = themeColor(context, R.attr.c_mx_led_low)
    private val cLedOff = themeColor(context, R.attr.c_mx_led_off)
    private var face: LinearGradient? = null

    init {
        gravity = Gravity.CENTER
        typeface = themeFont(context, R.attr.c_font_medium)
        textSize = 16f
        setTextColor(themeColor(context, R.attr.c_mx_text))
        setPadding(dp(34), 0, dp(22), dp(3))
        isClickable = true
        isLongClickable = true
        if (bare) padFlat()
    }

    /** Pilule plate : de la place pour le point à gauche seulement sur la scène rappelée. */
    private fun padFlat() = setPadding(dp(if (led && lit) 32 else 20), 0, dp(20), 0)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        face = LinearGradient(0f, 0f, 0f, h - dp(3).toFloat(), cTop, cBottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (bare) return drawFlat(canvas)
        val r = (height - dp(3)) / 2f
        val sink = if (isPressed) dp(2).toFloat() else 0f
        rect.set(0f, sink, width.toFloat(), height - dp(3).toFloat())
        paint.color = cShadow
        canvas.drawRoundRect(0f, dp(3).toFloat(), width.toFloat(), height.toFloat(), r, r, paint)
        paint.shader = face
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.color = cHighlight
        canvas.drawRect(rect.left + r, rect.top + 1, rect.right - r, rect.top + dp(2), paint)
        // Voyant à gauche du nom.
        val ledY = rect.centerY()
        if (led && lit) {
            paint.color = cLed
            paint.alpha = 80
            canvas.drawCircle(dp(20).toFloat(), ledY, dp(8).toFloat(), paint)
            paint.alpha = 255
        }
        if (led) {
            paint.color = if (lit) cLed else cLedOff
            canvas.drawCircle(dp(20).toFloat(), ledY, dp(4).toFloat(), paint)
        }
        canvas.withTranslation(0f, sink) { super.onDraw(this) }
    }

    private fun drawFlat(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val on = led && lit
        paint.style = Paint.Style.FILL
        paint.color = if (isPressed || on) cAccent else cFlat
        if (isPressed || on) paint.alpha = if (isPressed) PRESS_ALPHA else LIT_ALPHA
        canvas.drawRoundRect(rect, r, r, paint)
        paint.alpha = 255
        if (on) {
            val ring = dp(1.5f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ring
            paint.color = cAccent
            rect.inset(ring / 2, ring / 2)
            canvas.drawRoundRect(rect, r - ring / 2, r - ring / 2, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(dp(18).toFloat(), height / 2f, dp(3.5f), paint)
        }
        super.onDraw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        invalidate()
        return handled
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        /** Pilule plate au repos (7 %), scène rappelée (18 %) et appui (22 %, comme hush_press). */
        const val FLAT_ALPHA = 18
        const val LIT_ALPHA = 46
        const val PRESS_ALPHA = 56
    }
}
