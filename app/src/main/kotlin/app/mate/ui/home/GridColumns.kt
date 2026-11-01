package app.mate.ui.home

/**
 * Colonnes de la grille de l'Accueil (WidgetGrid), en px : `cols` cases de [cellW] séparées par `gap`, à partir de
 * `start`, sur `width`. `spine` > 0 (thème Spider-Man, c_spine) : la gouttière du milieu devient une colonne vide de
 * cette largeur, centrée sur la grille, où le logo du fond reste net ; un widget à cheval sur le milieu la traverse.
 */
class GridColumns(private val start: Int, width: Int, private val gap: Int, spine: Int, private val cols: Int) {
    /** Largeur prise en plus de la gouttière du milieu. */
    private val extra = if (spine > gap && cols > 1) spine - gap else 0
    private val half = cols / 2
    val cellW = (width - gap * (cols - 1) - extra) / cols
    private val step = cellW + gap

    fun left(col: Int) = start + col * step + if (col >= half) extra else 0

    /** Largeur de `span` cases à partir de `col`, gouttières (et colonne vide) comprises. */
    fun width(col: Int, span: Int) = left(col + span - 1) + cellW - left(col)

    /** Position de `x` en cases depuis le bord gauche, fractions comprises ; la colonne vide compte pour rien. */
    fun cellsAt(x: Float): Float {
        val spineStart = start + half * step - gap
        val inside = when {
            extra == 0 || x <= spineStart -> x - start
            x < spineStart + extra -> (spineStart - start).toFloat()
            else -> x - start - extra
        }
        return inside / step
    }

    /** Un widget de `span` cases à partir de `col` passe sur la colonne vide (il en couvre les deux côtés). */
    fun crossesMiddle(col: Int, span: Int) = extra > 0 && col < half && col + span > half

    /** Case sous `x`, null hors de la grille ou dans la colonne vide (gouttières : la case de gauche, comme avant). */
    fun colAt(x: Float): Int? {
        if (x < start) return null
        val spineStart = start + half * step - gap
        if (extra > 0 && x >= spineStart + gap && x < spineStart + gap + extra) return null
        val col = cellsAt(x).toInt()
        return col.takeIf { it in 0 until cols }
    }
}
