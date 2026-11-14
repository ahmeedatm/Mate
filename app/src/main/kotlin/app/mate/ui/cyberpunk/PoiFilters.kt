package app.mate.ui.cyberpunk

import android.content.Context
import app.mate.Prefs
import app.mate.net.CyberpunkPoi
import app.mate.net.PoiCategory

/** Filtres du panneau de la carte : catégories cachées (gardées par tablette), comptes et points restants. Tout est visible par défaut. */
object PoiFilters {
    fun hidden(context: Context): Set<PoiCategory> = Prefs.cyberpunkPoiHidden(context).mapNotNull { key -> PoiCategory.entries.firstOrNull { it.key == key } }.toSet()

    fun setHidden(context: Context, hidden: Set<PoiCategory>) = Prefs.setCyberpunkPoiHidden(context, hidden.map { it.key }.toSet())

    fun toggled(hidden: Set<PoiCategory>, cat: PoiCategory, visible: Boolean): Set<PoiCategory> = if (visible) hidden - cat else hidden + cat

    /** Toutes les catégories, dans l'ordre du panneau, même à zéro. */
    fun counts(points: List<CyberpunkPoi>): Map<PoiCategory, Int> {
        val byCat = points.groupingBy { it.cat }.eachCount()
        return PoiCategory.entries.associateWith { byCat[it] ?: 0 }
    }

    fun visible(points: List<CyberpunkPoi>, hidden: Set<PoiCategory>): List<CyberpunkPoi> = if (hidden.isEmpty()) points else points.filter { it.cat !in hidden }
}
