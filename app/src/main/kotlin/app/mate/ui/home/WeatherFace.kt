package app.mate.ui.home

/**
 * Ce que la Météo montre dans une forme (#180, direction Cadran) : [curve] = courbe des prochaines heures (en travers
 * ou de haut en bas), [details] = ciel, min / max et ville à côté de la température, [days] = les 4 jours suivants,
 * [air] = qualité de l'air. Plus grand, plus de choses ; la Pastille garde la température et une petite courbe.
 */
data class WeatherFace(val curve: Curve, val details: Boolean, val days: Boolean, val air: Boolean) {
    enum class Curve { NONE, ACROSS, DOWN }

    companion object {
        fun of(size: WidgetSize): WeatherFace = when (size.family) {
            ShapeFamily.PILL -> WeatherFace(Curve.ACROSS, details = false, days = false, air = false)
            ShapeFamily.ROW -> WeatherFace(if (size.w == COLS) Curve.ACROSS else Curve.NONE, details = true, days = false, air = false)
            ShapeFamily.COLUMN -> WeatherFace(Curve.DOWN, details = false, days = false, air = false)
            ShapeFamily.BLOCK -> WeatherFace(if (maxOf(size.w, size.h) >= 3) Curve.ACROSS else Curve.NONE, details = true, days = true, air = true)
            ShapeFamily.LARGE, ShapeFamily.FULL -> WeatherFace(Curve.ACROSS, details = true, days = true, air = true)
        }
    }
}
