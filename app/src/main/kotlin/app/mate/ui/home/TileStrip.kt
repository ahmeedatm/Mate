package app.mate.ui.home

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.mate.R
import app.mate.ui.dp
import app.mate.ui.themePx
import kotlin.math.roundToInt

/**
 * Pastille, Ligne et Colonne des widgets de touches (Touches, Applis), sans défilement : en Colonne une liste (icône et
 * nom sur une ligne), en Ligne des icônes seules, en Pastille l'icône de la première touche. Le nombre suit la taille
 * réelle de la vue, qui change avec le profil de rangées.
 */
object TileStrip {
    /** Touches qui tiennent dans une vue de [width] × [height] px ; 1 tant qu'elle n'est pas mesurée. */
    fun fit(context: Context, family: ShapeFamily, width: Int, height: Int, panel: Boolean = false) =
        TileShapes.stripFit(family, width, height, context.resources.displayMetrics.density, panel, gapDp(context))

    /** Écart entre deux lignes de liste (dp) : celui du thème (`c_list_gap`). */
    fun gapDp(context: Context) = (themePx(context, R.attr.c_list_gap) / context.resources.displayMetrics.density).roundToInt()

    fun orientation(family: ShapeFamily) = if (family == ShapeFamily.ROW) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL

    fun look(family: ShapeFamily) = if (family == ShapeFamily.COLUMN) TileLook.LIST else TileLook.ICON

    /** Place de la touche n° [index] dans la vue de la forme ([panel] : Colonne d'un panneau à filet, lignes plus basses). */
    fun params(context: Context, family: ShapeFamily, index: Int, panel: Boolean = false): LinearLayout.LayoutParams = when (family) {
        ShapeFamily.ROW -> LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = context.dp(TileShapes.ICON_GAP_DP) }
        ShapeFamily.COLUMN -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(if (panel) TileShapes.LIST_ROW_PANEL_DP else TileShapes.LIST_ROW_DP)).apply { if (index > 0) topMargin = context.dp(if (panel) maxOf(gapDp(context), TileShapes.LIST_GAP_PANEL_DP) else gapDp(context)) }
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
