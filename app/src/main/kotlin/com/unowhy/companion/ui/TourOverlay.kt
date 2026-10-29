package com.unowhy.companion.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import com.unowhy.companion.Prefs
import com.unowhy.companion.R

/**
 * Visite guidée du premier lancement ([TourModel]) : un voile sur tout l'écran, percé autour de l'entrée de la barre
 * présentée (projecteur), et une bulle à sa droite (titre, texte, Passer / Suivant). Quatre étapes, puis plus jamais.
 * `anchor` : la vue à éclairer pour une étape (null : bulle au centre, sans projecteur).
 */
@SuppressLint("ViewConstructor")
class TourOverlay(context: Context, private val anchor: (TourModel.Step) -> View?) : FrameLayout(context) {

    private val scrim = themeColor(context, R.attr.c_scrim)
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private var hole: RectF? = null
    private var index = 0

    /** Visite finie ou passée : ce qui attendait la fin (écran des plugins) peut s'afficher. */
    var onDone: () -> Unit = {}

    private val title = TextView(context).apply {
        textSize = 22f
        typeface = themeFont(context, R.attr.c_font_title)
        setTextColor(themeColor(context, R.attr.c_text))
    }
    private val body = TextView(context).apply {
        textSize = 16f
        typeface = themeFont(context, R.attr.c_font_regular)
        setTextColor(themeColor(context, R.attr.c_text_dim))
        setLineSpacing(0f, 1.2f)
    }
    private val counter = TextView(context).apply {
        textSize = 13f
        setTextColor(themeColor(context, R.attr.c_text_faint))
    }
    private val skip = Button(context, null, 0, R.style.ActionButton).apply {
        setText(R.string.tour_skip)
        setOnClickListener { finish() }
    }
    private val next = Button(context, null, 0, R.style.PrimaryButton).apply { setOnClickListener { advance() } }

    private val bubble = LinearLayout(context, null, 0, R.style.Card).apply {
        orientation = LinearLayout.VERTICAL
        addView(counter)
        addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(skip)
                addView(next, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) },
        )
    }

    init {
        setWillNotDraw(false)
        // Calque matériel : la découpe du projecteur (CLEAR) efface le voile, pas ce qui est dessous.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(bubble, LayoutParams(dp(BUBBLE_WIDTH_DP), LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    fun start() {
        index = 0
        visibility = VISIBLE
        alpha = 0f
        Look.navSpring(this, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
        post { show() }
    }

    // Le voile garde tous les touchers : rien ne se passe dessous pendant la visite.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = true

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(scrim)
        hole?.let { canvas.drawRoundRect(it, dp(RADIUS_DP).toFloat(), dp(RADIUS_DP).toFloat(), clear) }
    }

    private fun show() {
        // Voile ou bulle pas encore mesurés (premier passage) : on attend la mise en page.
        if (width == 0 || bubble.width == 0) {
            post { show() }
            return
        }
        val step = TourModel.STEPS[index]
        val (titleRes, bodyRes) = TEXTS.getValue(step)
        title.setText(titleRes)
        body.setText(bodyRes)
        counter.text = context.getString(R.string.tour_counter, index + 1, TourModel.STEPS.size)
        next.setText(if (index == TourModel.STEPS.lastIndex) R.string.tour_done else R.string.tour_next)
        val target = anchor(step)?.takeIf { it.isShown && it.width > 0 }
        val here = IntArray(2).also { getLocationOnScreen(it) }
        hole = target?.let {
            val at = IntArray(2).also { a -> it.getLocationOnScreen(a) }
            val pad = dp(6).toFloat()
            RectF(at[0] - here[0] - pad, at[1] - here[1] - pad, at[0] - here[0] + it.width + pad, at[1] - here[1] + it.height + pad)
        }
        invalidate()
        bubble.post {
            val h = hole
            val x = if (h != null) h.right + dp(20) else (width - bubble.width) / 2f
            val y = if (h != null) (h.centerY() - bubble.height / 2f).coerceIn(dp(16).toFloat(), (height - bubble.height - dp(16)).toFloat()) else (height - bubble.height) / 2f
            // La bulle est posée en haut à gauche : sa place passe par la translation (x = gauche + translation).
            // Elle arrive de la barre : 16 dp avant sa place, en fondu.
            bubble.translationY = y
            bubble.translationX = x - dp(16)
            bubble.alpha = 0f
            Look.navSpring(bubble, DynamicAnimation.TRANSLATION_X, Look.SPATIAL_DEFAULT, x)
            Look.navSpring(bubble, DynamicAnimation.ALPHA, Look.EFFECTS_DEFAULT, 1f)
        }
    }

    private fun advance() {
        if (index == TourModel.STEPS.lastIndex) return finish()
        index++
        show()
    }

    private fun finish() {
        Prefs.setTourDone(context)
        Look.navSpring(this, DynamicAnimation.ALPHA, Look.EFFECTS_FAST, 0f) {
            visibility = GONE
            onDone()
        }
    }

    private companion object {
        const val BUBBLE_WIDTH_DP = 420
        const val RADIUS_DP = 18

        val TEXTS = mapOf(
            TourModel.Step.HOME to (R.string.tour_home_title to R.string.tour_home_body),
            TourModel.Step.SCREENS to (R.string.tour_screens_title to R.string.tour_screens_body),
            TourModel.Step.SWIPE to (R.string.tour_swipe_title to R.string.tour_swipe_body),
            TourModel.Step.TALK to (R.string.tour_talk_title to R.string.tour_talk_body),
            TourModel.Step.NOTIFICATIONS to (R.string.tour_notifications_title to R.string.tour_notifications_body),
            TourModel.Step.CUSTOMIZE to (R.string.tour_customize_title to R.string.tour_customize_body),
        )
    }
}
