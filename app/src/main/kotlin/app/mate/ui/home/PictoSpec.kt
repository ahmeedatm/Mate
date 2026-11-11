package app.mate.ui.home

/**
 * Dessin du picto d'une ligne, sans rien de graphique : forme, rapport largeur / hauteur, rayon, couleurs. Métro rond,
 * RER et train carré arrondi, bus rectangle plus large, tram blanc avec barres haut et bas. Seuls les pictos prennent
 * les couleurs officielles ; une ligne inconnue prend celles du thème ([neutralFill], [neutralText]).
 */
data class PictoSpec(
    val form: Form,
    val widthRatio: Float,
    val radiusRatio: Float,
    val fill: Int,
    val text: Int,
    val bars: Boolean = false,
    val barColor: Int = fill,
) {
    enum class Form { CIRCLE, RECT }

    companion object {
        private const val SQUARE_RADIUS = 0.22f
        private const val BUS_RADIUS = 0.12f
        private const val BUS_WIDTH = 1.45f
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** [neutralText] : couleur de texte du thème (le picto neutre est plein de cette couleur, texte à la couleur du fond). */
        fun of(line: TransportLines.Line?, neutralBackground: Int, neutralText: Int): PictoSpec {
            if (line == null) return PictoSpec(Form.CIRCLE, 1f, 0.5f, neutralText, neutralBackground)
            return when (line.mode) {
                TransportLines.Mode.METRO -> PictoSpec(Form.CIRCLE, 1f, 0.5f, line.bg, line.fg)
                TransportLines.Mode.RER, TransportLines.Mode.TRAIN -> PictoSpec(Form.RECT, 1f, SQUARE_RADIUS, line.bg, line.fg)
                TransportLines.Mode.BUS -> PictoSpec(Form.RECT, BUS_WIDTH, BUS_RADIUS, line.bg, line.fg)
                TransportLines.Mode.TRAM -> PictoSpec(Form.RECT, 1f, BUS_RADIUS, WHITE, line.bg, bars = true, barColor = line.bg)
            }
        }
    }
}
