package app.mate.ui.home

/**
 * Ce que l'Horloge montre dans une forme (#180, direction Cadran) : [dial] = cadran des minutes autour de l'heure
 * (Pastille, Colonne), [dateInDial] = date courte dans le cadran (Pastille), [stacked] = heures au-dessus des minutes
 * (Grand étroit), [seconds] = secondes (Plein écran), [greeting] = « Bonjour » au-dessus ou à côté de l'heure.
 */
data class ClockFace(
    val dial: Boolean,
    val dateInDial: Boolean,
    val stacked: Boolean,
    val seconds: Boolean,
    val greeting: Boolean,
) {
    companion object {
        /** Graduations du cadran : une par minute. */
        const val TICKS = 60

        fun of(size: WidgetSize): ClockFace = when (size.family) {
            ShapeFamily.PILL -> ClockFace(dial = true, dateInDial = true, stacked = false, seconds = false, greeting = false)
            ShapeFamily.COLUMN -> ClockFace(dial = true, dateInDial = false, stacked = false, seconds = false, greeting = false)
            ShapeFamily.ROW, ShapeFamily.BLOCK -> ClockFace(dial = false, dateInDial = false, stacked = false, seconds = false, greeting = true)
            ShapeFamily.LARGE -> ClockFace(dial = false, dateInDial = false, stacked = size.h > size.w, seconds = false, greeting = true)
            ShapeFamily.FULL -> ClockFace(dial = false, dateInDial = false, stacked = false, seconds = true, greeting = true)
        }

        /** Graduations allumées depuis midi du cadran (en haut) : une par minute passée de l'heure. */
        fun lit(minute: Int) = minute.coerceIn(0, TICKS - 1)
    }
}
