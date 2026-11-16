package app.mate.ui.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.mate.R
import app.mate.ui.Look
import app.mate.ui.dp
import app.mate.ui.themeColor
import app.mate.ui.themeFont

/**
 * Ce que l'afficheur montre : l'arrêt (en tête des grandes formes), les passages de l'écran, et [traffic], le plus grave
 * de l'info trafic de l'arrêt, dit en un mot à droite de l'arrêt.
 */
class BoardContent(val stop: String, val items: List<BoardItem>, val traffic: TransportTraffic.Level? = null)

/** Un message de la carte d'info trafic : le picto de sa ligne, sa gravité et le texte d'IDFM. */
class TrafficNote(val line: TransportLines.Line?, val picto: PictoSpec, val level: TransportTraffic.Level, val text: String)

/** Orange et rouge des alertes d'IDFM, pareils dans tous les thèmes comme les pictos ; « à venir » prend l'encre de l'écran. */
fun trafficColor(context: Context, level: TransportTraffic.Level): Int = when (level) {
    TransportTraffic.Level.SOON -> themeColor(context, R.attr.c_mx_lcd_text)
    TransportTraffic.Level.DISRUPTED -> 0xFFF28E00.toInt() // controle: permis orange « perturbé » des alertes IDFM, identique dans tous les thèmes
    TransportTraffic.Level.INTERRUPTED -> 0xFFE2001A.toInt() // controle: permis rouge « interrompu » des alertes IDFM, identique dans tous les thèmes
}

/**
 * Afficheur de quai posé dans la carte du widget Transports : l'écran à points ([LcdPanel]), le nom de l'arrêt en tête
 * dès la Colonne, et la jauge d'approche sous le prochain passage. Une mise en page par famille de formes. Pas d'heure :
 * le bandeau de l'appli la montre déjà. Tout est en couleurs du thème ; seuls les pictos ([LinePicto]) et les jauges
 * portent les couleurs officielles des lignes, et l'info trafic celles des alertes d'IDFM ([trafficColor]). La carte
 * d'info trafic ([showNote]) se pose par-dessus l'écran, dans le widget.
 */
class TransportBoard(private val context: Context, private val card: LinearLayout) {
    /** Étiquettes qui clignotent dans le rendu courant (lues par [Blinker]). */
    val blinking = mutableListOf<View>()

    private var family = ShapeFamily.PILL
    private var rows = 1
    private val screen = LcdPanel(context)
    private val list = LinearLayout(context)
    private val stack = FrameLayout(context)
    private var note: View? = null

    val noteShown get() = note != null

    /** Pose l'écran dans la carte pour [family] ; [rows] = nombre de passages montrés. */
    fun layout(family: ShapeFamily, rows: Int) {
        this.family = family
        this.rows = rows
        val side = family == ShapeFamily.ROW
        card.removeAllViews()
        stack.removeAllViews()
        note = null
        screen.removeAllViews()
        list.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val padX = if (side) SIDE_PAD_X else PAD_X
        val padY = if (side) SIDE_PAD_Y else PAD_Y
        screen.setPadding(context.dp(padX), context.dp(padY), context.dp(padX), context.dp(padY))
        stack.addView(screen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        card.addView(stack, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** Pose la carte d'info trafic par-dessus l'écran : les plus graves d'abord, le texte d'IDFM en entier, défilable. */
    fun showNote(notes: List<TrafficNote>) {
        hideNote()
        val pad = context.dp(NOTE_PAD)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            notes.forEachIndexed { i, n -> addView(noteRow(n), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = context.dp(NOTE_GAP) }) }
            addView(noteText(R.attr.c_text_dim, R.attr.c_font_regular, NOTE_SOURCE_SP).apply { setText(R.string.widget_transport_traffic_source) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(NOTE_GAP) })
        }
        val view = ScrollView(context).apply {
            background = GradientDrawable().apply {
                setColor(themeColor(context, R.attr.c_bg))
                cornerRadius = context.dp(NOTE_RADIUS).toFloat()
            }
            elevation = context.dp(NOTE_ELEVATION).toFloat()
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        val margin = context.dp(NOTE_MARGIN)
        stack.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply { setMargins(margin, margin, margin, margin) })
        note = view
        Look.popIn(view)
    }

    fun hideNote() {
        note?.let { stack.removeView(it) }
        note = null
    }

    private fun noteRow(n: TrafficNote) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinePicto(context).apply { set(n.picto, n.line?.name.orEmpty(), NOTE_PICTO, n.level) })
                addView(noteText(R.attr.c_text, R.attr.c_font_medium, NOTE_TITLE_SP).apply { setText(n.level.titleRes()) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(NOTE_GAP) })
            },
        )
        addView(noteText(R.attr.c_text, R.attr.c_font_regular, NOTE_TEXT_SP).apply { text = n.text }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(4) })
    }

    private fun noteText(colorAttr: Int, fontAttr: Int, sp: Float) = TextView(context).apply {
        textSize = sp
        setTextColor(themeColor(context, colorAttr))
        typeface = themeFont(context, fontAttr)
    }

    fun show(content: BoardContent) {
        reset(content)
        val items = content.items
        val maker = BoardRows(context, look(), blinking)
        when (family) {
            ShapeFamily.PILL -> items.firstOrNull()?.let { list.addView(maker.pill(it), LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
            ShapeFamily.ROW -> fill(items, horizontal = true, maker::side)
            ShapeFamily.COLUMN -> fill(items, horizontal = false, maker::column)
            else -> fill(items, horizontal = false, maker::wide)
        }
    }

    /** Un message (clé absente, aucun arrêt, aucun passage, erreur) dans l'écran, sous le nom de l'arrêt s'il y en a un. */
    fun showMessage(content: BoardContent, message: String) {
        reset(content)
        list.addView(
            lcdText(context, MESSAGE_SP).apply {
                text = message
                isAllCaps = false
                maxLines = MESSAGE_LINES
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )
    }

    /** Vide l'écran et remet l'en-tête (nom de l'arrêt) sur les formes qui en ont la place. */
    private fun reset(content: BoardContent) {
        blinking.clear()
        screen.removeAllViews()
        list.removeAllViews()
        list.weightSum = 0f
        if (family != ShapeFamily.PILL && family != ShapeFamily.ROW) screen.addView(head(content.stop, content.traffic), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        screen.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun head(stop: String, traffic: TransportTraffic.Level?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    lcdText(context, look().head).apply {
                        text = stop
                        alpha = HEAD_ALPHA
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                )
                traffic?.let { addView(word(it)) }
            },
        )
        addView(DashDivider(context, vertical = false), LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1)).apply { topMargin = context.dp(HEAD_GAP) })
    }

    /** Mot de l'info trafic en tête : plein de la couleur de l'alerte, ou cerné de l'encre de l'écran pour « à venir ». */
    private fun word(level: TransportTraffic.Level) = lcdText(context, look().head * WORD_SCALE).apply {
        setText(level.wordRes())
        maxLines = 1
        val color = trafficColor(context, level)
        val pad = context.dp(WORD_PAD)
        setPadding(pad, 0, pad, 0)
        background = GradientDrawable().apply {
            cornerRadius = context.dp(WORD_RADIUS).toFloat()
            if (level == TransportTraffic.Level.SOON) setStroke(context.dp(1), color) else setColor(color)
        }
        if (level != TransportTraffic.Level.SOON) {
            val ink = themeColor(context, R.attr.c_mx_lcd_bg)
            setTextColor(ink)
            setShadowLayer(0f, 0f, 0f, ink)
        }
    }

    /** Passages côte à côte ([horizontal], séparés d'un trait pointillé vertical) ou empilés ; une part égale chacun. */
    private fun fill(items: List<BoardItem>, horizontal: Boolean, make: (BoardItem, Boolean) -> View) {
        list.weightSum = rows.toFloat()
        items.take(rows).forEachIndexed { i, item ->
            if (i > 0) list.addView(divider(horizontal))
            // Jauge d'approche : sous chaque passage en Ligne (un ou deux), sous le prochain seul ailleurs.
            list.addView(
                make(item, horizontal || i == 0),
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

    private fun look() = when (family) {
        ShapeFamily.PILL -> BoardLook(30, 46f, 14f, 15f, 4)
        ShapeFamily.ROW -> BoardLook(38, 46f, 19f, 15f, 4)
        ShapeFamily.COLUMN -> BoardLook(26, 26f, 14f, 15f, 4)
        ShapeFamily.FULL -> BoardLook(44, 44f, 26f, 18f, 6)
        else -> BoardLook(30, 30f, 18f, 15f, 4)
    }

    private companion object {
        const val GAP = 7
        const val PAD_X = 16
        const val PAD_Y = 10
        const val SIDE_PAD_X = 14
        const val SIDE_PAD_Y = 8
        const val HEAD_GAP = 6
        const val HEAD_ALPHA = 0.85f
        const val MESSAGE_SP = 14f
        const val MESSAGE_LINES = 3
        const val WORD_SCALE = 0.9f
        const val WORD_PAD = 5
        const val WORD_RADIUS = 3
        const val NOTE_PAD = 14
        const val NOTE_GAP = 10
        const val NOTE_MARGIN = 10
        const val NOTE_RADIUS = 12
        const val NOTE_ELEVATION = 10
        const val NOTE_PICTO = 20
        const val NOTE_TITLE_SP = 15f
        const val NOTE_TEXT_SP = 14f
        const val NOTE_SOURCE_SP = 12f
    }
}
