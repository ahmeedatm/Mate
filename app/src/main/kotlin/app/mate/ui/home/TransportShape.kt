package app.mate.ui.home

/**
 * Ce que la forme posée montre du widget Transports : [rows] passages, [side] = en ligne (Pastille, Ligne) ou empilé,
 * [title] = nom de l'arrêt en tête. Plus grand, plus de passages ; plus petit, le prochain seul. Une mise en page par
 * famille ([ShapeFamily]), jamais un simple étirement.
 */
data class TransportShape(
    val rows: Int,
    val side: Boolean,
    val title: Boolean,
    val sp: Float = 17f,
) {
    companion object {
        private const val MAX = 7
        private const val WIDE = 4

        fun of(size: WidgetSize) = when (size.family) {
            ShapeFamily.PILL -> TransportShape(rows = 1, side = true, title = false)
            ShapeFamily.ROW -> TransportShape(rows = if (size.w >= WIDE) 2 else 1, side = true, title = false, sp = 16f)
            ShapeFamily.COLUMN -> TransportShape(rows = size.h, side = false, title = true)
            ShapeFamily.BLOCK -> TransportShape(rows = minOf(MAX, size.w * size.h - 1), side = false, title = true, sp = 18f)
            ShapeFamily.LARGE -> TransportShape(rows = MAX, side = false, title = true, sp = 19f)
            ShapeFamily.FULL -> TransportShape(rows = MAX, side = false, title = true, sp = 20f)
        }
    }
}
