package app.mate.ui.home

import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.widget.ImageViewCompat
import app.mate.R
import app.mate.net.AudioState
import app.mate.net.BackendClient
import app.mate.net.PresentAction
import app.mate.net.runCatchingCancellable
import app.mate.ui.dp
import app.mate.ui.errorText
import app.mate.ui.themeColor
import app.mate.ui.themeFont
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Télécommande de diapos pour l'appli ouverte sur l'ordinateur (PowerPoint, Keynote) : précédente, suivante (la plus
 * grande, c'est le geste principal), début, fin, chrono et micro de l'ordinateur (rouge quand il est coupé). XL : tout
 * sur une rangée ; L : précédente et suivante en haut, début, fin et micro dessous. Le chrono est partagé par tous les
 * exemplaires du widget (l'exemplaire agrandi continue le même) et ne tique qu'à l'écran, tant qu'il tourne.
 */
class PresentWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LinearLayout(context).asCard(16).apply { orientation = LinearLayout.VERTICAL }

    private val chrono = TextView(context).apply {
        textSize = 34f
        includeFontPadding = false
        isSingleLine = true
        typeface = themeFont(context, R.attr.c_font_display)
    }
    private val previous = Key(R.drawable.ic_chevron_left, R.string.present_previous, R.string.present_previous_desc, Kind.PLAIN) { send(PresentAction.PREVIOUS) }
    private val next = Key(R.drawable.ic_chevron_right, R.string.present_next, R.string.present_next_desc, Kind.MAIN) { send(PresentAction.NEXT) }
    private val start = Key(R.drawable.ic_skip_previous, R.string.present_start, R.string.present_start_desc, Kind.PLAIN) { send(PresentAction.START) }
    private val end = Key(R.drawable.ic_tile_stop, R.string.present_end, R.string.present_end_desc, Kind.PLAIN) { send(PresentAction.END) }
    private val mic = Key(R.drawable.ic_mic, R.string.present_mic, R.string.mic_toggle, Kind.PLAIN) { toggleMic() }

    private var audio: AudioState? = null
    private var active = false
    private var ticker: Job? = null

    override fun applySize() {
        view.removeAllViews()
        for (key in listOf(previous, next, start, end, mic)) (key.parent as? ViewGroup)?.removeView(key)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(cardTitle(placed.type.title), LinearLayout.LayoutParams(0, wrap, 1f))
            addView(chrono, LinearLayout.LayoutParams(wrap, wrap))
        }
        view.addView(header, LinearLayout.LayoutParams(match, wrap))
        fun row(vararg parts: Pair<View, Float>) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            parts.forEachIndexed { i, (child, weight) ->
                addView(child, LinearLayout.LayoutParams(0, match, weight).apply { if (i > 0) marginStart = dp(GAP) })
            }
        }
        if (placed.size == WidgetSize.XL) {
            previous.arrange(compact = false)
            next.arrange(compact = false)
            mic.arrange(compact = false)
            start.arrange(compact = true)
            end.arrange(compact = true)
            val side = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(start, LinearLayout.LayoutParams(match, 0, 1f))
                addView(end, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(GAP) })
            }
            view.addView(row(previous to 2f, next to 3.2f, side to 1.5f, mic to 1.5f), LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(10) })
        } else {
            previous.arrange(compact = false)
            next.arrange(compact = false)
            for (key in listOf(start, end, mic)) key.arrange(compact = true)
            view.addView(row(previous to 1f, next to 1.6f), LinearLayout.LayoutParams(match, 0, 1.5f).apply { topMargin = dp(10) })
            view.addView(row(start to 1f, end to 1f, mic to 1.2f), LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = dp(GAP) })
        }
        renderChrono()
        renderMic()
    }

    override suspend fun load() = readMic()

    override fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            ticker?.cancel()
            ticker = null
            return
        }
        renderChrono()
        syncTicker()
        host.scope.launch { readMic() }
    }

    private suspend fun readMic() {
        runCatchingCancellable { BackendClient(context).system.audio() }.onSuccess { audio = it; renderMic() }
    }

    /** Un seul tick par seconde, seulement à l'écran et tant que le chrono tourne. */
    private fun syncTicker() {
        if (!active || !timer.running) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker?.isActive == true) return
        ticker = host.scope.launch {
            while (isActive && timer.running) {
                renderChrono()
                delay(TICK_MS)
            }
        }
    }

    private fun renderChrono() {
        chrono.text = PresentLogic.clock(timer.elapsed(SystemClock.elapsedRealtime()))
        chrono.setTextColor(themeColor(context, if (timer.running) R.attr.c_text else R.attr.c_text_dim))
    }

    private fun renderMic() {
        val state = PresentLogic.mic(audio)
        mic.set(state.label, state.icon, alert = state == PresentLogic.Mic.MUTED)
    }

    /** Gestes envoyés dans l'ordre des appuis (Début = deux touches : jamais mêlées à une flèche). Le chrono suit une fois la touche passée. */
    private fun send(action: PresentAction) {
        host.scope.launch {
            queue.withLock {
                runCatchingCancellable { BackendClient(context).present.send(action) }
                    .onSuccess {
                        timer.onAction(action, SystemClock.elapsedRealtime())
                        renderChrono()
                        syncTicker()
                    }
                    .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Même geste que la touche « Couper le micro » du deck : on lit l'état de l'ordinateur et on pose l'inverse. */
    private fun toggleMic() {
        host.scope.launch {
            runCatchingCancellable { val system = BackendClient(context).system; system.setAudio(micMuted = !system.audio().micMuted) }
                .onSuccess { audio = it; renderMic() }
                .onFailure { Toast.makeText(context, context.errorText(it), Toast.LENGTH_LONG).show() }
        }
    }

    private enum class Kind(@DrawableRes val background: Int, val tint: Int) {
        PLAIN(R.drawable.button_bg, R.attr.c_text),
        MAIN(R.drawable.button_primary_bg, R.attr.c_on_primary),
    }

    /** Grande touche : icône et nom, l'un au-dessus de l'autre (`compact` : côte à côte, pour les petites touches). */
    private inner class Key(
        @DrawableRes icon: Int,
        @StringRes label: Int,
        @StringRes private val description: Int,
        private val kind: Kind,
        onClick: () -> Unit,
    ) : LinearLayout(context) {
        private val image = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; setImageResource(icon) }
        private val text = TextView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            typeface = themeFont(context, R.attr.c_font_medium)
            gravity = Gravity.CENTER
            isSingleLine = true
            setText(label)
        }
        private var alert = false

        init {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(description)
            stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press)
            setOnClickListener { onClick() }
            addView(image)
            addView(text)
            paint()
        }

        fun arrange(compact: Boolean) {
            val main = kind == Kind.MAIN
            orientation = if (compact) HORIZONTAL else VERTICAL
            val size = dp(if (compact) 28 else if (main) 72 else 52)
            image.layoutParams = LayoutParams(size, size)
            text.textSize = if (compact) 18f else if (main) 24f else 20f
            (text.layoutParams as LayoutParams).apply { if (compact) { topMargin = 0; marginStart = dp(10) } else { topMargin = dp(4); marginStart = 0 } }
        }

        /** Nom, icône, et rouge quand `alert` (micro coupé). */
        fun set(@StringRes label: Int, @DrawableRes icon: Int, alert: Boolean) {
            text.setText(label)
            image.setImageResource(icon)
            contentDescription = context.getString(R.string.present_key_state, context.getString(description), context.getString(label))
            this.alert = alert
            paint()
        }

        private fun paint() {
            setBackgroundResource(if (alert) R.drawable.button_ko_bg else kind.background)
            val color = themeColor(context, if (alert) R.attr.c_ko else kind.tint)
            ImageViewCompat.setImageTintList(image, ColorStateList.valueOf(color))
            text.setTextColor(color)
        }
    }

    private companion object {
        const val GAP = 10
        const val TICK_MS = 1_000L

        /** Un seul chrono pour tous les exemplaires du widget. */
        val timer = PresentTimer()

        /** Une touche à la fois vers l'ordinateur. */
        val queue = Mutex()
    }
}
