package app.mate.ui.home

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.ui.dp

/** Un passage prêt à dessiner : picto, destination, attente et « puis N min » (null si pas de passage suivant). */
class BoardItem(
    val line: TransportLines.Line?,
    val picto: PictoSpec,
    val destination: String,
    val wait: Transport.Wait,
    val thenMinutes: Int?,
    val approach: Float,
)

/** Tailles d'une famille de formes : [picto] et [gauge] (jauge d'approche) en dp de haut, [wait], [dest] et [head] (arrêt) en sp. */
class BoardLook(val picto: Int, val wait: Float, val dest: Float, val head: Float, val gauge: Int)

/**
 * Les rangées de l'écran, une mise en page par famille : [pill], [side] (Ligne), [column], [wide] (Bloc et plus). Les
 * étiquettes qui clignotent sont déposées dans [blinking] ; chaque rangée porte sa phrase pour TalkBack. Avec `gauge`,
 * la rangée finit par la jauge d'approche du passage, à la couleur de sa ligne.
 */
class BoardRows(private val context: Context, private val look: BoardLook, private val blinking: MutableList<View>) {
    /** Pastille : picto, attente, destination en petit, étagés sur la hauteur. */
    fun pill(item: BoardItem): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(picto(item))
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(wait(item))
        addView(gauge(item), gaugeParams())
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(dest(item, look.dest))
        contentDescription = describe(item)
    }

    /** Ligne (2 à 4 × 1) : picto, destination avec « puis N min » dessous, attente à droite. */
    fun side(item: BoardItem, gauge: Boolean): View = withGauge(item, gauge) {
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(picto(item))
            val text = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(dest(item, look.dest))
                item.thenMinutes?.let { addView(lcdText(context, THEN_SP).apply { alpha = THEN_ALPHA; text = context.getString(R.string.widget_transport_then, it); maxLines = 1 }) }
            }
            addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(GAP); marginEnd = context.dp(GAP) })
            addView(wait(item))
            contentDescription = describe(item)
        }
    }

    /** Colonne (1 × 2 à 4) : picto et attente sur une ligne, destination dessous. */
    fun column(item: BoardItem, gauge: Boolean): View = withGauge(item, gauge) {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(picto(item))
                    addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                    addView(wait(item))
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
            )
            addView(dest(item, look.dest), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(4) })
            contentDescription = describe(item)
        }
    }

    /** Bloc, Grand, Plein écran : picto | destination | attente. */
    fun wide(item: BoardItem, gauge: Boolean): View = withGauge(item, gauge) {
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(picto(item))
            addView(dest(item, look.dest), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(GAP); marginEnd = context.dp(GAP) })
            addView(wait(item))
            contentDescription = describe(item)
        }
    }

    private val words = TransportSpeech.words(context)

    /** [row] suivie de la jauge d'approche de [item] si [gauge] ; la rangée seule sinon. */
    private fun withGauge(item: BoardItem, gauge: Boolean, row: () -> View): View {
        if (!gauge) return row()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val inner = row()
            addView(inner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(gauge(item), gaugeParams())
            contentDescription = inner.contentDescription
            inner.contentDescription = null
        }
    }

    private fun gauge(item: BoardItem) = ApproachBar(context, item.picto.barColor).apply {
        fraction = item.approach
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun gaugeParams() = LinearLayout.LayoutParams(MATCH_PARENT, context.dp(look.gauge)).apply { topMargin = context.dp(GAUGE_GAP) }

    private fun picto(item: BoardItem) = LinePicto(context).apply { set(item.picto, item.line?.name.orEmpty(), look.picto) }

    private fun dest(item: BoardItem, sp: Float) = lcdText(context, sp).apply {
        text = item.destination
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** « À quai » fixe, « À l'approche » (2 lignes au plus, taille réduite), ou le nombre avec « min » plus petit. */
    private fun wait(item: BoardItem): TextView = lcdText(context, look.wait).apply {
        gravity = Gravity.END
        when (item.wait.kind) {
            Transport.Wait.Kind.AT_STOP -> word(R.string.widget_transport_at_stop)
            Transport.Wait.Kind.APPROACHING -> word(R.string.widget_transport_approaching)
            Transport.Wait.Kind.NUMBER -> {
                val unit = " " + context.getString(R.string.widget_transport_unit)
                val shown = SpannableString(item.wait.minutes.toString() + unit)
                val scale = maxOf(UNIT_SCALE, MIN_UNIT_SP / look.wait)
                shown.setSpan(RelativeSizeSpan(scale), shown.length - unit.length, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = shown
                maxLines = 1
            }
        }
        if (item.wait.blink) blinking += this
    }

    private fun TextView.word(res: Int) {
        setText(res)
        textSize = look.wait * WORD_SCALE
        maxLines = 2
    }

    private fun describe(item: BoardItem) = TransportSpeech.row(words, item.line, item.destination, item.wait)

    private companion object {
        const val GAP = 12
        const val GAUGE_GAP = 6
        const val THEN_SP = 13f
        const val THEN_ALPHA = 0.85f
        const val UNIT_SCALE = 0.38f
        const val MIN_UNIT_SP = 13f
        const val WORD_SCALE = 0.5f
    }
}
