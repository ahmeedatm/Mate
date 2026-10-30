package app.mate.ui.home

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.mate.R
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
 * ([HeroComplications]). Rien ne bouge entre deux minutes.
 */
class HeroWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    override val view = LayoutInflater.from(context).inflate(R.layout.widget_hero, FrameLayout(context), false) as HeroView
    private val clock = view.findViewById<RollingText>(R.id.hero_clock)
    private val date = view.findViewById<TextView>(R.id.hero_date)
    private val next = view.findViewById<View>(R.id.hero_next)
    private val nextDown = view.findViewById<TextView>(R.id.hero_next_down)
    private val nextPrefix = view.findViewById<TextView>(R.id.hero_next_prefix)
    private val nextTitle = view.findViewById<TextView>(R.id.hero_next_title)
    private val nextWhen = view.findViewById<TextView>(R.id.hero_next_when)
    private val nextIn = view.findViewById<View>(R.id.hero_next_in)
    private val nextCount = view.findViewById<RollingText>(R.id.hero_next_count)
    private val compl = HeroComplications(context, host, placed, view)

    private val dateFormat = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE)
    private var active = false

    override val hasSettings get() = true

    init {
        val sp = { value: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics) }
        clock.typeface = themeFont(context, R.attr.c_font_display)
        clock.color = color(R.attr.c_text)
        nextCount.typeface = themeFont(context, R.attr.c_font_medium)
        nextCount.cellEm = 0.58f
        nextCount.minCells = 2
        nextCount.color = color(R.attr.c_accent)
        nextCount.setSize(sp(17f), dp(24))
        render(animate = false)
    }

    override fun setActive(active: Boolean) {
        this.active = active
        compl.setActive(active)
        if (active) render(animate = false)
    }

    override fun onMinute() = render(animate = active)

    override fun onCalendar() = renderNext(animate = false)

    private fun render(animate: Boolean) {
        val now = System.currentTimeMillis()
        clock.set(SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(now)), animate)
        date.text = dateFormat.format(Date(now)).replaceFirstChar { it.uppercase() }
        renderNext(animate)
        compl.render(animate)
    }

    /**
     * « Ensuite <titre> dans N min » : le compteur bascule chaque minute ; plus tard ou demain, le moment en texte.
     * Ordinateur injoignable : « <nom> ne répond pas depuis HH:MM » (sans l'heure si jamais vu en ligne) à la place, les rendez-vous peuvent dater.
     */
    private fun renderNext(animate: Boolean) {
        val down = host.unreachable
        val line = if (down == null) nextLine(host.events, System.currentTimeMillis(), host.chosenId) else null
        view.nextWanted = down != null || line != null
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

    override suspend fun load() = compl.load()

    override fun showSettings() = compl.showSettings()
}
