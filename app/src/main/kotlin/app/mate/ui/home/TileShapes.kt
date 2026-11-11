package app.mate.ui.home

/**
 * Comment les widgets de touches (Touches, Applis) se rangent dans la forme posée (#183) : la grille qui défile pour
 * les Blocs, les Grands et le Plein écran ; en Pastille, Ligne et Colonne, des touches qui tiennent sans défiler
 * ([TileStrip] les dessine).
 */
object TileShapes {
    /** Une ligne de la liste en Colonne, et l'écart entre deux. */
    const val LIST_ROW_DP = 56
    const val LIST_GAP_DP = 4

    /**
     * Dans un panneau à filet (Instrument) : lignes de la hauteur de la plaque ronde (52 dp, jamais rognée) et 4 dp de jeu,
     * au moins 8 dp entre deux lignes, marge réduite, titre et 8 dp de marge dessous.
     */
    const val LIST_ROW_PANEL_DP = 56
    const val LIST_GAP_PANEL_DP = 8
    const val PANEL_STRIP_PAD_DP = 6
    const val PANEL_CAPTION_DP = 14
    const val PANEL_CAPTION_GAP_DP = 8

    /** Une icône seule en Ligne, et l'écart entre deux. */
    const val ICON_DP = 64
    const val ICON_GAP_DP = 8

    /** Éléments de [item] px qui tiennent dans [room] px, [gap] px entre deux ; au moins 1 (vue pas encore mesurée). */
    fun fit(room: Int, item: Int, gap: Int): Int = maxOf(1, (room + gap) / (item + gap))

    fun usesGrid(size: WidgetSize) = size.family.let { it != ShapeFamily.PILL && it != ShapeFamily.ROW && it != ShapeFamily.COLUMN }

    /**
     * Touches montrées hors de la grille dans une vue de [width] × [height] px : icônes en largeur en Ligne, lignes de
     * liste en hauteur en Colonne, une seule en Pastille. [density] : px par dp, comme `Context.dp`. [panel] : dans un panneau à filet, la Colonne a des lignes plus basses.
     */
    fun stripFit(family: ShapeFamily, width: Int, height: Int, density: Float, panel: Boolean = false, gapDp: Int = LIST_GAP_DP): Int {
        fun px(dp: Int) = (dp * density).toInt()
        return when (family) {
            ShapeFamily.ROW -> fit(width, px(ICON_DP), px(ICON_GAP_DP))
            ShapeFamily.COLUMN -> fit(height, px(if (panel) LIST_ROW_PANEL_DP else LIST_ROW_DP), px(if (panel) maxOf(gapDp, LIST_GAP_PANEL_DP) else gapDp))
            else -> 1
        }
    }
}
