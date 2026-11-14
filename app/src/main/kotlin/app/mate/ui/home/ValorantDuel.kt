package app.mate.ui.home

import kotlin.math.abs

/**
 * Calculs de la vue agrandie « Face-à-face » ([ValorantDuelView], variante C choisie par Ahmed le 14/11/2026) : les
 * joueurs par paires, les barres qui poussent vers le centre, les moyennes d'équipe tirées l'une contre l'autre.
 */
object ValorantDuel {
    /** K/D qui remplit la barre ; ACS : le meilleur de la partie. */
    const val KD_FULL = 2.0

    /** Plus petit bout de barre : une valeur nulle ou inconnue ne fait pas une ligne vide. */
    const val MIN_BAR = 0.04f

    /** Haut de la vue (score, frise, moyennes) ; une paire à trois lignes (nom, rang, tête / victoires), à deux. */
    const val HEADER_DP = 170
    const val ROOMY_PAIR_DP = 70
    const val TWO_LINES_DP = 48
    private const val TEAM = 5

    /**
     * Lignes gardées par joueur : 3 (nom, rang et pic, petite ligne tête / victoires), 2 (sans la petite ligne) ou 1 (le
     * nom), selon la place ; [bodyDp] 0 : pas encore mesuré. Au moins une équipe de 5. Le texte grossi agrandit tout.
     */
    fun lines(bodyDp: Int, pairs: Int, fontScale: Float = 1f): Int {
        if (bodyDp <= 0) return 3
        val scale = maxOf(1f, fontScale)
        val each = (bodyDp - HEADER_DP * scale) / maxOf(pairs, TEAM)
        return when {
            each >= ROOMY_PAIR_DP * scale -> 3
            each >= TWO_LINES_DP * scale -> 2
            else -> 1
        }
    }

    /** Cadran du menu : sa taille, sans dépasser la place mesurée ([bodyDp] 0 : pas encore mesurée). */
    const val DIAL_DP = 340
    const val DIAL_MARGIN_DP = 24
    const val DIAL_MIN_DP = 160

    fun dialDp(bodyDp: Int): Int = if (bodyDp <= 0) DIAL_DP else (bodyDp - DIAL_MARGIN_DP).coerceIn(DIAL_MIN_DP, DIAL_DP)

    /** Joueur face à celui de même place (du meilleur rang au plus bas) ; place vide si l'autre équipe en a moins. */
    fun <T> pairs(allies: List<T>, enemies: List<T>): List<Pair<T?, T?>> =
        List(maxOf(allies.size, enemies.size)) { allies.getOrNull(it) to enemies.getOrNull(it) }

    fun average(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    /** Part de mon équipe dans la corde tirée (0,5 : égalité) ; null si l'une des deux manque. */
    fun share(mine: Double?, theirs: Double?): Float? {
        if (mine == null || theirs == null || mine + theirs <= 0.0) return null
        return (mine / (mine + theirs)).toFloat()
    }

    fun fraction(value: Double?, full: Double): Float {
        if (value == null || full <= 0.0) return MIN_BAR
        return (value / full).toFloat().coerceIn(MIN_BAR, 1f)
    }

    /** RR gagnés (positifs) ou perdus de mes dernières parties, rapportés au plus grand écart. */
    fun swings(rr: List<Int>): List<Float> {
        val max = rr.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: return rr.map { 0f }
        return rr.map { it.toFloat() / max }
    }
}
