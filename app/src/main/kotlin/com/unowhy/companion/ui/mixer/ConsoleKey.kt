package com.unowhy.companion.ui.mixer

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
import androidx.core.graphics.withTranslation
import com.unowhy.companion.R
import com.unowhy.companion.ui.dp
import com.unowhy.companion.ui.themeColor
import com.unowhy.companion.ui.themeFont

/** Touche de console (scènes de l'onglet Son) : relief, enfoncée au toucher, voyant allumé sur la scène rappelée. */
class ConsoleKey(context: Context) : AppCompatTextView(context) {

    /** Touche à voyant (scène) ; sans voyant : « Enregistrer ». */
    var led = true

    /** Voyant allumé : c'est la dernière scène rappelée. */
    var lit = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

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
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        face = LinearGradient(0f, 0f, 0f, h - dp(3).toFloat(), cTop, cBottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = super.onTouchEvent(event)
        invalidate()
        return handled
    }
}
