package app.mate.ui.mixer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.mate.R
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Dessin d'une tranche du widget Mixage en thème Instrument (« A · Table à faders », choix d'Ahmed, #243) : afficheur LCD
 * du nom, rail fin et curseur à capuchon comme les touches du dock, vumètre à segments, volume en Departure Mono et
 * touche Muet à DEL (socle + capuchon, `dock_key_base`/`dock_key_cap`/`dock_led`). [FaderStrip] garde la géométrie et le
 * toucher ; il passe ici le canvas, les rectangles et l'état. Rien ne s'alloue au dessin.
 */
class FaderInstrument(private val context: Context) {
    /** Departure Mono (`c_font_display`) : nom de l'afficheur, volume, libellé de la touche. */
    val display: Typeface = themeFont(context, R.attr.c_font_display)
    private val label: Typeface = themeFont(context, R.attr.c_font_label)
    val lcdText = themeColor(context, R.attr.c_mx_lcd_text)

    private val density = context.resources.displayMetrics.density
    private val scaled = context.resources.displayMetrics.scaledDensity
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val cLcdBg = themeColor(context, R.attr.c_mx_lcd_bg)
    private val cLedOff = themeColor(context, R.attr.c_mx_led_off)
    private val cLeds = intArrayOf(
        themeColor(context, R.attr.c_mx_led_low),
        themeColor(context, R.attr.c_mx_led_mid),
        themeColor(context, R.attr.c_mx_led_high),
    )
    private val cSlot = themeColor(context, R.attr.c_stroke)
    private val cRaised = themeColor(context, R.attr.c_surface_raised)
    private val cSocle = themeColor(context, R.attr.c_dock_socle)
    private val cSignal = themeColor(context, R.attr.c_signal)
    private val cText = themeColor(context, R.attr.c_text)
    private val cDim = themeColor(context, R.attr.c_text_dim)
    private val cMuted = themeColor(context, R.attr.c_ko)

    private val keyBase: Drawable? = ContextCompat.getDrawable(context, R.drawable.dock_key_base)?.mutate()
    private val keyCap: Drawable? = ContextCompat.getDrawable(context, R.drawable.dock_key_cap)?.mutate()
    private val keyLed: Drawable? = ContextCompat.getDrawable(context, R.drawable.dock_led)?.mutate()

    private fun dp(value: Float) = value * density

    /** Afficheur du nom : rectangle arrondi sur le fond LCD. */
    fun drawLcd(canvas: Canvas, lcd: RectF) {
        paint.color = cLcdBg
        canvas.drawRoundRect(lcd, dp(LCD_RADIUS_DP), dp(LCD_RADIUS_DP), paint)
    }

    /** Fente du curseur, de `top` à `bottom`, centrée sur `cx`. */
    fun drawRail(canvas: Canvas, cx: Float, top: Float, bottom: Float) {
        val half = dp(RAIL_DP) / 2
        paint.color = cSlot
        canvas.drawRoundRect(cx - half, top, cx + half, bottom, half, half, paint)
    }

    /** Curseur : socle décalé dessous, capuchon rond de 6 dp, index court orange ; estompé s'il ne se pilote pas. */
    fun drawThumb(canvas: Canvas, cx: Float, y: Float, w: Float, h: Float, enabled: Boolean) {
        val alpha = if (enabled) 255 else DISABLED_ALPHA
        val r = dp(THUMB_RADIUS_DP)
        rect.set(cx - w / 2, y - h / 2 + dp(SOCLE_DP), cx + w / 2, y + h / 2 + dp(SOCLE_DP))
        paint.color = cSocle
        paint.alpha = alpha
        canvas.drawRoundRect(rect, r, r, paint)
        rect.offset(0f, -dp(SOCLE_DP))
        paint.color = cRaised
        paint.alpha = alpha
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = cSlot
        paint.alpha = alpha
        rect.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(rect, r, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = cSignal
        paint.alpha = alpha
        val half = dp(INDEX_DP) / 2
        canvas.drawRoundRect(cx - w / 4, y - half, cx + w / 4, y + half, half, half, paint)
        paint.alpha = 255
    }

    /**
     * Vumètre à segments (0 = en bas) de `top` à `bottom` : `lit` segments allumés sur `segs`, zones de [MixerMath.ledZone] ;
     * éteints, ou tous estompés quand le niveau n'est pas lu (`metered` faux).
     */
    fun drawMeter(canvas: Canvas, x: Float, top: Float, bottom: Float, lit: Int, segs: Int, metered: Boolean) {
        val gap = dp(SEG_GAP_DP)
        val segH = ((bottom - top) + gap) / segs - gap
        val r = dp(SEG_RADIUS_DP)
        val off = if (metered) cLedOff else ColorUtils.setAlphaComponent(cLedOff, Color.alpha(cLedOff) * UNMETERED_ALPHA / 255)
        for (i in 0 until segs) {
            val segBottom = bottom - i * (segH + gap)
            paint.color = if (metered && i < lit) cLeds[MixerMath.ledZone(i, segs)] else off
            canvas.drawRoundRect(x, segBottom - segH, x + dp(SEG_W_DP), segBottom, r, r, paint)
        }
    }

    /** Volume (« 65% ») centré sur `x`, à la ligne de base `y` ; barré et `c_ko` en sourdine. */
    fun drawValue(canvas: Canvas, shown: String, x: Float, y: Float, muted: Boolean, small: Boolean, tight: Boolean) {
        text.typeface = display
        text.textAlign = Paint.Align.CENTER
        text.textSize = scaled * if (small) SILENT_SP else if (tight) VALUE_TIGHT_SP else VALUE_SP
        text.color = if (muted) cMuted else cText
        text.isStrikeThruText = muted
        canvas.drawText(shown, x, y, text)
        text.isStrikeThruText = false
    }

    /** Ligne de base d'un volume centré à la hauteur `centerY` (mode serré, à côté de la touche Muet). */
    fun valueBaseline(centerY: Float): Float {
        text.typeface = display
        text.textSize = scaled * VALUE_TIGHT_SP
        return centerY - (text.ascent() + text.descent()) / 2
    }

    /**
     * Touche Muet : socle fixe, capuchon qui s'enfonce de 4 dp à l'appui et tant que la voie est coupée (DEL et contour
     * orange, libellé « COUPÉ »), comme une touche allumée du dock.
     */
    fun drawKey(canvas: Canvas, box: RectF, muted: Boolean, pressed: Boolean, name: String) {
        val socle = dp(SOCLE_KEY_DP).toInt()
        val sink = if (muted || pressed) socle else 0
        val l = box.left.toInt()
        val r = box.right.toInt()
        keyBase?.run {
            setBounds(l, box.top.toInt() + socle, r, box.bottom.toInt())
            draw(canvas)
        }
        canvas.save()
        canvas.translate(0f, sink.toFloat())
        keyCap?.run {
            state = if (pressed) PRESSED else if (muted) LIT else IDLE
            setBounds(l, box.top.toInt(), r, box.bottom.toInt() - socle)
            draw(canvas)
        }
        val capH = box.height() - socle
        val ledW = dp(LED_W_DP).toInt()
        val ledTop = box.top + dp(LED_TOP_DP)
        keyLed?.run {
            state = if (muted) LIT else IDLE
            val mid = box.centerX().toInt()
            setBounds(mid - ledW / 2, ledTop.toInt(), mid + ledW / 2, (ledTop + dp(LED_H_DP)).toInt())
            draw(canvas)
        }
        text.typeface = label
        text.textAlign = Paint.Align.CENTER
        text.textSize = scaled * KEY_LABEL_SP
        text.letterSpacing = KEY_SPACING
        text.color = if (muted) cText else cDim
        canvas.drawText(name, box.centerX(), box.top + capH * LABEL_AT - (text.ascent() + text.descent()) / 2, text)
        text.letterSpacing = 0f
        canvas.restore()
    }

    private companion object {
        const val LCD_RADIUS_DP = 6f
        const val RAIL_DP = 6f
        const val THUMB_RADIUS_DP = 6f
        const val INDEX_DP = 3f
        const val SOCLE_DP = 2f
        const val SOCLE_KEY_DP = 4f
        const val SEG_W_DP = 8f
        const val SEG_GAP_DP = 2f
        const val SEG_RADIUS_DP = 1f
        const val LED_W_DP = 14f
        const val LED_H_DP = 4f
        const val LED_TOP_DP = 7f
        const val SILENT_SP = 11f
        const val VALUE_SP = 15f
        const val VALUE_TIGHT_SP = 13f
        const val KEY_LABEL_SP = 11f
        const val KEY_SPACING = 0.06f

        /** Le libellé de la touche se centre aux deux tiers du capuchon (la DEL est au-dessus). */
        const val LABEL_AT = 0.66f
        const val DISABLED_ALPHA = 90

        /** Opacité des segments sans niveau lu (35 %). */
        const val UNMETERED_ALPHA = 0x59
        val IDLE = intArrayOf()
        val PRESSED = intArrayOf(android.R.attr.state_pressed)
        val LIT = intArrayOf(android.R.attr.state_selected)
    }
}
