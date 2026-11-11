package app.mate.ui.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import app.mate.ui.dp

/** Ce que l'afficheur montre : l'arrêt (en tête des grandes formes) et les passages de l'écran. */
class BoardContent(val stop: String, val items: List<BoardItem>)

/**
 * Afficheur de quai posé dans la carte du widget Transports : l'écran à points ([LcdPanel]), le nom de l'arrêt en tête
 * dès la Colonne, et la jauge d'approche sous le prochain passage. Une mise en page par famille de formes. Pas d'heure :
 * le bandeau de l'appli la montre déjà. Tout est en couleurs du thème ; seuls les pictos ([LinePicto]) et les jauges
 * portent les couleurs officielles des lignes.
 */
class TransportBoard(private val context: Context, private val card: LinearLayout) {
    /** Étiquettes qui clignotent dans le rendu courant (lues par [Blinker]). */
    val blinking = mutableListOf<View>()

    private var family = ShapeFamily.PILL
    private var rows = 1
    private val screen = LcdPanel(context)
    private val list = LinearLayout(context)

    /** Pose l'écran dans la carte pour [family] ; [rows] = nombre de passages montrés. */
    fun layout(family: ShapeFamily, rows: Int) {
        this.family = family
        this.rows = rows
        val side = family == ShapeFamily.ROW
        card.removeAllViews()
        screen.removeAllViews()
        list.orientation = if (side) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val padX = if (side) SIDE_PAD_X else PAD_X
        val padY = if (side) SIDE_PAD_Y else PAD_Y
        screen.setPadding(context.dp(padX), context.dp(padY), context.dp(padX), context.dp(padY))
        card.addView(screen, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
        if (family != ShapeFamily.PILL && family != ShapeFamily.ROW) screen.addView(head(content.stop), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        screen.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun head(stop: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            lcdText(context, look().head).apply {
                text = stop
                alpha = HEAD_ALPHA
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
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
        ShapeFamily.PILL -> BoardLook(30, 46f, 12f, 13f, 4)
        ShapeFamily.ROW -> BoardLook(38, 46f, 19f, 13f, 4)
        ShapeFamily.COLUMN -> BoardLook(26, 26f, 12f, 13f, 4)
        ShapeFamily.FULL -> BoardLook(44, 44f, 26f, 18f, 6)
        else -> BoardLook(30, 30f, 18f, 13f, 4)
    }

    private companion object {
        const val GAP = 7
        const val PAD_X = 16
        const val PAD_Y = 10
        const val SIDE_PAD_X = 14
        const val SIDE_PAD_Y = 8
        const val HEAD_GAP = 6
        const val HEAD_ALPHA = 0.7f
        const val MESSAGE_SP = 14f
        const val MESSAGE_LINES = 3
    }
}
