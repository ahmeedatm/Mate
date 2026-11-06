package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.mate.R
import app.mate.net.CalendarEvent
import app.mate.ui.RollingText
import app.mate.ui.themeFont
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Héros de l'Accueil (DA Console douce) : taille de l'heure selon la place, sans fond. Pleine hauteur (une ligne de l'Accueil
 * profil à lignes inégales) : l'heure à 184 px, la date, « Ensuite … » et les complications. Plus bas (panneau document
 * ouvert) : l'heure et la date, la météo et l'eau seulement s'il y a la largeur.
 */
class HeroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private lateinit var clock: RollingText
    private lateinit var date: View
    private lateinit var next: View
    private lateinit var compl: View

    /** Il y a un prochain rendez-vous à dire : « Ensuite … » apparaît si la hauteur le permet. */
    var nextWanted = false
        set(value) { if (field != value) { field = value; requestLayout() } }

    override fun onFinishInflate() {
        super.onFinishInflate()
        clock = findViewById(R.id.hero_clock)
        date = findViewById(R.id.hero_date)
        next = findViewById(R.id.hero_next)
        compl = findViewById(R.id.hero_compl)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        val full = h >= FULL_HEIGHT * density
        show(next, full && nextWanted)
        show(compl, w >= COMPL_WIDTH * density)
        // L'heure prend ce que la date et « Ensuite » (hauteur de leur texte, police agrandie comprise) laissent dans la
        // ligne ; sur une ligne haute elle déborde de 12 dp, sous 220 dp elle rend 2 dp. La ligne de l'heure vaut 176 pour 184 de corps.
        val below = blockHeight(date) + if (next.isVisible) blockHeight(next) else 0
        val room = h + (if (full) OVERHANG else -2) * density - below
        val maxClock = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MAX_CLOCK, resources.displayMetrics)
        val size = minOf(maxClock, 0.45f * w, room / LINE_RATIO)
        clock.setSize(size, (size * LINE_RATIO).roundToInt(), size.roundToInt())
        // Les textes ont le droit de déborder de la ligne (la mesure normale les coupe à sa hauteur) : la vue garde la sienne.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h + (2 * OVERHANG * density).roundToInt(), MeasureSpec.AT_MOST))
        setMeasuredDimension(measuredWidth, h)
    }

    /** Hauteur d'un bloc sous l'heure, marge du haut comprise. */
    private fun blockHeight(view: View): Int {
        view.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return view.measuredHeight + (view.layoutParams as MarginLayoutParams).topMargin
    }

    private fun show(view: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != target) view.visibility = target
    }

    private companion object {
        const val FULL_HEIGHT = 220
        const val OVERHANG = 12
        const val MAX_CLOCK = 184f
        const val COMPL_WIDTH = 760
        const val LINE_RATIO = 176f / 184f
    }
}

/**
 * Héros : heure qui bascule chiffre par chiffre à chaque minute, date, « Ensuite … dans N min », météo et eau
 * ([HeroComplications]). Rien ne bouge entre deux minutes. Une mise en page par famille ([HeroFace], direction Cadran,
 * #180) : la Ligne garde le bandeau ([HeroView]) ; ailleurs les mêmes pièces sont rangées autrement, avec le cadran des
 * minutes (Colonne), la journée en ligne de temps et le prochain rendez-vous dans son anneau (Plein écran).
 */
class HeroWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = FrameLayout(context)
    private var face = HeroFace.of(placed.size)

    /** Le bandeau d'avant, en Ligne seulement : il dimensionne lui-même l'heure. */
    private var band: HeroView? = null
    private lateinit var clock: RollingText
    private lateinit var date: TextView
    private lateinit var next: View
    private lateinit var nextDown: TextView
    private lateinit var nextPrefix: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nextWhen: TextView
    private lateinit var nextIn: View
    private lateinit var nextCount: RollingText
    private lateinit var complView: View
    private var compl: HeroComplications? = null

    private val dial = ClockDial(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val dialFrame = FrameLayout(context)
    private val track = DayTrack(context)
    private val ring = CountdownRing(context)
    private val ringTitle = label(28f).apply {
        typeface = themeFont(context, R.attr.c_font_medium)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val ringWhen = label(18f, R.attr.c_text_dim).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
    private val ringRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val minuteFormat = SimpleDateFormat("m", Locale.FRANCE)
    private var active = false

    /** Aperçu du catalogue : la journée d'exemple à la place de l'agenda de l'ordinateur. */
    private var sample: List<CalendarEvent>? = null
    private val events get() = sample ?: host.events

    override val hasSettings get() = true

    init {
        view.clipChildren = false
        view.clipToPadding = false
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) view.post(::fit)
        }
        applySize()
    }

    override fun applySize() {
        face = HeroFace.of(placed.size)
        view.removeAllViews()
        val root = LayoutInflater.from(context).inflate(R.layout.widget_hero, view, false) as HeroView
        clock = root.findViewById(R.id.hero_clock)
        date = root.findViewById(R.id.hero_date)
        next = root.findViewById(R.id.hero_next)
        nextDown = root.findViewById(R.id.hero_next_down)
        nextPrefix = root.findViewById(R.id.hero_next_prefix)
        nextTitle = root.findViewById(R.id.hero_next_title)
        nextWhen = root.findViewById(R.id.hero_next_when)
        nextIn = root.findViewById(R.id.hero_next_in)
        nextCount = root.findViewById(R.id.hero_next_count)
        complView = root.findViewById(R.id.hero_compl)
        styleClock()
        if (face.band) {
            band = root
            view.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            band = null
            view.addView(stack(), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        compl?.setActive(false)
        // Météo et eau seulement là où elles se voient : pas de lecture de position ni de réseau pour rien.
        compl = if (face.band || face.compl) HeroComplications(context, host, placed, complView, compl).also { it.setActive(active) } else null
        render(animate = false)
        view.post(::fit)
    }

    private fun styleClock() {
        val sp = { value: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics) }
        clock.typeface = themeFont(context, R.attr.c_font_display)
        clock.color = color(R.attr.c_text)
        nextCount.typeface = themeFont(context, R.attr.c_font_medium)
        nextCount.cellEm = 0.58f
        nextCount.minCells = 2
        nextCount.color = color(R.attr.c_accent)
        nextCount.setSize(sp(17f), dp(24))
    }

    /** Les pièces du bandeau, rangées pour la forme : de haut en bas, centrées en Pastille et en Colonne. */
    private fun stack(): View {
        for (part in listOf(clock, date, next, complView, dial, dialFrame, track, ring, ringTitle, ringWhen, ringRow)) (part.parent as? ViewGroup)?.removeView(part)
        dialFrame.removeAllViews()
        ringRow.removeAllViews()
        val centered = family == ShapeFamily.PILL || face.dial
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        }
        if (face.dial) {
            dialFrame.addView(dial, FrameLayout.LayoutParams(MATCH, MATCH))
            dialFrame.addView(clock, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            column.addView(dialFrame, LinearLayout.LayoutParams(0, 0))
        } else {
            column.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        date.gravity = if (centered) Gravity.CENTER else Gravity.START
        if (face.date) column.addView(date, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(GAP_DP) })
        if (face.next) column.addView(next, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(GAP_DP) })
        if (face.ring) {
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(15f, R.attr.c_text_faint).apply { setText(R.string.next_prefix) })
            texts.addView(ringTitle)
            texts.addView(ringWhen)
            ringRow.addView(ring, LinearLayout.LayoutParams(0, 0).apply { marginEnd = dp(GAP_DP * 2) })
            ringRow.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            column.addView(ringRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(GAP_DP * 3) })
        }
        track.across = true
        track.titles = face.trackTitles
        if (face.track) {
            val trackH = dp(if (face.trackTitles) TRACK_TITLES_DP else TRACK_DP)
            column.addView(track, LinearLayout.LayoutParams(MATCH, trackH).apply { topMargin = dp(GAP_DP * 2) })
        }
        if (face.compl) {
            (complView as? LinearLayout)?.orientation = if (face.complDown) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            column.addView(complView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(GAP_DP * 3) })
        }
        return column
    }

    /** Hors bandeau : l'heure prend ce que les autres pièces laissent, en hauteur comme en largeur. */
    private fun fit() {
        if (face.band || view.width == 0) return
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val byWidth = w / (CLOCK_CELLS * clock.cellEm)
        if (face.dial) {
            val below = listOf(date, complView).filter { it.parent != null && it.visibility != View.GONE }.sumOf { measured(it) + dp(GAP_DP * 2) }
            val d = minOf(w, h - below).coerceAtLeast(0f).toInt()
            dialFrame.layoutParams = (dialFrame.layoutParams as LinearLayout.LayoutParams).apply { width = d; height = d }
            setClock(d * DIAL_TEXT_PART / (CLOCK_CELLS * clock.cellEm))
            return
        }
        if (face.ring) {
            val d = minOf(h * RING_PART, w * RING_W_PART).toInt()
            ring.layoutParams = (ring.layoutParams as LinearLayout.LayoutParams).apply { width = d; height = d }
            ringTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, (d * RING_TITLE_PART).coerceIn(sp(MIN_SP), sp(RING_TITLE_MAX_SP)))
        }
        val others = listOf(date, next, ringRow, track, complView).filter { it.parent != null && it.visibility != View.GONE }
        val reserve = others.sumOf { measured(it) + it.topMargin() }
        setClock(minOf((h - reserve) / LINE_RATIO, byWidth, sp(MAX_CLOCK_SP)).coerceAtLeast(sp(MIN_CLOCK_SP)))
    }

    /** Hors bandeau, personne ne mesure l'heure juste après : `setSize` ne relance pas la mise en page, on la demande. */
    private fun setClock(size: Float) {
        clock.setSize(size, (size * LINE_RATIO).roundToInt(), size.roundToInt())
        clock.requestLayout()
    }

    private fun measured(part: View): Int {
        val lp = part.layoutParams
        val wSpec = View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.AT_MOST)
        val hSpec = if (lp != null && lp.height > 0) View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        part.measure(wSpec, hSpec)
        return part.measuredHeight
    }

    private fun View.topMargin() = (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    override fun setActive(active: Boolean) {
        this.active = active
        compl?.setActive(active)
        if (active) render(animate = false)
    }

    override fun onMinute() = render(animate = active)

    override fun onCalendar() = renderNext(animate = false)

    private fun render(animate: Boolean) {
        val now = System.currentTimeMillis()
        clock.set(clockFormat.format(Date(now)), animate)
        val text = dateFormat.format(Date(now)).replaceFirstChar { it.uppercase() }
        if (date.text.toString() != text) date.text = text
        if (face.dial) dial.show(ClockFace.lit(minuteFormat.format(Date(now)).toInt()))
        renderNext(animate)
        compl?.render(animate)
    }

    /**
     * « Ensuite <titre> dans N min » : le compteur bascule chaque minute ; plus tard ou demain, le moment en texte.
     * Ordinateur injoignable : « <nom> ne répond pas depuis HH:MM » (sans l'heure si jamais vu en ligne) à la place, les rendez-vous peuvent dater.
     * Hors bandeau, la journée en ligne de temps et, en Plein écran, le prochain rendez-vous dans son anneau.
     */
    private fun renderNext(animate: Boolean) {
        val now = System.currentTimeMillis()
        val down = if (sample == null) host.unreachable else null
        if (face.track) track.show(events, now)
        renderRing(now, down)
        val line = if (down == null) nextLine(events, now, host.chosenId) else null
        band?.nextWanted = down != null || line != null
        if (band == null) {
            val shown = if (face.next && (down != null || line != null)) View.VISIBLE else View.GONE
            // « Ensuite » qui apparaît ou disparaît change la place de l'heure.
            if (next.visibility != shown) view.post(::fit)
            next.visibility = shown
        }
        nextDown.visibility = if (down != null) View.VISIBLE else View.GONE
        if (down != null) {
            val since = down.since()
            nextDown.text =
                if (since != null) context.getString(R.string.hero_down, down.name, since) else context.getString(R.string.hero_down_unknown, down.name)
            nextPrefix.visibility = View.GONE
            nextTitle.visibility = View.GONE
            nextWhen.visibility = View.GONE
            nextIn.visibility = View.GONE
            return
        }
        nextTitle.visibility = View.VISIBLE
        if (line == null) return
        nextPrefix.visibility = if (line is NextLine.Now) View.GONE else View.VISIBLE
        nextTitle.text = line.title
        if (line is NextLine.InMinutes) {
            nextWhen.visibility = View.GONE
            nextIn.visibility = View.VISIBLE
            nextCount.set(line.minutes.toString(), animate)
        } else {
            nextIn.visibility = View.GONE
            nextWhen.visibility = View.VISIBLE
            nextWhen.text = line.whenText(context)
            nextWhen.setTextColor(color(if (line is NextLine.Now) R.attr.c_accent else R.attr.c_text_dim))
        }
    }

    /** Plein écran : le prochain rendez-vous en grand, dans son anneau ; rien à dire (ou ordinateur muet) : pas d'anneau. */
    private fun renderRing(now: Long, down: Down?) {
        if (!face.ring) return
        val event = if (down == null) nextEvent(events, now, host.chosenId) else null
        val shown = if (event == null) View.GONE else View.VISIBLE
        if (ringRow.visibility != shown) view.post(::fit)
        ringRow.visibility = shown
        if (event == null) return
        ring.showNext(event, now)
        ringTitle.text = event.title
        val whenText = nextLine(event, now).whenText(context)
        ringWhen.text = if (event.location.isBlank()) whenText else "$whenText · ${event.location}"
    }

    override suspend fun load() {
        compl?.load()
    }

    override fun showSettings() {
        compl?.showSettings()
    }

    override fun showPreview(sample: WidgetSample) {
        if (sample !is CalendarSample) return
        this.sample = CalendarSample.events(System.currentTimeMillis(), context::getString)
        compl?.showSample(WeatherSample.paris())
        render(animate = false)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** « HH:mm » : cinq cases de [RollingText]. */
        const val CLOCK_CELLS = 5

        /** Hauteur de la ligne de l'heure, en tailles de texte (celle du bandeau). */
        const val LINE_RATIO = 176f / 184f
        const val MAX_CLOCK_SP = 184f
        const val MIN_CLOCK_SP = 28f
        const val MIN_SP = 15f

        const val GAP_DP = 6
        const val TRACK_DP = 28
        const val TRACK_TITLES_DP = 56

        /** Heure dans le cadran, en part de son diamètre. */
        const val DIAL_TEXT_PART = 0.56f

        /** Anneau du Plein écran : part de la hauteur et de la largeur ; son titre en part du diamètre. */
        const val RING_PART = 0.26f
        const val RING_W_PART = 0.3f
        const val RING_TITLE_PART = 0.18f
        const val RING_TITLE_MAX_SP = 34f
    }
}
