package com.unowhy.companion.ui.home

/** Hauteurs des lignes de l'Accueil : égales par défaut, proportionnelles à des poids pour le profil Cadran. */
object RowProfile {
    /** Héros, ligne du jour, deux rangées de tuiles : 232 / 92 / 184 / 184 sur 740 de hauteur utile. */
    val CADRAN_ROWS = intArrayOf(232, 92, 184, 184)

    /** « Ma journée » en haut ([WidgetType.DAY]) : pas de grande heure, le bandeau baisse et les cartes gagnent la place. */
    val DAY_ROWS = intArrayOf(156, 92, 222, 222)

    /** Lignes du profil `page` : selon le bandeau posé en haut. */
    fun cadranRows(widgets: List<Placed>, page: Int) =
        if (widgets.any { it.page == page && it.row == 0 && it.type == WidgetType.DAY }) DAY_ROWS else CADRAN_ROWS

    /** Hauteur de chaque ligne : `total` moins les gouttières, réparti selon `weights`, le reste sur la dernière. */
    fun rowHeights(total: Int, gap: Int, weights: IntArray): IntArray {
        val room = total - gap * (weights.size - 1)
        val sum = weights.sum()
        val heights = IntArray(weights.size) { room * weights[it] / sum }
        heights[heights.lastIndex] += room - heights.sum()
        return heights
    }
}
