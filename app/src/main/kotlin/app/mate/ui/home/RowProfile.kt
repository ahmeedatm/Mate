package app.mate.ui.home

/** Hauteurs des lignes de l'Accueil : égales par défaut, proportionnelles à des poids pour le profil à lignes inégales. */
object RowProfile {
    /** Héros, ligne du jour, deux rangées de tuiles : 232 / 92 / 184 / 184 sur 740 de hauteur utile. */
    val CADRAN_ROWS = intArrayOf(232, 92, 184, 184)

    /** Hauteur de chaque ligne : `total` moins les gouttières, réparti selon `weights`, le reste sur la dernière. */
    fun rowHeights(total: Int, gap: Int, weights: IntArray): IntArray {
        val room = total - gap * (weights.size - 1)
        val sum = weights.sum()
        val heights = IntArray(weights.size) { room * weights[it] / sum }
        heights[heights.lastIndex] += room - heights.sum()
        return heights
    }
}
