package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.focus.FocusTicks
import app.mate.focus.FocusTimer
import app.mate.focus.NoisePlayer
import app.mate.ui.FocusDial
import app.mate.ui.Look
import app.mate.ui.TitleView
import app.mate.ui.label
import app.mate.ui.themeFont

/** Exemple du Focus pour le catalogue : un chrono en cours et une ambiance, sans toucher au vrai minuteur. */
class FocusSample(val state: FocusTimer.State, val noise: NoisePlayer.Kind?) : WidgetSample

/**
 * Minuteur Focus (DA Console douce) : 25 graduations, une par minute, allumées pendant le chrono. Toucher = mode Focus,
 * bouton = démarrer / pause. Une mise en page par famille de formes :
 * - Pastille : cadran réduit et bouton ;
 * - Ligne : temps, les graduations déroulées en bande, bouton ;
 * - Colonne : temps en haut, la bande à la verticale qui prend toute la hauteur, bouton ;
 * - Bloc, Grand, Plein écran : le cadran à la plus grande taille qui tient, les ambiances (sauf en 2 × 2) ; Grand et
 *   Plein écran ajoutent les séances finies ; plus large que haut, le cadran passe à gauche.
 */
class FocusWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    /** Conteneur stable : la vue interne est refaite quand la forme change. */
    override val view = FrameLayout(context)
    private var dial: FocusDial? = null
    private var band: FocusBand? = null
    private lateinit var time: TextView
    private lateinit var phase: TextView
    private var detail: TextView? = null
    private var sessions: TextView? = null
    private lateinit var toggle: View
    private lateinit var toggleIcon: ImageView
    private val noiseChips = LinkedHashMap<NoisePlayer.Kind, Button>()
    private val focusListener: (FocusTimer.State) -> Unit = { render() }
    private val noiseListener: () -> Unit = { render() }
    private var shown = false
    private var bound: WidgetSize? = null
    private var preview: FocusSample? = null

    init {
        bind()
    }

    override fun applySize() {
        if (placed.size == bound) return
        bind()
        render()
    }

    override fun showPreview(sample: WidgetSample) {
        preview = sample as? FocusSample ?: return
        render()
    }

    private fun bind() {
        bound = placed.size
        view.removeAllViews()
        noiseChips.clear()
        dial = null
        band = null
        detail = null
        sessions = null
        val inner = when (family) {
            ShapeFamily.PILL -> pill()
            ShapeFamily.ROW -> strip(vertical = false)
            ShapeFamily.COLUMN -> strip(vertical = true)
            ShapeFamily.BLOCK, ShapeFamily.LARGE, ShapeFamily.FULL -> big()
        }
        view.addView(inner)
        inner.setOnClickListener { host.main?.showMode("focus") }
        toggle.setOnClickListener { FocusTimer.toggle() }
        Look.pressSpring(inner)
        Look.pressSpring(toggle)
        shown = false
    }

    /** Une case : cadran réduit et bouton seulement (le titre « Focus » et l'ambiance ne tiennent pas à côté). */
    private fun pill(): View {
        val inner = LayoutInflater.from(context).inflate(R.layout.widget_focus, view, false)
        inner.background = HomeCard.background(context)
        dial = inner.findViewById(R.id.widget_focus_dial)
        time = inner.findViewById(R.id.widget_focus_time)
        phase = inner.findViewById(R.id.widget_focus_phase)
        toggleIcon = inner.findViewById(R.id.widget_focus_toggle_icon)
        toggle = inner.findViewById(R.id.widget_focus_toggle)
        inner.setPadding(dp(14), dp(14), dp(14), dp(14))
        val ring = inner.findViewById<View>(R.id.widget_focus_ring_box)
        ring.layoutParams.apply { width = dp(PILL_DIAL_DP); height = dp(PILL_DIAL_DP) }
        (inner.findViewById<View>(R.id.widget_focus_detail).parent as View).visibility = View.GONE
        ((toggle.parent as View).layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        capText(time, 26f)
        capText(phase, 11f)
        // Rangée basse (92 dp) : le cadran suit la hauteur réelle, le temps avec lui, la phase part.
        inner.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val side = (bottom - top - inner.paddingTop - inner.paddingBottom).coerceIn(dp(48), dp(PILL_DIAL_DP))
            if (ring.layoutParams.height == side) return@addOnLayoutChangeListener
            ring.layoutParams = ring.layoutParams.apply { width = side; height = side }
            setSp(time, 26f * side / dp(PILL_DIAL_DP))
            phase.visibility = if (side < dp(LOW_DIAL_DP)) View.GONE else View.VISIBLE
            inner.post { inner.requestLayout() }
        }
        return inner
    }

    /**
     * Ligne ou colonne : le temps, la bande de graduations qui prend la place restante, le bouton. Ligne basse (la rangée
     * de 92 dp) : temps plus petit, sans la phase. Pas d'ambiances : en colonne elles écrasaient la bande.
     */
    private fun strip(vertical: Boolean): View {
        val box = LinearLayout(context).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            background = HomeCard.background(context)
            val pad = dp(if (vertical) 20 else 24)
            setPadding(pad, dp(12), pad, dp(12))
            isClickable = true
        }
        time = displayText()
        phase = label(13f, R.attr.c_text_dim).apply { maxLines = 1 }
        if (vertical) for (t in listOf(time, phase)) t.gravity = Gravity.CENTER_HORIZONTAL
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.START
            if (vertical) addView(TitleView(context).apply { setText(R.string.widget_focus); gravity = Gravity.CENTER_HORIZONTAL }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            addView(time)
            addView(phase)
        }
        box.addView(texts)
        val strip = FocusBand(context, vertical, segmented = lcd)
        band = strip
        box.addView(
            strip,
            if (vertical) {
                LinearLayout.LayoutParams(dp(BAND_DP), 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(16) }
            } else {
                LinearLayout.LayoutParams(0, dp(BAND_DP), 1f).apply { marginStart = dp(24); marginEnd = dp(24) }
            },
        )
        box.addView(toggleButton(56))
        // La rangée basse de l'Accueil (92 dp) n'a pas la place du temps en grand et de la phase dessous.
        box.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (vertical || bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val low = bottom - top < dp(LOW_ROW_DP)
            setSp(time, if (low) 34f else 44f)
            phase.visibility = if (low) View.GONE else View.VISIBLE
        }
        setSp(time, if (vertical) 40f else 44f)
        return box
    }

    /**
     * Bloc, Grand, Plein écran : le cadran à la plus grande taille qui tient, le temps à sa mesure, les ambiances (en 2 × 2
     * elles écraseraient le cadran : seule celle qui joue est dite) ; Grand et Plein écran disent aussi les séances finies.
     * Plus large que haut (3 × 2, 4 × 2, 4 × 3) : cadran à gauche, le reste à droite.
     */
    private fun big(): View {
        val wide = placed.size.w > placed.size.h
        val noises = wide || placed.size.h >= 3
        val extra = family != ShapeFamily.BLOCK
        val tile = LinearLayout(context).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = HomeCard.background(context)
            setPadding(dp(24), dp(20), dp(24), dp(20))
            isClickable = true
        }
        val box = DialBox(context)
        dial = box.dial
        time = box.time
        phase = box.phase
        // Le cadran, carré, centré dans la place qui lui revient.
        val ring = FrameLayout(context).apply { addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)) }
        val side = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TitleView(context).apply { setText(R.string.widget_focus) }
        // En colonne, le bouton est centré sous le cadran ; à côté (large), il ouvre la colonne de droite.
        val controls = LinearLayout(context).apply {
            gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER
            addView(toggleButton(if (family == ShapeFamily.FULL) 72 else 56))
            addView(
                label(14f, R.attr.c_text_faint).apply { maxLines = 1 }.also { detail = it },
                if (wide) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) } else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) },
            )
        }
        val done = label(14f, R.attr.c_text_dim).also { sessions = it }
        if (wide) {
            tile.addView(ring, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            side.addView(title)
            if (extra) side.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            side.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
            side.addView(noiseRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            side.addView(controls)
            tile.addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f).apply { marginStart = dp(24) })
        } else {
            val head = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (extra) addView(done)
            }
            tile.addView(head)
            tile.addView(ring, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8); bottomMargin = dp(12) })
            if (noises) tile.addView(noiseRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            tile.addView(controls)
        }
        return tile
    }

    private fun displayText() = TextView(context).apply {
        typeface = themeFont(context, R.attr.c_font_display)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
        letterSpacing = -0.02f
        setTextColor(color(R.attr.c_text))
        maxLines = 1
    }

    private fun toggleButton(size: Int): View {
        toggle = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.console_round_bg)
            isClickable = true
            isFocusable = true
        }
        toggleIcon = ImageView(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.attr.c_text))
        }
        (toggle as FrameLayout).addView(toggleIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        toggle.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        return toggle
    }

    /** Une puce par ambiance, en rangée à largeurs égales (toucher celle qui joue l'arrête). */
    private fun noiseRow() = LinearLayout(context).apply {
        for (kind in NoisePlayer.Kind.entries) {
            // Largeurs égales imposées par la rangée : peu de marge, une ligne (colonnes étroites de Spider-Verse).
            addView(noiseChip(kind).apply { setPaddingRelative(dp(4), 0, dp(4), 0) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
    }

    private fun noiseChip(kind: NoisePlayer.Kind) = Button(context, null, 0, R.style.Chip).apply {
        setText(kind.label)
        maxLines = 1
        setOnClickListener { if (NoisePlayer.kind == kind) NoisePlayer.stop() else NoisePlayer.play(kind) }
        noiseChips[kind] = this
    }

    /** Police agrandie (Accessibilité) : le texte ne dépasse pas sa taille à 1,0 ([maxSp]), celle qui tient dans le cadran. */
    private fun capText(text: TextView, maxSp: Float) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(text.textSize, maxSp * context.resources.displayMetrics.density))
    }

    private fun setSp(text: TextView, sp: Float) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        capText(text, sp)
    }

    override fun setActive(active: Boolean) {
        if (active) {
            FocusTimer.observe(focusListener)
            NoisePlayer.observe(noiseListener)
        } else {
            FocusTimer.unobserve(focusListener)
            NoisePlayer.unobserve(noiseListener)
        }
        shown = false
        render()
    }

    override fun onMinute() = render()

    private fun render() {
        val state = preview?.state ?: FocusTimer.state
        val noise = if (preview != null) preview?.noise else NoisePlayer.kind?.takeIf { NoisePlayer.playing }
        val focus = state.phase == FocusTimer.Phase.FOCUS
        val started = state.running || state.remainingMs < state.durationMs
        time.text = FocusTimer.format(state.remainingMs)
        Look.countdown(time, state.remainingMs, state.running)
        phase.setText(
            when {
                !started -> R.string.focus_ready
                !state.running -> R.string.focus_paused
                focus -> R.string.focus_phase_work
                else -> R.string.focus_phase_break
            },
        )
        val hue = color(if (focus) R.attr.c_accent else R.attr.c_ok)
        val lit = FocusTicks.lit(state.remainingMs, state.durationMs, state.running)
        dial?.let {
            it.color = hue
            // Première image sans cascade : l'écran arrive déjà dans le bon état.
            it.show(lit, animate = shown)
        }
        band?.show(lit, hue)
        shown = true
        detail?.let {
            it.text = noise?.let { kind -> context.getString(R.string.noise_playing, context.getString(kind.label)) } ?: ""
            // Vide, il ne décale pas le bouton centré.
            it.visibility = if (it.text.isEmpty()) View.GONE else View.VISIBLE
        }
        sessions?.let {
            it.text = context.resources.getQuantityString(R.plurals.focus_sessions_done, state.completed, state.completed)
            it.visibility = if (state.completed > 0) View.VISIBLE else View.INVISIBLE
        }
        for ((kind, chip) in noiseChips) chip.isSelected = noise == kind
        Look.swapIcon(toggleIcon, if (state.running) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = context.getString(if (state.running) R.string.focus_pause else R.string.focus_start)
    }

    /**
     * Le cadran, carré, à la plus grande taille que lui laisse son parent ; le temps et la phase au centre, à sa mesure
     * (le texte suit le cadran, pas la police système : il ne doit jamais en sortir).
     */
    private class DialBox(context: Context) : FrameLayout(context) {
        val dial = FocusDial(context)
        val time = TextView(context)
        val phase = TextView(context)
        private var sized = -1

        init {
            addView(dial)
            time.typeface = themeFont(context, R.attr.c_font_display)
            time.fontFeatureSettings = "tnum"
            time.includeFontPadding = false
            time.letterSpacing = -0.02f
            time.setTextColor(app.mate.ui.themeColor(context, R.attr.c_text))
            phase.setTextColor(app.mate.ui.themeColor(context, R.attr.c_text_dim))
            phase.maxLines = 1
            phase.gravity = Gravity.CENTER_HORIZONTAL
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(time)
                addView(phase)
            }
            addView(texts, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val side = minOf(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            // Tailles posées avant de mesurer les enfants : posées après (onSizeChanged), le temps resterait coupé à
            // la largeur de l'ancienne taille. Même proportion que le grand cadran d'origine : 40 sp dans 176 dp.
            if (side != sized) {
                sized = side
                time.setTextSize(TypedValue.COMPLEX_UNIT_PX, side * TIME_RATIO)
                phase.setTextSize(TypedValue.COMPLEX_UNIT_PX, maxOf(side * PHASE_RATIO, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)))
                phase.maxWidth = (side * 0.6f).toInt()
                phase.ellipsize = TextUtils.TruncateAt.END
            }
            val exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
            super.onMeasure(exact, exact)
        }
    }

    private companion object {
        const val BAND_DP = 40
        const val PILL_DIAL_DP = 104
        const val LOW_DIAL_DP = 80
        const val LOW_ROW_DP = 120
        const val TIME_RATIO = 40f / 176f
        const val PHASE_RATIO = 13f / 176f
    }
}
