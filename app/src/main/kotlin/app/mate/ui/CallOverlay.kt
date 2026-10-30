package app.mate.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import app.mate.MainActivity
import app.mate.R
import app.mate.audio.Speaker
import app.mate.device.Screen
import app.mate.net.BackendClient
import app.mate.voice.Mic
import kotlinx.coroutines.launch

/**
 * Appel entrant sur l'ordinateur (Discord…, voir backend/src/calls.ts) : grande carte par-dessus tout, avec Répondre,
 * Vidéo et Ignorer, qui appuient sur les vrais boutons de l'appli. Répondre ouvre ensuite la page de l'appli dans le
 * Deck (micro, sourdine, Quitter l'appel). Sans réponse, la carte part quand l'appel s'arrête (« Appel manqué »).
 */
class CallOverlay(private val activity: MainActivity) : FrameLayout(activity) {

    /** Appli qui sonne (null : pas d'appel), et son appelant s'il est connu. */
    var app: String? = null
        private set
    private var caller: String? = null
    private var announced = false
    private var pulse: ValueAnimator? = null

    private fun color(attr: Int) = themeColor(context, attr)

    private val badge = ImageView(context).apply {
        setImageDrawable(Look.badge(context, R.drawable.ic_call, color(R.attr.c_ok), sizeDp = 88, iconDp = 44))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = text(18f, R.attr.c_text_dim).apply { setText(R.string.call_incoming) }
    private val name = text(40f, R.attr.c_text).apply { typeface = themeFont(context, R.attr.c_font_medium); maxLines = 2 }
    private val answer = button(R.string.call_answer, R.drawable.ic_call, R.attr.c_ok) { respond(video = false) }
    private val video = button(R.string.call_video, R.drawable.ic_videocam, R.attr.c_accent) { respond(video = true) }
    private val decline = button(R.string.call_decline, R.drawable.ic_call_end, R.attr.c_ko) { decline() }

    init {
        setBackgroundColor(ColorUtils.setAlphaComponent(color(R.attr.c_bg), 0xE6))
        isClickable = true // les touchers ne passent pas à l'écran dessous
        visibility = GONE
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.console_tile_bg)
            setPadding(dp(48), dp(36), dp(48), dp(36))
            addView(badge)
            addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(20) })
            addView(name, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(decline, video, answer).forEachIndexed { i, b ->
                    addView(b, LinearLayout.LayoutParams(WRAP, dp(64)).apply { if (i > 0) marginStart = dp(16) })
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(32) })
        }
        addView(card, LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private fun text(size: Float, colorAttr: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color(colorAttr))
        gravity = Gravity.CENTER
    }

    private fun button(text: Int, icon: Int, colorAttr: Int, onClick: () -> Unit) =
        Button(context, null, 0, R.style.NowButton).apply {
            setText(text)
            textSize = 20f
            setPadding(dp(28), 0, dp(28), 0)
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            backgroundTintList = ColorStateList.valueOf(color(colorAttr))
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.press)
            setOnClickListener { onClick() }
        }

    /** Sonnerie sans nouvelle de l'ordinateur (fin d'appel perdue, compagnon redémarré) : la carte se ferme seule. */
    private val expire = Runnable { if (app != null) hide() }

    /** Ordre `call` de l'ordinateur : `ringing` (de nouveau quand l'appelant est connu) ou `ended`. */
    fun onCall(state: String, app: String, caller: String?, hasVideo: Boolean) {
        if (state == "ended") {
            if (this.app == app) {
                hide()
                activity.showBanner(context.getString(R.string.call_missed, this.caller ?: app, app), 60, icon = BannerIcon.App(app))
            }
            return
        }
        val fresh = this.app != app
        this.app = app
        if (caller != null) this.caller = caller else if (fresh) this.caller = null
        name.text = this.caller ?: app
        label.text = context.getString(if (this.caller != null) R.string.call_incoming_app else R.string.call_incoming, app)
        video.visibility = if (hasVideo) VISIBLE else GONE
        setButtonsEnabled(true)
        removeCallbacks(expire)
        postDelayed(expire, RING_TIMEOUT_MS)
        if (fresh) {
            announced = false
            // Carte affichée : le mot de réveil se tait jusqu'à sa fermeture.
            Mic.hold(Mic.CALL)
            visibility = VISIBLE
            bringToFront()
            Screen.touched(activity)
            Look.enter(this, 0)
            pulse = Look.pulse(badge)
            // Annonce un peu après : l'appelant arrive en général dans la seconde qui suit.
            postDelayed({ announce() }, 1_500)
        } else if (caller != null) {
            announce()
        }
    }

    private fun announce() {
        if (announced || app == null) return
        announced = true
        Speaker.speak(caller?.let { context.getString(R.string.call_spoken, it, app) } ?: context.getString(R.string.call_spoken_app, app))
    }

    fun hide() {
        removeCallbacks(expire)
        app = null
        visibility = GONE
        Mic.release(Mic.CALL)
        Look.stop(pulse, badge)
        pulse = null
        Speaker.stop()
    }

    private fun setButtonsEnabled(enabled: Boolean) = listOf(answer, video, decline).forEach { it.isEnabled = enabled }

    /** Aussi appelé par la commande vocale « réponds ». */
    fun respond(video: Boolean) {
        val current = app ?: return
        setButtonsEnabled(false)
        activity.lifecycleScope.launch {
            try {
                BackendClient(activity).system.answerCall(current, video)
                hide()
                activity.showAppControls(current)
            } catch (e: Exception) {
                setButtonsEnabled(true)
                activity.showBanner(context.errorText(e), 6)
            }
        }
    }

    fun decline() {
        val current = app ?: return
        setButtonsEnabled(false)
        activity.lifecycleScope.launch {
            // Fermée même si l'ordinateur n'a pas trouvé le bouton (appel déjà fini) : jamais de carte bloquée.
            try {
                BackendClient(activity).system.declineCall(current)
            } catch (e: Exception) {
                activity.showBanner(context.errorText(e), 6)
            }
            hide()
        }
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RING_TIMEOUT_MS = 120_000L
    }
}
