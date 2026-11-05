package app.mate.ui.home

/**
 * Une forme de la fiche du catalogue : `spot`, la place où « Ajouter le widget » la pose (null : nulle part sur la page) ;
 * `atCase` : cette place est la case touchée. Faux avec une place : grisée « Pas la place ici », le bouton propose la
 * première place libre.
 */
data class ShapeChoice(val size: WidgetSize, val spot: Fit?, val atCase: Boolean)

/**
 * Les formes de `type` pour un ajout à la case (`col`, `row`) de `page`, dans l'ordre de `type.sizes`. La case recule
 * près du bord pour laisser tenir la forme ; sans case touchée (`col` < 0), toute forme qui tient quelque part est posable.
 */
fun HomeLayout.shapeChoices(type: WidgetType, page: Int, col: Int, row: Int): List<ShapeChoice> = type.sizes.map { size ->
    val c = col.coerceAtMost(COLS - size.w)
    val r = row.coerceAtMost(ROWS - size.h)
    if (col >= 0 && fits(page, c, r, size)) return@map ShapeChoice(size, Fit(size, c, r), atCase = true)
    val free = freeSpot(page, size)?.let { (fc, fr) -> Fit(size, fc, fr) }
    ShapeChoice(size, free, atCase = col < 0 && free != null)
}
