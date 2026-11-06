package app.mate.ui.home

/**
 * Mise en page des Actus selon la forme posée (#181). `titles` : combien de titres montrer (0 : la Pastille ne montre
 * que le nombre de titres pas encore vus) ; `side` : l'icône à côté ; `maxLines` : lignes par titre, plus en grand.
 */
data class NewsShape(val titles: Int, val side: Boolean, val title: Boolean, val maxLines: Int) {
    companion object {
        /** Le flux en donne cinq au plus (`MAX_ITEMS` de backend/src/news.ts). */
        const val MAX_TITLES = 5

        fun of(size: WidgetSize): NewsShape = when (size.family) {
            ShapeFamily.PILL -> NewsShape(titles = 0, side = true, title = false, maxLines = 1)
            ShapeFamily.ROW -> NewsShape(titles = size.w - 1, side = true, title = false, maxLines = 2)
            ShapeFamily.COLUMN -> NewsShape(titles = size.h, side = false, title = false, maxLines = 3)
            ShapeFamily.BLOCK -> NewsShape(titles = MAX_TITLES, side = false, title = true, maxLines = 1)
            ShapeFamily.LARGE -> NewsShape(titles = MAX_TITLES, side = false, title = true, maxLines = 2)
            ShapeFamily.FULL -> NewsShape(titles = MAX_TITLES, side = false, title = true, maxLines = 3)
        }
    }
}

/** Titres du flux pas encore ouverts depuis la tablette. */
fun unseenCount(titles: List<String>, seen: Set<String>): Int = titles.count { it !in seen }

/** Titres vus après avoir ouvert `opened` : ceux qui ont quitté le flux sont oubliés (la config ne grossit pas). */
fun seenAfter(seen: Set<String>, titles: List<String>, opened: String?): Set<String> =
    (seen + listOfNotNull(opened)).filterTo(LinkedHashSet()) { it in titles }
