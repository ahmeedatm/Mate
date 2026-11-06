package app.mate.ui.home

/**
 * Ce que le Héros montre dans une forme (#180, direction Cadran). [band] : la Ligne garde le bandeau d'avant ([HeroView],
 * qui s'adapte déjà à sa largeur). Ailleurs : [dial] = cadran des minutes autour de l'heure, [date], [next] = « Ensuite … »,
 * [track] = la journée en ligne de temps ([trackTitles] : avec les titres), [ring] = le prochain rendez-vous en grand
 * dans son anneau, [compl] = météo et eau ([complDown] : l'une sous l'autre).
 */
data class HeroFace(
    val band: Boolean,
    val dial: Boolean = false,
    val date: Boolean = false,
    val next: Boolean = false,
    val track: Boolean = false,
    val trackTitles: Boolean = false,
    val ring: Boolean = false,
    val compl: Boolean = false,
    val complDown: Boolean = false,
) {
    companion object {
        fun of(size: WidgetSize): HeroFace = when (size.family) {
            ShapeFamily.ROW -> HeroFace(band = true)
            ShapeFamily.PILL -> HeroFace(band = false, track = true)
            ShapeFamily.COLUMN -> HeroFace(band = false, dial = true, date = true, compl = true, complDown = true)
            ShapeFamily.BLOCK -> HeroFace(band = false, date = true, next = true, track = true, compl = true)
            ShapeFamily.LARGE -> HeroFace(band = false, date = true, next = true, track = true, trackTitles = true, compl = true)
            ShapeFamily.FULL -> HeroFace(band = false, date = true, track = true, trackTitles = true, ring = true, compl = true)
        }
    }
}

/**
 * Ce que la Journée montre dans une forme (#180, direction Cadran). [band] : la Ligne garde le bandeau d'avant. Ailleurs :
 * [ring] = anneau de compte à rebours avant la réunion ([ringBeside] : à gauche de son titre, sinon au-dessus),
 * [greeting] = salutation et date, [actions] = Focus et Ne pas déranger, [track] = la journée avec les titres,
 * [compl] = météo et eau.
 */
data class DayFace(
    val band: Boolean,
    val ring: Boolean = false,
    val ringBeside: Boolean = false,
    val greeting: Boolean = false,
    val actions: Boolean = false,
    val track: Boolean = false,
    val compl: Boolean = false,
) {
    companion object {
        fun of(size: WidgetSize): DayFace = when (size.family) {
            ShapeFamily.ROW -> DayFace(band = true)
            ShapeFamily.PILL -> DayFace(band = false, ring = true, ringBeside = true)
            ShapeFamily.COLUMN -> DayFace(band = false, ring = true, greeting = true, actions = true)
            ShapeFamily.BLOCK -> DayFace(band = false, ring = true, ringBeside = true, greeting = true, actions = true)
            ShapeFamily.LARGE -> DayFace(band = false, ring = true, ringBeside = size.w > size.h, greeting = true, actions = true, track = true)
            ShapeFamily.FULL -> DayFace(band = false, ring = true, ringBeside = true, greeting = true, actions = true, track = true, compl = true)
        }
    }
}
