package app.mate.ui.home

/** Touches du widget Présentation. */
enum class PresentKey { PREVIOUS, NEXT, START, END, MIC }

/** Dessin d'une touche : icône sur le nom (grande touche), côte à côte (petite touche), ou icône seule. */
enum class PresentStyle { STACKED, INLINE, ICON }

/** Une case d'une rangée : une touche, ou plusieurs empilées (Début et Fin du Grand en largeur). */
data class PresentCell(val keys: List<PresentKey>, val weight: Float, val style: PresentStyle)

/** Une rangée de touches et sa part de la hauteur. */
data class PresentRow(val cells: List<PresentCell>, val weight: Float)

/**
 * Mise en page d'une forme (#184, télécommande) : [header] titre et chrono ([sideHeader] : à gauche des touches, en
 * Ligne), taille du chrono [chronoSp], rangées de touches, et [scale] des icônes et des noms (1 : celles du Bloc).
 */
data class PresentPlan(val header: Boolean, val sideHeader: Boolean, val chronoSp: Float, val rows: List<PresentRow>, val scale: Float)

private fun cell(key: PresentKey, weight: Float, style: PresentStyle) = PresentCell(listOf(key), weight, style)

/** Suivante est le geste principal : présente dans chaque forme, toujours la plus grande. */
fun planPresent(size: WidgetSize): PresentPlan = when (size.family) {
    ShapeFamily.PILL -> PresentPlan(false, false, 0f, listOf(PresentRow(listOf(cell(PresentKey.NEXT, 1f, PresentStyle.STACKED)), 1f)), 1f)
    ShapeFamily.ROW -> {
        val keys = when (size.w) {
            2 -> listOf(PresentKey.PREVIOUS, PresentKey.NEXT)
            3 -> listOf(PresentKey.PREVIOUS, PresentKey.NEXT, PresentKey.MIC)
            else -> listOf(PresentKey.START, PresentKey.PREVIOUS, PresentKey.NEXT, PresentKey.END, PresentKey.MIC)
        }
        val cells = keys.map { cell(it, if (it == PresentKey.NEXT) MAIN_WEIGHT else 1f, PresentStyle.INLINE) }
        PresentPlan(true, true, ROW_CHRONO_SP, listOf(PresentRow(cells, 1f)), 1f)
    }
    ShapeFamily.COLUMN -> PresentPlan(
        header = true,
        sideHeader = false,
        chronoSp = COLUMN_CHRONO_SP,
        rows = listOf(
            PresentRow(listOf(cell(PresentKey.NEXT, 1f, PresentStyle.STACKED)), 2f),
            // Précédente côte à côte : elle tient dans une colonne basse (1 × 2 sur une rangée de 92 px).
            PresentRow(listOf(cell(PresentKey.PREVIOUS, 1f, PresentStyle.INLINE)), 1f),
            PresentRow(listOf(PresentKey.START, PresentKey.END, PresentKey.MIC).map { cell(it, 1f, PresentStyle.ICON) }, COLUMN_TOOLS_WEIGHT),
        ),
        scale = COLUMN_SCALE,
    )
    // Grand en largeur (4 × 2, 4 × 3) : tout sur une rangée, Début et Fin empilés.
    ShapeFamily.LARGE -> if (size.w == COLS) {
        val row = PresentRow(
            listOf(
                cell(PresentKey.PREVIOUS, 2f, PresentStyle.STACKED),
                cell(PresentKey.NEXT, 3.2f, PresentStyle.STACKED),
                PresentCell(listOf(PresentKey.START, PresentKey.END), 1.5f, PresentStyle.INLINE),
                cell(PresentKey.MIC, 1.5f, PresentStyle.STACKED),
            ),
            1f,
        )
        PresentPlan(true, false, BLOCK_CHRONO_SP, listOf(row), 1f)
    } else {
        twoRows(BLOCK_CHRONO_SP, 1f)
    }
    ShapeFamily.FULL -> twoRows(FULL_CHRONO_SP, FULL_SCALE).let { it.copy(rows = listOf(it.rows[0].copy(weight = 2f), it.rows[1])) }
    ShapeFamily.BLOCK -> twoRows(BLOCK_CHRONO_SP, 1f)
}

/** Précédente et Suivante en haut, Début, Fin et Micro dessous (le Bloc d'avant les 16 formes). */
private fun twoRows(chronoSp: Float, scale: Float) = PresentPlan(
    header = true,
    sideHeader = false,
    chronoSp = chronoSp,
    rows = listOf(
        PresentRow(listOf(cell(PresentKey.PREVIOUS, 1f, PresentStyle.STACKED), cell(PresentKey.NEXT, 1.6f, PresentStyle.STACKED)), 1.5f),
        PresentRow(listOf(cell(PresentKey.START, 1f, PresentStyle.INLINE), cell(PresentKey.END, 1f, PresentStyle.INLINE), cell(PresentKey.MIC, 1.2f, PresentStyle.INLINE)), 1f),
    ),
    scale = scale,
)

private const val MAIN_WEIGHT = 1.6f
private const val ROW_CHRONO_SP = 26f
private const val COLUMN_CHRONO_SP = 28f
private const val BLOCK_CHRONO_SP = 34f
private const val FULL_CHRONO_SP = 56f
private const val COLUMN_TOOLS_WEIGHT = 0.8f
private const val COLUMN_SCALE = 0.85f
private const val FULL_SCALE = 1.6f
