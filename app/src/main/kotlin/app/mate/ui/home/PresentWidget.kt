package app.mate.ui.home

import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.text.TextUtils
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
 * grande, c'est le geste principal), début, fin, chrono et micro de l'ordinateur (rouge quand il est coupé), disposés
 * selon la forme (`planPresent`, #184) : Pastille = Suivante seule, Ligne = les touches qui tiennent, Colonne =
 * Suivante, Précédente puis les outils, Bloc et Plein écran = deux rangées, Grand en largeur = une rangée. Le chrono est
 * partagé par tous les exemplaires du widget (l'exemplaire agrandi continue le même) et ne tique qu'à l'écran, tant
 * qu'il tourne.
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

    /** Aperçu du catalogue : chrono et micro d'exemple, sans toucher au chrono partagé ni à l'ordinateur. */
    private var preview: PresentSample? = null

    /** Hauteur utile de la carte, en dp : la Pastille y règle sa touche. */
    private var innerH = 0

    init {
        view.addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
            val h = ((b - t - v.paddingTop - v.paddingBottom) / context.resources.displayMetrics.density).toInt()
            if (h == innerH) return@addOnLayoutChangeListener
            innerH = h
            if (family == ShapeFamily.PILL) v.post { next.fitPill(h) }
        }
    }

    override fun applySize() {
        view.removeAllViews()
        for (key in listOf(previous, next, start, end, mic)) (key.parent as? ViewGroup)?.removeView(key)
        (chrono.parent as? ViewGroup)?.removeView(chrono)
        val plan = planPresent(placed.size)
        // Hauteur relevée à nouveau au prochain tour de mise en page : pas de Pastille aux cotes de la forme d'avant.
        innerH = 0
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val pad = dp(if (plan.sideHeader || !plan.header) TIGHT_PAD else PAD)
        view.setPadding(pad, pad, pad, pad)
        chrono.textSize = plan.chronoSp
        val title = cardTitle(placed.type.title)
        // Ligne : titre et chrono à gauche, les touches à droite ; ailleurs, titre et chrono en haut.
        val body: LinearLayout = if (plan.sideHeader) {
            LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }.also { line ->
                val side = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(chrono)
                }
                line.addView(side, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(GAP) })
                view.addView(line, LinearLayout.LayoutParams(match, 0, 1f))
            }
        } else {
            if (plan.header) {
                view.addView(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
                        addView(chrono, LinearLayout.LayoutParams(wrap, wrap))
                    },
                    LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dp(GAP) },
                )
            }
            view
        }
        plan.rows.forEachIndexed { r, row ->
            // Au moins une cible tactile de haut (48 dp), même dans une colonne basse.
            val line = LinearLayout(context).apply { minimumHeight = dp(MIN_TOUCH_DP) }
            row.cells.forEachIndexed { i, cell ->
                val keys = cell.keys.map(::keyOf)
                keys.forEach { it.arrange(cell.style, plan.scale) }
                val child: View = keys.singleOrNull() ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    keys.forEachIndexed { k, key -> addView(key, LinearLayout.LayoutParams(match, 0, 1f).apply { if (k > 0) topMargin = dp(GAP) }) }
                }
                line.addView(child, LinearLayout.LayoutParams(0, match, cell.weight).apply { if (i > 0) marginStart = dp(GAP) })
            }
            val params = if (plan.sideHeader) LinearLayout.LayoutParams(0, match, 1f) else LinearLayout.LayoutParams(match, 0, row.weight)
            if (r > 0) params.topMargin = dp(GAP)
            body.addView(line, params)
        }
        if (family == ShapeFamily.PILL && innerH > 0) next.fitPill(innerH)
        renderChrono()
        renderMic()
        syncTicker()
    }

    private fun keyOf(key: PresentKey): Key = when (key) {
        PresentKey.PREVIOUS -> previous
        PresentKey.NEXT -> next
        PresentKey.START -> start
        PresentKey.END -> end
        PresentKey.MIC -> mic
    }

    override fun showPreview(sample: WidgetSample) {
        preview = sample as? PresentSample ?: return
        audio = preview?.audio
        renderChrono()
        renderMic()
    }

    override suspend fun load() {
        if (preview == null) readMic()
    }

    override fun setActive(active: Boolean) {
        this.active = active && preview == null
        if (!this.active) {
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
        // Pastille : pas de chrono à l'écran, rien à faire tiquer.
        if (!active || !timer.running || chrono.parent == null) {
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
        val sample = preview
        val running = sample?.running ?: timer.running
        chrono.text = PresentLogic.clock(sample?.elapsedMs ?: timer.elapsed(SystemClock.elapsedRealtime()))
        chrono.setTextColor(themeColor(context, if (running) R.attr.c_text else R.attr.c_text_dim))
    }

    private fun renderMic() {
        val state = PresentLogic.mic(audio)
        mic.set(state.label, state.icon, alert = state == PresentLogic.Mic.MUTED)
    }

    /** Gestes envoyés dans l'ordre des appuis (Début = deux touches : jamais mêlées à une flèche). Le chrono suit une fois la touche passée. */
    private fun send(action: PresentAction) {
        if (preview != null) return
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
        if (preview != null) return
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
            // Texte agrandi (Réglages) ou touche étroite : le nom finit par « … » plutôt que coupé net.
            ellipsize = TextUtils.TruncateAt.END
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

        /** [style] : icône sur le nom, côte à côte ou icône seule ; [scale] : 1 pour les cotes du Bloc. */
        fun arrange(style: PresentStyle, scale: Float) {
            val main = kind == Kind.MAIN
            val compact = style != PresentStyle.STACKED
            orientation = if (compact) HORIZONTAL else VERTICAL
            val size = dp(((if (compact) 28 else if (main) 72 else 52) * scale).toInt())
            image.layoutParams = LayoutParams(size, size)
            text.textSize = (if (compact) 18f else if (main) 24f else 20f) * scale
            text.visibility = if (style == PresentStyle.ICON) GONE else VISIBLE
            (text.layoutParams as LayoutParams).apply { if (compact) { topMargin = 0; marginStart = dp(10) } else { topMargin = dp(4); marginStart = 0 } }
        }

        /** Pastille : l'icône prend la hauteur de la carte, le nom dessous s'il reste la place. */
        fun fitPill(heightDp: Int) {
            val pill = presentPill(heightDp)
            text.visibility = if (pill.withText) VISIBLE else GONE
            image.layoutParams = LayoutParams(dp(pill.iconDp), dp(pill.iconDp))
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
        const val PAD = 16
        const val MIN_TOUCH_DP = 48
        const val TIGHT_PAD = 10

        const val TICK_MS = 1_000L

        /** Un seul chrono pour tous les exemplaires du widget. */
        val timer = PresentTimer()

        /** Une touche à la fois vers l'ordinateur. */
        val queue = Mutex()
    }
}

/** Exemple de Présentation pour le catalogue : chrono qui tourne depuis 12 min 34 s, micro ouvert. */
class PresentSample(val elapsedMs: Long, val running: Boolean, val audio: AudioState) : WidgetSample
