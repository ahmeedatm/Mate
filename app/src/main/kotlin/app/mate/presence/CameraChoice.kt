package app.mate.presence

/**
 * Choix de la caméra avant, sans Android : tailles et cadences sont des paires (largeur, hauteur) et (bas, haut)
 * lues par [PresenceCamera] dans les caractéristiques du capteur.
 */
object CameraChoice {
    const val MIN_WIDTH = 160
    const val MIN_HEIGHT = 120

    /** Plus petite image d'au moins 160×120 (de quoi réduire à 32×24 sans trou) ; null si le capteur n'en propose pas. */
    fun smallest(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? =
        sizes.filter { it.first >= MIN_WIDTH && it.second >= MIN_HEIGHT }.minByOrNull { it.first.toLong() * it.second }

    /** Cadence la plus basse (à plafond égal, la plus stable : plancher le plus haut) : le capteur produit moins d'images. */
    fun lowestRate(ranges: List<Pair<Int, Int>>): Pair<Int, Int>? =
        ranges.minWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
}
