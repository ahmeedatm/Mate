package app.mate.ui.home

/**
 * Ce que la forme posée montre du widget Transports : [rows] passages, [side] = en ligne (Pastille, Ligne) ou empilé,
 * [title] = nom de l'arrêt en tête, [big] = le prochain passage en gros (Pastille, Ligne). Plus grand, plus de passages ;
 * plus petit, le prochain seul. Une mise en page par famille ([ShapeFamily]), jamais un simple étirement.
 */
data class TransportShape(
    val rows: Int,
    val side: Boolean,
    val title: Boolean,
    val big: Boolean,
    val sp: Float = 17f,
) {
    companion object {
        private const val MAX = 7

        fun of(size: WidgetSize) = when (size.family) {
            ShapeFamily.PILL -> TransportShape(rows = 1, side = true, title = false, big = true)
            ShapeFamily.ROW -> TransportShape(rows = size.w - 1, side = true, title = false, big = true, sp = 16f)
            ShapeFamily.COLUMN -> TransportShape(rows = size.h + 1, side = false, title = false, big = false)
            ShapeFamily.BLOCK -> TransportShape(rows = minOf(MAX, size.w * size.h - 1), side = false, title = true, big = false, sp = 18f)
            ShapeFamily.LARGE -> TransportShape(rows = MAX, side = false, title = true, big = false, sp = 19f)
            ShapeFamily.FULL -> TransportShape(rows = MAX, side = false, title = true, big = false, sp = 20f)
        }
    }
}
