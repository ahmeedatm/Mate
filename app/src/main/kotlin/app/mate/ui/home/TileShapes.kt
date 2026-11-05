package app.mate.ui.home

/**
 * Comment les widgets de touches (Touches, Applis) se rangent dans la forme posée (#183) : la grille qui défile pour
 * les Blocs, les Grands et le Plein écran ; en Pastille, Ligne et Colonne, des touches qui tiennent sans défiler.
 */
object TileShapes {
    /** Éléments de [item] px qui tiennent dans [room] px, [gap] px entre deux ; au moins 1 (vue pas encore mesurée). */
    fun fit(room: Int, item: Int, gap: Int): Int = maxOf(1, (room + gap) / (item + gap))

    fun usesGrid(size: WidgetSize) = size.family !in setOf(ShapeFamily.PILL, ShapeFamily.ROW, ShapeFamily.COLUMN)

    /** Grandes touches : une par case en Pastille et Ligne, deux de front en Colonne (une case fait 300 dp de large). */
    fun bigColumns(size: WidgetSize) = if (size.family == ShapeFamily.COLUMN) 2 else size.w
}
