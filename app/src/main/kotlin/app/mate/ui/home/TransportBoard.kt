package app.mate.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
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

/** Un message de la carte d'info trafic : le picto de sa ligne, son nom parlé (« RER B »), sa gravité et le texte d'IDFM. */
class TrafficNote(val line: TransportLines.Line?, val picto: PictoSpec, val spoken: String, val level: TransportTraffic.Level, val text: String)

/** Orange et rouge des alertes d'IDFM, pareils dans tous les thèmes comme les pictos ; « à venir » prend l'encre de l'écran. */
fun trafficColor(context: Context, level: TransportTraffic.Level): Int = when (level) {
    TransportTraffic.Level.SOON -> themeColor(context, R.attr.c_mx_lcd_text)
    TransportTraffic.Level.DISRUPTED -> 0xFFF28E00.toInt() // controle: permis orange « perturbé » des alertes IDFM, identique dans tous les thèmes
    TransportTraffic.Level.INTERRUPTED -> 0xFFE2001A.toInt() // controle: permis rouge « interrompu » des alertes IDFM, identique dans tous les thèmes
}

/** Encre posée sur [trafficColor] (mot, marque de la pastille) : blanc sur le rouge, presque noir sur l'orange (4,5:1 au moins). */
fun trafficInk(level: TransportTraffic.Level): Int =
    if (level == TransportTraffic.Level.INTERRUPTED) 0xFFFFFFFF.toInt() else 0xFF141413.toInt() // controle: permis encres opaques des alertes IDFM, contrastes vérifiés

/** Mot court de l'info trafic sur l'écran : plein de la couleur de l'alerte, ou cerné de l'encre de l'écran pour « à venir ». */
fun trafficWord(context: Context, level: TransportTraffic.Level, sp: Float): TextView = lcdText(context, sp).apply {
    setText(level.wordRes())
    maxLines = 1
    val color = trafficColor(context, level)
    val pad = context.dp(WORD_PAD_DP)
    setPadding(pad, 0, pad, 0)
    background = GradientDrawable().apply {
        cornerRadius = context.dp(WORD_RADIUS_DP).toFloat()
        if (level == TransportTraffic.Level.SOON) setStroke(context.dp(1), color) else setColor(color)
    }
    if (level != TransportTraffic.Level.SOON) {
        setTextColor(trafficInk(level))
        setShadowLayer(0f, 0f, 0f, 0)
    }
}

private const val WORD_PAD_DP = 5
private const val WORD_RADIUS_DP = 3

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

    /**
     * Pose la carte d'info trafic par-dessus l'écran : les plus graves d'abord, le texte d'IDFM en entier, défilable (bord
     * estompé quand il y a une suite), pied fixe avec la source et une croix. Toucher la carte ou la croix appelle
     * [onClose] ; un défilement appelle [onActivity] (l'hôte repousse la fermeture automatique). Filet du thème plutôt
     * qu'une ombre (DA), pour qu'elle se détache aussi d'un écran sombre sur un thème sombre.
     */
    fun showNote(notes: List<TrafficNote>, onClose: () -> Unit, onActivity: () -> Unit) {
        hideNote()
        val pad = context.dp(NOTE_PAD)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            notes.forEachIndexed { i, n -> addView(noteRow(n), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (i > 0) topMargin = context.dp(NOTE_GAP) }) }
            setOnClickListener { onClose() }
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(NOTE_FADE))
            setOnScrollChangeListener { _, _, _, _, _ -> onActivity() }
            addView(body)
        }
        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(themeColor(context, R.attr.c_text))
            contentDescription = context.getString(R.string.close)
            setPadding(context.dp(NOTE_CLOSE_PAD), context.dp(NOTE_CLOSE_PAD), context.dp(NOTE_CLOSE_PAD), context.dp(NOTE_CLOSE_PAD))
            setOnClickListener { onClose() }
        }
        val foot = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, 0, 0)
            addView(noteText(R.attr.c_text_dim, R.attr.c_font_regular, NOTE_SOURCE_SP).apply { setText(R.string.widget_transport_traffic_source) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(context.dp(NOTE_CLOSE), context.dp(NOTE_CLOSE)))
            setOnClickListener { onClose() }
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(themeColor(context, R.attr.c_bg))
                setStroke(context.dp(1), themeColor(context, R.attr.c_stroke))
                cornerRadius = context.dp(NOTE_RADIUS).toFloat()
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // Plus court que l'écran : la carte grandit avec son texte, puis le texte défile au-dessus du pied.
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1f))
            addView(foot, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val margin = context.dp(NOTE_MARGIN)
        stack.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply { setMargins(margin, margin, margin, margin) })
        screen.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        note = view
        Look.popIn(view)
    }

    fun hideNote() {
        note?.let { stack.removeView(it) }
        note = null
        screen.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun noteRow(n: TrafficNote) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val title = context.getString(n.level.titleRes())
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinePicto(context).apply { set(n.picto, n.line?.name.orEmpty(), NOTE_PICTO, n.level, onCard = true) })
                addView(noteText(R.attr.c_text, R.attr.c_font_medium, NOTE_TITLE_SP).apply { text = title }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = context.dp(NOTE_GAP) })
            },
        )
        addView(noteText(R.attr.c_text, R.attr.c_font_regular, NOTE_TEXT_SP).apply { text = n.text }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(4) })
        // TalkBack : la ligne, la gravité et le texte d'une traite (le picto seul ne se lit pas).
        contentDescription = listOf(n.spoken, title, n.text).filter { it.isNotBlank() }.joinToString(", ")
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        for (i in 0 until childCount) getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
        // La pastille d'info trafic déborde du coin de son picto : aucun parent ne la rogne, l'écran garde sa marge.
        unclip(screen)
    }

    private fun unclip(view: View) {
        if (view !is ViewGroup) return
        view.clipChildren = false
        for (i in 0 until view.childCount) unclip(view.getChildAt(i))
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
                traffic?.let { addView(trafficWord(context, it, look().head * WORD_SCALE)) }
            },
        )
        addView(DashDivider(context, vertical = false), LinearLayout.LayoutParams(MATCH_PARENT, context.dp(1)).apply { topMargin = context.dp(HEAD_GAP) })
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
        const val WORD_SCALE = 1f
        const val NOTE_PAD = 14
        const val NOTE_GAP = 10
        const val NOTE_MARGIN = 10
        const val NOTE_RADIUS = 12
        const val NOTE_FADE = 24
        const val NOTE_CLOSE = 44
        const val NOTE_CLOSE_PAD = 11
        const val NOTE_PICTO = 22
        const val NOTE_TITLE_SP = 16f
        const val NOTE_TEXT_SP = 16f
        const val NOTE_SOURCE_SP = 13f
    }
}
