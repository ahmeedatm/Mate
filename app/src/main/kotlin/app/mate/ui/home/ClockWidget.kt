package app.mate.ui.home

import android.content.Context
import android.graphics.Paint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import app.mate.R
import app.mate.ui.themeFont
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Exemple du catalogue : l'Horloge montre l'heure qu'il est, elle n'a pas de données à inventer. */
object ClockSample : WidgetSample

/**
 * Horloge (direction Cadran, #180), une mise en page par famille ([ClockFace]) : Pastille et Colonne = cadran des
 * minutes autour de l'heure ; Ligne = l'heure, la salutation et la date à côté ; Bloc et Grand = salutation, heure en
 * grand, date (Grand étroit : heures au-dessus des minutes) ; Plein écran = en plus les secondes. Les tailles suivent
 * la taille réelle de la carte ([fit]), pas la forme.
 */
class ClockWidget(context: Context, host: HomeHost, placed: Placed) : HomeWidget(context, host, placed) {
    private val greeting = label(20f, R.attr.c_text_dim).apply { oneLine() }
    private val clock = clockText("HH:mm")
    private val hours = clockText("HH")
    private val minutes = clockText("mm")
    private val seconds = clockText("ss").apply { setTextColor(color(R.attr.c_text_dim)) }
    private val date = label(22f, R.attr.c_text_dim)
    private val dial = ClockDial(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val ring = FrameLayout(context)
    private var face = ClockFace.of(placed.size)
    override val view = FrameLayout(context).asCard(24)

    init {
        // La hauteur d'une rangée dépend du profil de l'Accueil (RowProfile) : les tailles se recalculent à chaque changement.
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) view.post(::fit)
        }
    }

    override fun applySize() {
        face = ClockFace.of(placed.size)
        for (part in listOf(greeting, clock, hours, minutes, seconds, date, dial, ring)) (part.parent as? ViewGroup)?.removeView(part)
        ring.removeAllViews()
        view.removeAllViews()
        val pad = dp(if (family == ShapeFamily.PILL) PILL_PAD_DP else PAD_DP)
        view.setPadding(pad, pad, pad, pad)
        val content = when (family) {
            ShapeFamily.PILL, ShapeFamily.COLUMN -> dialLayout()
            ShapeFamily.ROW -> rowLayout()
            else -> stackLayout()
        }
        view.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        onMinute()
        view.post(::fit)
    }

    /** Cadran avec l'heure dedans ; Pastille : la date courte aussi, Colonne : la date longue dessous. */
    private fun dialLayout() = column(Gravity.CENTER).apply {
        ring.addView(dial, FrameLayout.LayoutParams(MATCH, MATCH))
        val inside = column(Gravity.CENTER).apply {
            addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
            if (face.dateInDial) addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        ring.addView(inside, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        addView(ring, LinearLayout.LayoutParams(0, 0))
        date.gravity = Gravity.CENTER
        date.maxLines = if (face.dateInDial) 1 else 2
        if (!face.dateInDial) addView(date, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(GAP_DP) })
    }

    /** L'heure à gauche, la salutation et la date à côté. */
    private fun rowLayout() = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
        val side = column(Gravity.CENTER_VERTICAL).apply {
            addView(greeting)
            addView(date)
        }
        date.oneLine()
        addView(side, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(SIDE_GAP_DP) })
    }

    /** Salutation, heure (empilée ou non, secondes en plein écran), date ; Bloc à gauche, Grand et Plein écran centrés. */
    private fun stackLayout() = column(if (family == ShapeFamily.BLOCK) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER).apply {
        addView(greeting, LinearLayout.LayoutParams(WRAP, WRAP))
        if (face.stacked) {
            addView(hours, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(minutes, LinearLayout.LayoutParams(WRAP, WRAP))
        } else {
            // LinearLayout aligne les lignes de base : les secondes s'assoient sur la même ligne que l'heure.
            addView(
                LinearLayout(context).apply {
                    addView(clock)
                    if (face.seconds) addView(seconds, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(GAP_DP) })
                },
                LinearLayout.LayoutParams(WRAP, WRAP),
            )
        }
        date.oneLine()
        addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** Tailles calculées sur la place réelle (padding déduit) : rien ne dépasse, rien ne flotte dans le vide. */
    private fun fit() {
        val w = (view.width - view.paddingLeft - view.paddingRight).toFloat()
        val h = (view.height - view.paddingTop - view.paddingBottom).toFloat()
        if (w <= 0 || h <= 0) return
        when (family) {
            ShapeFamily.PILL, ShapeFamily.COLUMN -> fitDial(w, h)
            ShapeFamily.ROW -> fitRow(w, h)
            else -> fitStack(w, h)
        }
    }

    private fun fitDial(w: Float, h: Float) {
        val below = if (face.dateInDial) 0f else sp(DATE_BELOW_SP).coerceAtMost(w * DATE_BELOW_PART).coerceAtLeast(sp(MIN_SP))
        if (!face.dateInDial) date.setTextSize(TypedValue.COMPLEX_UNIT_PX, below)
        val reserve = if (face.dateInDial) 0f else below * LINE * 2 + dp(GAP_DP)
        val size = minOf(w, h - reserve).toInt().coerceAtLeast(0)
        // Rangée fine : un cadran de cette taille ne se lit plus, l'heure seule prend la place.
        val bare = size < dp(MIN_DIAL_DP)
        dial.visibility = if (bare) View.GONE else View.VISIBLE
        ring.layoutParams = (ring.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (bare) w.toInt() else size
            height = if (bare) (h - reserve).toInt().coerceAtLeast(0) else size
        }
        val text = if (bare) minOf(byWidth(clock, SAMPLE, w), (h - reserve) / LINE) else byWidth(clock, SAMPLE, size * DIAL_TEXT_PART)
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, text)
        if (face.dateInDial) fitDialDate(size, bare)
        ring.requestLayout()
    }

    /** Date dans le cadran : jamais sous [MIN_SP] (illisible de loin) ; si elle n'y tient pas, pas de date. */
    private fun fitDialDate(size: Int, bare: Boolean) {
        val px = (size * DIAL_DATE_PART).coerceAtLeast(sp(MIN_SP))
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        val fits = date.paint.measureText(date.text.toString()) <= size * DIAL_TEXT_PART
        date.visibility = if (bare || !fits) View.GONE else View.VISIBLE
    }

    private fun fitRow(w: Float, h: Float) {
        // Rangée basse (la fine de l'Accueil fait 92 dp) : marges réduites, sans salutation.
        val short = view.height < dp(SHORT_DP)
        val padV = dp(if (short) SHORT_PAD_DP else PAD_DP)
        if (view.paddingTop != padV) {
            view.setPadding(view.paddingLeft, padV, view.paddingRight, padV)
            view.post(::fit)
            return
        }
        greeting.visibility = if (short) View.GONE else View.VISIBLE
        val px = minOf(h / LINE, byWidth(clock, SAMPLE, w * ROW_CLOCK_PART))
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, (px * GREETING_PART).coerceIn(sp(MIN_SP), sp(GREETING_MAX_SP)))
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, (px * DATE_PART).coerceIn(sp(MIN_SP), sp(DATE_MAX_SP)))
    }

    private fun fitStack(w: Float, h: Float) {
        val lines = if (face.stacked) 2 else 1
        val wide = if (face.stacked) byWidth(hours, "00", w) else byWidth(clock, SAMPLE, w / if (face.seconds) 1f + SECONDS_PART * 0.5f else 1f)
        var px = minOf(h / (lines * LINE), wide)
        // Les marges des polices ne se devinent pas : mesurer le tout et rapetisser l'heure jusqu'à ce qu'il tienne.
        repeat(FIT_PASSES) {
            setStackSizes(px)
            val content = view.getChildAt(0) ?: return
            content.measure(View.MeasureSpec.makeMeasureSpec(w.toInt(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            if (content.measuredHeight <= h) return
            px = (px * h / content.measuredHeight * FIT_MARGIN).coerceAtLeast(sp(MIN_SP))
        }
    }

    private fun setStackSizes(px: Float) {
        for (part in listOf(clock, hours, minutes)) part.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        seconds.setTextSize(TypedValue.COMPLEX_UNIT_PX, px * SECONDS_PART)
        // Taille fixe : chaque seconde, le texte change sans remesurer tout l'Accueil (seulement la vue redessinée).
        seconds.layoutParams?.let { lp ->
            lp.width = kotlin.math.ceil(seconds.paint.measureText(SECONDS_SAMPLE)).toInt() + seconds.paddingLeft + seconds.paddingRight
            lp.height = seconds.lineHeight
            seconds.layoutParams = lp
        }
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_PX, (px * GREETING_PART).coerceIn(sp(MIN_SP), sp(GREETING_MAX_SP)))
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, (px * DATE_PART).coerceIn(sp(MIN_SP), sp(DATE_MAX_SP)))
    }

    override fun onMinute() {
        val now = Calendar.getInstance()
        val hello = context.getString(when (now.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        })
        if (greeting.text.toString() != hello) greeting.text = hello
        // Dans le cadran de la Pastille, date courte (« Mer. 5 ») ; ailleurs, la date entière.
        val pattern = if (face.dateInDial) "EEE d" else "EEEE d MMMM"
        val text = SimpleDateFormat(pattern, Locale.FRANCE).format(Date()).replaceFirstChar { it.uppercase() }
        // Même texte : pas de nouvelle mise en page (une fois par minute sur la tablette).
        if (date.text.toString() != text) date.text = text
        dial.show(ClockFace.lit(now.get(Calendar.MINUTE)))
    }

    /** L'heure est l'heure : l'aperçu du catalogue montre l'heure réelle, sans réseau ni minuterie. */
    override fun showPreview(sample: WidgetSample) = onMinute()

    private fun clockText(pattern: String) = TextClock(context).apply {
        format12Hour = pattern
        format24Hour = pattern
        includeFontPadding = false
        isSingleLine = true
        gravity = Gravity.CENTER
        setTextColor(color(R.attr.c_text))
        typeface = themeFont(context, R.attr.c_font_light)
    }

    private fun column(gravity: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    private fun TextView.oneLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Taille de texte (px) pour que [sample] tienne dans [width] px avec la police de [view]. */
    private fun byWidth(view: TextView, sample: String, width: Float): Float {
        val paint = Paint(view.paint).apply { textSize = MEASURE_PX }
        return width / paint.measureText(sample) * MEASURE_PX
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Heure de référence pour mesurer la largeur : tous les chiffres de Figtree ont la même chasse. */
        const val SAMPLE = "00:00"
        const val SECONDS_SAMPLE = "00"
        const val MEASURE_PX = 100f

        /** Hauteur d'une ligne de texte, en tailles de texte (chiffres sans marge de police). */
        const val LINE = 1.15f

        const val PAD_DP = 24
        const val PILL_PAD_DP = 12
        const val SHORT_PAD_DP = 8
        const val GAP_DP = 8
        const val SIDE_GAP_DP = 24

        /** En dessous de cette hauteur, la rangée est « basse » (la fine de l'Accueil fait 92 dp). */
        const val SHORT_DP = 130

        /** En dessous de ce diamètre, pas de cadran : l'heure seule. */
        const val MIN_DIAL_DP = 110

        /** Ajustement de l'heure à la hauteur : passes au plus, et marge prise à chaque passe. */
        const val FIT_PASSES = 3
        const val FIT_MARGIN = 0.97f

        /** Largeur de l'heure dans le cadran, en part de son diamètre (elle tient à l'intérieur des graduations). */
        const val DIAL_TEXT_PART = 0.56f
        const val DIAL_DATE_PART = 0.085f

        /** Date sous le cadran (Colonne) : sa taille, bornée à une part de la largeur pour les colonnes étroites. */
        const val DATE_BELOW_SP = 18f
        const val DATE_BELOW_PART = 0.09f

        /** Ligne : part de la largeur laissée à l'heure, le reste à la salutation et à la date. */
        const val ROW_CLOCK_PART = 0.5f

        /** Salutation et date, en part de la taille de l'heure, bornées entre [MIN_SP] et leur maximum. */
        const val GREETING_PART = 0.18f
        const val DATE_PART = 0.22f
        const val SECONDS_PART = 0.4f
        const val MIN_SP = 14f
        const val GREETING_MAX_SP = 24f
        const val DATE_MAX_SP = 30f
    }
}
