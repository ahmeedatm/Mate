package app.mate.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.net.PointerButton
import app.mate.net.PointerChannel
import app.mate.ui.dp
import app.mate.ui.pointer.PX_PER_NOTCH
import app.mate.ui.pointer.PadCommand
import app.mate.ui.pointer.ScrollStrip
import app.mate.ui.pointer.TAP_SLOP_PX
import app.mate.ui.pointer.TouchAction
import app.mate.ui.pointer.send
import app.mate.ui.themeColor
import app.mate.ui.themeRes

/**
 * Le Pavé tactile en Pastille, Ligne et Colonne (#183). Pastille : un bouton Clic (toucher = clic gauche, maintenir =
 * clic droit). Ligne et Colonne : une bande de défilement sur un axe ([ScrollStrip]) ; [variant] provisoire, le temps
 * qu'Ahmed choisisse : A bande seule (une tape clique), B bande + bouton Clic, C deux boutons Clic gauche et droit.
 * Le canal vers l'ordinateur n'existe que tant que le widget est à l'écran ; l'aperçu du catalogue n'en ouvre pas.
 */
class PadCompact(context: Context, private val family: ShapeFamily, private val variant: String) : LinearLayout(context) {
    private var channel: PointerChannel? = null
    private var strip: ScrollStrip? = null

    init {
        orientation = if (family == ShapeFamily.ROW) HORIZONTAL else VERTICAL
        when {
            family == ShapeFamily.PILL -> addView(clickButton(R.string.pad_click, null), LayoutParams(MATCH, MATCH))
            variant == "C" -> {
                addView(clickButton(R.string.pad_left_click, PointerButton.LEFT), weighted(0))
                addView(clickButton(R.string.pad_right_click, PointerButton.RIGHT), weighted(1))
            }
            variant == "B" -> {
                addView(stripView(tapClicks = false), weighted(0, weight = 2f))
                addView(clickButton(R.string.pad_click, null), weighted(1))
            }
            else -> addView(stripView(tapClicks = true), LayoutParams(MATCH, MATCH))
        }
    }

    /** À l'écran : canal ouvert et écran gardé allumé ; ailleurs : canal fermé. */
    fun setActive(active: Boolean) {
        keepScreenOn = active
        if (active) {
            if (channel != null) return
            channel = PointerChannel.create(context)
        } else {
            strip?.onTouch(TouchAction.CANCEL, 0f, 0f)
            channel?.close()
            channel = null
        }
    }

    private fun send(command: PadCommand) {
        channel?.send(command)
    }

    /** Bouton Clic : [button] null = toucher pour le clic gauche, maintenir pour le clic droit. */
    private fun clickButton(label: Int, button: PointerButton?) = Button(context).apply {
        setText(label)
        isAllCaps = false
        textSize = BUTTON_SP
        setTextColor(themeColor(context, R.attr.c_text))
        setBackgroundResource(R.drawable.pill_bg)
        if (button == null) contentDescription = context.getString(R.string.pad_click_desc)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            send(PadCommand.Click(button ?: PointerButton.LEFT))
        }
        if (button == null) {
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                send(PadCommand.Click(PointerButton.RIGHT))
                true
            }
        }
    }

    /** La bande : fond des cartes, une phrase courte au milieu ; glisser le long d'elle fait défiler. */
    @SuppressLint("ClickableViewAccessibility")
    private fun stripView(tapClicks: Boolean): View {
        val vertical = family != ShapeFamily.ROW
        val density = resources.displayMetrics.density
        val gestures = ScrollStrip(vertical, tapClicks, ::send, TAP_SLOP_PX * density, PX_PER_NOTCH * density).also { strip = it }
        return FrameLayout(context).apply {
            setBackgroundResource(themeRes(context, R.attr.c_home_card_bg))
            contentDescription = context.getString(if (vertical) R.string.pad_scroll_desc_v else R.string.pad_scroll_desc_h)
            addView(TextView(context).apply {
                setText(if (vertical) R.string.pad_scroll_hint_v else R.string.pad_scroll_hint_h)
                textSize = HINT_SP
                setTextColor(themeColor(context, R.attr.c_text_faint))
                gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
            setOnTouchListener { v, e ->
                // Les glissés sont pour la bande : ni le balayage de profils ni un défilement parent ne les prennent.
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> gestures.onTouch(TouchAction.DOWN, e.x, e.y)
                    MotionEvent.ACTION_MOVE -> gestures.onTouch(TouchAction.MOVE, e.x, e.y)
                    MotionEvent.ACTION_UP -> gestures.onTouch(TouchAction.UP, e.x, e.y)
                    MotionEvent.ACTION_CANCEL -> gestures.onTouch(TouchAction.CANCEL, e.x, e.y)
                }
                true
            }
        }
    }

    private fun weighted(index: Int, weight: Float = 1f): LayoutParams {
        val gap = context.dp(GAP_DP)
        return if (orientation == HORIZONTAL) {
            LayoutParams(0, MATCH, weight).apply { if (index > 0) marginStart = gap }
        } else {
            LayoutParams(MATCH, 0, weight).apply { if (index > 0) topMargin = gap }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val GAP_DP = 8
        const val BUTTON_SP = 16f
        const val HINT_SP = 14f
    }
}
