package app.mate.ui.home

/**
 * Mise en page de la Note selon la forme posée (#181). `side` : l'icône à côté du texte (Pastille, Ligne), sinon
 * au-dessus ; `title` : « Note » à côté de l'icône (Bloc et plus grand) ; le texte prend la plus grande taille qui tient
 * entre `minSp` et `maxSp`, de plus en plus grand avec la forme (très grand en plein écran).
 */
data class NoteShape(val side: Boolean, val title: Boolean, val minSp: Int, val maxSp: Int) {
    companion object {
        private const val MIN_SP = 14

        fun of(size: WidgetSize): NoteShape = when (size.family) {
            ShapeFamily.PILL -> NoteShape(side = true, title = false, minSp = MIN_SP, maxSp = 22)
            ShapeFamily.ROW -> NoteShape(side = true, title = false, minSp = MIN_SP, maxSp = 28)
            ShapeFamily.COLUMN -> NoteShape(side = false, title = false, minSp = MIN_SP, maxSp = 30)
            ShapeFamily.BLOCK -> NoteShape(side = false, title = true, minSp = 16, maxSp = 44)
            ShapeFamily.LARGE -> NoteShape(side = false, title = true, minSp = 18, maxSp = 60)
            ShapeFamily.FULL -> NoteShape(side = false, title = true, minSp = 20, maxSp = 88)
        }
    }
}
