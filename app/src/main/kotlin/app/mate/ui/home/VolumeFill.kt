package app.mate.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Volume en remplissage : la carte se remplit du bas à hauteur du volume, l'icône en haut, le volume en grand en bas.
 * Glisser de haut en bas n'importe où règle le volume (sans saut : le doigt déplace d'autant) ; toucher coupe ou rend
 * le son. Une seule vue dessinée à la main (la tablette est lente).
 */
class VolumeFill(context: Context) : View(context) {
    /** Volume choisi en glissant ; `final` au lâcher. */
    var onVolume: ((volume: Int, final: Boolean) -> Unit)? = null
    var onMute: (() -> Unit)? = null

    /** null : ne se pilote pas (ordinateur injoignable, ou sortie sans volume). Ignoré pendant un glisser. */
    var volume: Int? = null
        set(value) {
            if (dragging || field == value) return
            field = value
            describe()
            invalidate()
        }
    var muted = false
        set(value) {
            if (field == value) return
            field = value
            tintIcon()
            describe()
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = themeFont(context, R.attr.c_font_light)
        color = themeColor(context, R.attr.c_text)
    }
    private val cFill = themeColor(context, R.attr.c_accent_soft)
    private val cFillMuted = themeColor(context, R.attr.c_ko_soft)
    private val iconOn: Drawable = icon(R.drawable.ic_volume)
    private val iconOff: Drawable = icon(R.drawable.ic_volume_off)
    private val pad = context.dp(PAD_DP)
    private val iconSize = context.dp(ICON_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var startVolume = 0
    private var dragging = false

    init {
        isClickable = true
        isFocusable = true
        tintIcon()
        describe()
    }

    private fun icon(res: Int): Drawable = DrawableCompat.wrap(ContextCompat.getDrawable(context, res)!!.mutate())

    private fun tintIcon() {
        DrawableCompat.setTint(iconOn, themeColor(context, R.attr.c_text))
        DrawableCompat.setTint(iconOff, themeColor(context, R.attr.c_ko))
    }

    override fun onDraw(canvas: Canvas) {
        val level = (volume ?: 0) / AudioLink.MAX.toFloat()
        fill.color = if (muted) cFillMuted else cFill
        if (level > 0f) canvas.drawRect(0f, height * (1 - level), width.toFloat(), height.toFloat(), fill)
        val icon = if (muted) iconOff else iconOn
        icon.setBounds(pad, pad, pad + iconSize, pad + iconSize)
        icon.draw(canvas)
        big.textSize = (minOf(width, height) * TEXT_RATIO).coerceIn(context.dp(TEXT_MIN_DP).toFloat(), context.dp(TEXT_MAX_DP).toFloat())
        canvas.drawText(valueText(), pad.toFloat(), height - pad - big.descent(), big)
    }

    private fun valueText() = when {
        muted -> context.getString(R.string.muted)
        volume == null -> "–"
        else -> context.getString(R.string.percent, volume)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                startVolume = volume ?: 0
                dragging = false
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > slop && volume != null) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) drag(event.y, final = false)
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) drag(event.y, final = true) else { performClick(); onMute?.invoke() }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun drag(y: Float, final: Boolean) {
        val next = (startVolume + (downY - y) / height * AudioLink.MAX).roundToInt().coerceIn(0, AudioLink.MAX)
        if (next != volume || final) {
            val wasDragging = dragging
            dragging = false
            volume = next
            dragging = wasDragging && !final
            onVolume?.invoke(next, final)
        }
    }

    private fun describe() {
        contentDescription = context.getString(R.string.computer_volume) + ", " + valueText()
    }

    // TalkBack : glisser n'est pas un geste possible ; monter et baisser par les actions de défilement.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> AudioLink.STEP
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -AudioLink.STEP
            else -> return super.performAccessibilityAction(action, arguments)
        }
        val next = ((volume ?: return false) + step).coerceIn(0, AudioLink.MAX)
        volume = next
        onVolume?.invoke(next, true)
        return true
    }

    private companion object {
        const val PAD_DP = 16
        const val ICON_DP = 28
        const val TEXT_RATIO = 0.26f
        const val TEXT_MIN_DP = 28
        const val TEXT_MAX_DP = 64
    }
}
