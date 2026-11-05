package app.mate.ui

import app.mate.ui.home.ShapeFamily
import app.mate.ui.home.WidgetSize

/**
 * Mise en page des cartes à liste (Tâches, Courses) selon la forme posée (#181) : une tête (anneau de progrès, nombre
 * restant) partout, puis la liste. Pastille : la tête seule. Ligne : la tête à gauche, une colonne de liste par case en
 * plus. Colonne : la tête au-dessus, la liste sans cases (toucher la ligne la coche), l'ajout dès 1 × 3. Bloc, Grand et
 * Plein écran : cases et ajout, une colonne de liste de plus à partir de 3 cases de large, 3 en plein écran.
 */
data class ListShape(
    val list: Boolean,
    val columns: Int,
    val checks: Boolean,
    val addRow: Boolean,
    val sideHead: Boolean,
) {
    companion object {
        fun of(size: WidgetSize): ListShape = when (size.family) {
            ShapeFamily.PILL -> ListShape(list = false, columns = 1, checks = false, addRow = false, sideHead = false)
            ShapeFamily.ROW -> ListShape(list = true, columns = size.w - 1, checks = true, addRow = false, sideHead = true)
            ShapeFamily.COLUMN -> ListShape(list = true, columns = 1, checks = false, addRow = size.h >= 3, sideHead = false)
            ShapeFamily.BLOCK, ShapeFamily.LARGE -> ListShape(list = true, columns = if (size.w >= 3) 2 else 1, checks = true, addRow = true, sideHead = false)
            ShapeFamily.FULL -> ListShape(list = true, columns = 3, checks = true, addRow = true, sideHead = false)
        }

        /** Les éléments rangés ligne par ligne, `columns` par ligne : l'ordre de lecture reste celui de la liste. */
        fun <T> rows(items: List<T>, columns: Int): List<List<T>> = items.chunked(columns.coerceAtLeast(1))
    }
}
