package app.mate.ui

/**
 * Balayage orange entre deux écrans des thèmes Instrument (ui/SweepView) : durées, sens et course de l'aplat et du texte.
 * Calcul pur, sans Android : la vue l'applique. Le sens suit le déplacement dans la barre ([lastMove]) : vers le bas,
 * l'aplat vient de la gauche (comme la maquette).
 */
object SweepModel {
    /** Aplat qui couvre l'ancien écran, puis qui découvre le nouveau (ms). */
    const val COVER_MS = 300L
    const val REVEAL_MS = 360L

    /** Numéro et nom de l'écran : ils arrivent avec l'aplat (un peu plus longtemps), puis repartent (ms). */
    const val TEXT_COVER_MS = 420L
    const val TEXT_REVEAL_MS = 360L

    /** Course du texte, en dp, dans le sens du balayage. */
    private const val TEXT_COVER_DP = 180f
    private const val TEXT_REVEAL_DP = 240f

    /** Aplat hors de l'écran : sa largeur plus un peu, pour qu'aucun liseré ne reste au bord. */
    private const val OFF_SCREEN = 1.01f

    /** +1 : l'aplat vient de la gauche (on est descendu dans la barre) ; -1 : de la droite. */
    fun direction(lastMove: Int): Int = if (lastMove < 0) -1 else 1

    /** Translations (px) de l'aplat et du texte pour un balayage d'un écran de [width] px de large. */
    fun plan(lastMove: Int, width: Float, density: Float): Plan {
        val dir = direction(lastMove)
        val off = dir * width * OFF_SCREEN
        return Plan(
            direction = dir,
            plateStart = -off,
            plateEnd = off,
            textStart = -dir * TEXT_COVER_DP * density,
            textEnd = dir * TEXT_REVEAL_DP * density,
        )
    }

    data class Plan(val direction: Int, val plateStart: Float, val plateEnd: Float, val textStart: Float, val textEnd: Float)
}
