package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.ui.dp

/**
 * Pastille, Ligne et Colonne des widgets de touches (Touches, Applis), sans défilement : en Colonne une liste (icône et
 * nom sur une ligne), en Ligne des icônes seules, en Pastille l'icône de la première touche. Le nombre suit la taille
 * réelle de la vue, qui change avec le profil de rangées.
 */
object TileStrip {
    /** Une ligne de la liste en Colonne, et l'écart entre deux. */
    private const val LIST_ROW_DP = 56
    private const val LIST_GAP_DP = 4

    /** Une icône seule en Ligne, et l'écart entre deux. */
    private const val ICON_DP = 64
    private const val ICON_GAP_DP = 8

    /** Touches qui tiennent dans une vue de [width] × [height] px ; 1 tant qu'elle n'est pas mesurée. */
    fun fit(context: Context, family: ShapeFamily, width: Int, height: Int) = when (family) {
        ShapeFamily.ROW -> TileShapes.fit(width, context.dp(ICON_DP), context.dp(ICON_GAP_DP))
        ShapeFamily.COLUMN -> TileShapes.fit(height, context.dp(LIST_ROW_DP), context.dp(LIST_GAP_DP))
        else -> 1
    }

    fun orientation(family: ShapeFamily) = if (family == ShapeFamily.ROW) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

    fun look(family: ShapeFamily) = if (family == ShapeFamily.COLUMN) TileLook.LIST else TileLook.ICON

    /** Place de la touche n° [index] dans la vue de la forme. */
    fun params(context: Context, family: ShapeFamily, index: Int): LinearLayout.LayoutParams = when (family) {
        ShapeFamily.ROW -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = context.dp(ICON_GAP_DP) }
        ShapeFamily.COLUMN -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(LIST_ROW_DP)).apply { if (index > 0) topMargin = context.dp(LIST_GAP_DP) }
        else -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
}

/** Habit d'une case de [TileGridView.newCell] hors de la grille : case normale, ligne de liste, ou icône sans nom. */
enum class TileLook {
    TILE,
    LIST,
    ICON,
    ;

    /** À rappeler après chaque [TileGridView.bind] : il remet le libellé, pas l'habit. */
    fun apply(tile: View) {
        val label = tile.findViewById<TextView>(R.id.tile_label)
        when (this) {
            TILE -> Unit
            ICON -> {
                label.visibility = View.GONE
                (tile as LinearLayout).gravity = Gravity.CENTER
                tile.contentDescription = label.text
            }
            LIST -> {
                (tile as LinearLayout).orientation = LinearLayout.HORIZONTAL
                tile.gravity = Gravity.CENTER_VERTICAL
                label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                label.maxLines = 1
                label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = tile.context.dp(12) }
            }
        }
    }
}
