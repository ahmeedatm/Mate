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
)

/** Tailles d'une famille de formes : [picto] en dp de haut, [wait] et [dest] en sp. */
class BoardLook(val picto: Int, val wait: Float, val dest: Float, val badge: Int, val bandSp: Float)

/**
 * Les rangées de l'écran, une mise en page par famille : [pill], [side] (Ligne), [column], [wide] (Bloc et plus). Les
 * étiquettes qui clignotent sont déposées dans [blinking] ; chaque rangée porte sa phrase pour TalkBack.
 */
class BoardRows(private val context: Context, private val look: BoardLook, private val blinking: MutableList<View>) {
    /** Pastille : picto, attente, destination en petit, étagés sur la hauteur. */
    fun pill(item: BoardItem): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(picto(item))
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(wait(item))
        addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        addView(dest(item, look.dest))
        contentDescription = describe(item)
    }

    /** Ligne (2 à 4 × 1) : picto, destination avec « puis N min » dessous, attente à droite. */
    fun side(item: BoardItem): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(picto(item))
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(dest(item, look.dest))
            item.thenMinutes?.let { addView(lcdText(context, THEN_SP).apply { alpha = THEN_ALPHA; text = context.getString(R.string.widget_transport_then, it); isSingleLine = true }) }
        }
        addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(GAP); marginEnd = context.dp(GAP) })
        addView(wait(item))
        contentDescription = describe(item)
    }

    /** Colonne (1 × 2 à 4) : picto et attente sur une ligne, destination dessous. */
    fun column(item: BoardItem): View = LinearLayout(context).apply {
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

    /** Bloc, Grand, Plein écran : picto | destination | attente. */
    fun wide(item: BoardItem): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(picto(item))
        addView(dest(item, look.dest), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(GAP); marginEnd = context.dp(GAP) })
        addView(wait(item))
        contentDescription = describe(item)
    }

    private val words = TransportSpeech.words(context)

    private fun picto(item: BoardItem) = LinePicto(context).apply { set(item.picto, item.line?.name.orEmpty(), look.picto) }

    private fun dest(item: BoardItem, sp: Float) = lcdText(context, sp).apply {
        text = item.destination
        isSingleLine = true
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
                isSingleLine = true
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
        const val THEN_SP = 12f
        const val THEN_ALPHA = 0.7f
        const val UNIT_SCALE = 0.38f
        const val MIN_UNIT_SP = 11f
        const val WORD_SCALE = 0.5f
    }
}
