package app.mate.ui.home

/**
 * Capuchons d'Instrument pour les widgets de touches (Touches, Applis ; variante « B · Afficheur + clavier » choisie
 * par Ahmed, #243) : la touche du dock en petit, numéro en haut à gauche, DEL en haut à droite, icône au trait et nom en
 * capitales. Seulement dans un thème à fenêtre LCD (`c_lcd_overlay`) ; les autres thèmes gardent leurs plaques.
 */
object KeyCaps {
    /** Un capuchon en Ligne : assez large pour un nom court en capitales. */
    const val ROW_MIN_DP = 88

    /** Hauteur d'une case de la grille, et enfoncement du capuchon d'une touche allumée (comme le dock). */
    const val CELL_DP = 76
    const val LIT_DP = 4

    /** Grand et Plein écran (choix d'Ahmed) : capuchons plus hauts et plus larges, le nom sur deux lignes. */
    const val COLUMN_DP = 96
    const val BIG_COLUMN_DP = 128
    const val BIG_CELL_DP = 108

    private fun big(size: WidgetSize) = size.family == ShapeFamily.LARGE || size.family == ShapeFamily.FULL

    fun cellDp(size: WidgetSize) = if (big(size)) BIG_CELL_DP else CELL_DP

    fun labelLines(size: WidgetSize) = if (big(size)) 2 else 1

    fun applies(lcdOverlay: Int) = lcdOverlay != 0

    /** Numéro gravé sur le capuchon n° [index] (0 = le premier) : « 01 », « 02 »… */
    fun number(index: Int): String = (index + 1).toString().padStart(2, '0')

    /** Capuchons qui tiennent hors de la grille : plus larges qu'une icône en Ligne, lignes du panneau en Colonne. */
    fun stripFit(family: ShapeFamily, width: Int, height: Int, density: Float, gapDp: Int = TileShapes.LIST_GAP_DP): Int = when (family) {
        ShapeFamily.ROW -> TileShapes.fit(width, (ROW_MIN_DP * density).toInt(), (TileShapes.ICON_GAP_DP * density).toInt())
        else -> TileShapes.stripFit(family, width, height, density, panel = true, gapDp = gapDp)
    }

    /** La fenêtre LCD (« ▸ SPOTIFY · ENVOYÉ ») coiffe les Touches posées en grille : Bloc, Grand, Plein écran. */
    fun lcd(size: WidgetSize) = TileShapes.usesGrid(size)
}
