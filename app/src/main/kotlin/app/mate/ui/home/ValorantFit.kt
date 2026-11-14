package app.mate.ui.home

import app.mate.net.Valorant
import app.mate.net.ValorantEndStatus
import app.mate.net.ValorantState
import kotlin.math.roundToInt

/**
 * Ce que le tableau des joueurs garde dans la hauteur réelle de la case (elle change avec le thème et le profil de
 * rangées) : [ValorantShape] donne le maximum, puis on retire d'abord le niveau, puis les colonnes de détail, puis
 * l'équipe d'en face, jusqu'à ce que tout tienne. Jamais une ligne coupée à moitié. Vaut aussi pour le tableau de fin
 * (`detail` = toutes les colonnes).
 */
data class ValorantFit(val enemies: Boolean, val detail: Boolean, val level: Boolean) {
    companion object {
        /** Hauteur d'une ligne : icône de 32 dp (36 avec le logo du rang) et deux lignes de texte ; icône de 32 dp ; icône de 20 dp. */
        private const val ROW_LEVEL_DP = 42
        private const val ROW_DETAIL_DP = 38
        private const val ROW_DP = 24

        /** Titre d'équipe et légendes, avec sa marge ; écart entre deux équipes empilées. */
        private const val HEAD_DP = 26
        private const val TEAM_GAP_DP = 6
        private const val TEAM = 5

        /** [heightDp] 0 : vue pas encore mesurée, le maximum de la forme. */
        fun of(shape: ValorantShape, heightDp: Int, fontScale: Float = 1f): ValorantFit {
            val choices = choices(shape)
            if (heightDp <= 0) return choices.first()
            return choices.firstOrNull { neededDp(shape, it, fontScale) <= heightDp } ?: choices.last()
        }

        private fun choices(shape: ValorantShape) = listOfNotNull(
            ValorantFit(enemies = true, detail = shape.detail, level = shape.detail),
            ValorantFit(enemies = true, detail = shape.detail, level = false),
            ValorantFit(enemies = true, detail = false, level = false),
            // Côte à côte, l'équipe seule a autant de lignes : la retirer ne fait rien gagner.
            ValorantFit(enemies = false, detail = false, level = false).takeIf { !shape.side },
        ).distinct()

        /** Hauteur fixe des lignes des grands widgets ([ValorantShape.stretch] faux), tableau centré. */
        private const val TALL_ROW_DP = 52

        /**
         * Lignes qui se partagent la hauteur : la forme le demande, ou les lignes hautes ([TALL_ROW_DP]) ne tiennent pas
         * dans la place mesurée (thème à cadre, barre du bas).
         */
        fun stretches(shape: ValorantShape, fit: ValorantFit, heightDp: Int, fontScale: Float = 1f): Boolean =
            shape.stretch || (heightDp > 0 && neededDp(shape, fit, fontScale, TALL_ROW_DP) > heightDp)

        /** Hauteur que demande [fit] ; le texte grossi agrandit les lignes. [rowDp] : hauteur imposée des lignes. */
        fun neededDp(shape: ValorantShape, fit: ValorantFit, fontScale: Float = 1f, rowDp: Int? = null): Int {
            val stacked = fit.enemies && !shape.side
            val rows = if (stacked) 2 * TEAM else TEAM
            val heads = if (stacked) 2 else 1
            val row = rowDp ?: when {
                fit.level -> ROW_LEVEL_DP
                fit.detail -> ROW_DETAIL_DP
                else -> ROW_DP
            }
            val scale = maxOf(1f, fontScale)
            return (rows * row * scale + heads * HEAD_DP * scale).roundToInt() + if (stacked) TEAM_GAP_DP else 0
        }
    }
}

/**
 * Ce que montrent pastille, ligne et colonne (une info par niveau d'importance : le score ou le résultat, mon rang, la
 * carte, puis le reste), selon le moment et la place. En partie, le rang laisse sa place à la carte dans les petites
 * formes : il ne bouge pas pendant le match. Testé par `ValorantFitTest`.
 */
data class ValorantGlance(
    val rank: Boolean = false,
    val score: Boolean = false,
    val band: Boolean = false,
    val result: Boolean = false,
    /** Mon ACS et mes K/D/A de la partie finie. */
    val myLine: Boolean = false,
    /** Ma journée (RR, victoires, défaites). */
    val today: Boolean = false,
    /** Mes dernières classées en pastilles gagné / perdu. */
    val history: Boolean = false,
) {
    companion object {
        fun of(v: Valorant, size: WidgetSize): ValorantGlance {
            val long = size.w >= 3 || size.h >= 3
            val ended = v.end?.status == ValorantEndStatus.READY
            val live = v.state == ValorantState.INGAME && v.score != null
            return when {
                ended -> ValorantGlance(result = true, myLine = long && size.family != ShapeFamily.PILL)
                !live -> ValorantGlance(
                    rank = true,
                    today = size.family == ShapeFamily.ROW && size.w >= 3 && v.today != null,
                    history = size.family == ShapeFamily.COLUMN && size.h >= 3 && v.history.isNotEmpty(),
                )
                size.family == ShapeFamily.PILL -> ValorantGlance(score = true)
                size.family == ShapeFamily.COLUMN && !long -> ValorantGlance(score = true)
                long -> ValorantGlance(rank = true, band = true)
                else -> ValorantGlance(band = true)
            }
        }
    }
}
