package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themeColor

/** Ce que l'afficheur montre : l'arrêt et l'heure du bandeau, et les passages de l'écran. */
class BoardContent(val stop: String, val clock: String, val items: List<BoardItem>)

/**
 * Afficheur de quai posé dans la carte du widget Transports : un bandeau à l'encre du thème (modes, arrêt, heure) et
 * l'écran à points ([LcdPanel]). Une mise en page par famille de formes : bandeau à gauche en Ligne, en haut sinon.
 * Tout est en couleurs du thème ; seuls les pictos ([LinePicto]) portent les couleurs officielles des lignes.
 */
class TransportBoard(private val context: Context, private val card: LinearLayout) {
    /** Étiquettes qui clignotent dans le rendu courant (lues par [Blinker]). */
    val blinking = mutableListOf<View>()

    private var family = ShapeFamily.PILL
    private var rows = 1
    private val band = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val screen = LcdPanel(context)
    private val font = ResourcesCompat.getFont(context, R.font.barlow_semicondensed_600)

    /** Pose le bandeau et l'écran dans la carte pour [family] ; [rows] = nombre de passages montrés. */
    fun layout(family: ShapeFamily, rows: Int) {
        this.family = family
        this.rows = rows
        val side = family == ShapeFamily.ROW
        card.removeAllViews()
        card.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        band.orientation = if (side) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        screen.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val padX = if (side) SIDE_PAD_X else 16
        screen.setPadding(context.dp(padX), context.dp(if (side) 8 else 10), context.dp(padX), context.dp(if (side) 8 else 10))
        val gap = context.dp(GAP)
        card.addView(band, if (side) LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT) else LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(
            screen,
            (if (side) LinearLayout.LayoutParams(0, MATCH_PARENT, 1f) else LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)).apply {
                if (side) marginStart = gap else topMargin = gap
            },
        )
    }

    fun show(content: BoardContent) {
        blinking.clear()
        screen.removeAllViews()
        screen.weightSum = 0f
        val items = content.items
        fillBand(content, items.mapNotNull { it.line?.mode }.distinct().sortedBy { it.ordinal })
        val maker = BoardRows(context, look(), blinking)
        when (family) {
            ShapeFamily.PILL -> items.firstOrNull()?.let { screen.addView(maker.pill(it), LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            ShapeFamily.ROW -> list(items, horizontal = true, maker::side)
            ShapeFamily.COLUMN -> list(items, horizontal = false, maker::column)
            else -> list(items, horizontal = false, maker::wide)
        }
    }

    /** Un message (clé absente, aucun arrêt, aucun passage, erreur) dans l'écran ; le bandeau reste. */
    fun showMessage(content: BoardContent, message: String) {
        blinking.clear()
        screen.removeAllViews()
        fillBand(content, emptyList())
        screen.addView(
            lcdText(context, MESSAGE_SP).apply {
                text = message
                maxLines = MESSAGE_LINES
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
    }

    /** Passages côte à côte ([horizontal], séparés d'un trait pointillé vertical) ou empilés ; une part égale chacun. */
    private fun list(items: List<BoardItem>, horizontal: Boolean, make: (BoardItem) -> View) {
        screen.weightSum = rows.toFloat()
        items.take(rows).forEachIndexed { i, item ->
            if (i > 0) screen.addView(divider(horizontal))
            screen.addView(
                make(item),
                if (horizontal) LinearLayout.LayoutParams(0, MATCH_PARENT, 1f) else LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
            )
        }
    }

    private fun divider(vertical: Boolean): View = DashDivider(context, vertical).apply {
        layoutParams = if (vertical) {
            LinearLayout.LayoutParams(context.dp(1), MATCH_PARENT).apply { marginStart = context.dp(GAP); marginEnd = context.dp(GAP) }
        } else {
            LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1))
        }
    }

    private fun fillBand(content: BoardContent, modes: List<TransportLines.Mode>) {
        val ink = themeColor(context, R.attr.c_text)
        val onInk = themeColor(context, R.attr.c_bg)
        val look = look()
        band.removeAllViews()
        band.background = GradientDrawable().apply {
            setColor(ink)
            cornerRadius = context.dp(BAND_RADIUS).toFloat()
        }
        val big = family == ShapeFamily.FULL
        when (family) {
            ShapeFamily.PILL -> {
                band.setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
                modes.take(1).forEach { band.addView(modeBadge(context, it, look.badge, onInk)) }
                band.visibility = if (modes.isEmpty()) View.GONE else View.VISIBLE
            }
            ShapeFamily.ROW -> {
                band.setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
                band.gravity = Gravity.CENTER
                modes.take(SIDE_MODES).forEachIndexed { i, m ->
                    band.addView(modeBadge(context, m, look.badge, onInk), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i > 0) topMargin = context.dp(6) })
                }
                band.contentDescription = content.stop
                band.visibility = View.VISIBLE
            }
            else -> {
                band.setPadding(context.dp(if (big) 18 else 12), context.dp(if (big) 10 else 6), context.dp(if (big) 18 else 12), context.dp(if (big) 10 else 6))
                band.gravity = Gravity.CENTER_VERTICAL
                modes.forEach { band.addView(modeBadge(context, it, look.badge, onInk), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = context.dp(6) }) }
                band.addView(bandText(content.stop, look.bandSp, onInk, 1f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                band.addView(bandText(content.clock, look.bandSp, onInk, CLOCK_ALPHA), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(8) })
                band.visibility = View.VISIBLE
            }
        }
    }

    private fun bandText(text: String, sp: Float, color: Int, alpha: Float) = TextView(context).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        typeface = font
        this.alpha = alpha
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun look() = when (family) {
        ShapeFamily.PILL -> BoardLook(30, 46f, 12f, 20, 19f)
        ShapeFamily.ROW -> BoardLook(38, 46f, 19f, 20, 19f)
        ShapeFamily.COLUMN -> BoardLook(26, 26f, 12f, 20, 19f)
        ShapeFamily.FULL -> BoardLook(44, 44f, 26f, 26, 28f)
        else -> BoardLook(30, 30f, 18f, 20, 19f)
    }

    private companion object {
        const val GAP = 7
        const val BAND_RADIUS = 13
        const val SIDE_PAD_X = 14
        const val SIDE_MODES = 3
        const val MESSAGE_SP = 14f
        const val MESSAGE_LINES = 3
        const val CLOCK_ALPHA = 0.8f
    }
}
