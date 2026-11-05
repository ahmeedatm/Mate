package app.mate.ui.home

import androidx.annotation.StringRes
import app.mate.R

/**
 * Une forme de la fiche du catalogue : `spot`, la place où « Ajouter le widget » la pose (null : nulle part sur la page) ;
 * `atCase` : cette place est la case touchée. Faux avec une place : grisée « Pas la place ici », le bouton propose la
 * première place libre.
 */
data class ShapeChoice(val size: WidgetSize, val spot: Fit?, val atCase: Boolean)

/** Ordre des formes dans la fiche (glissement et points) : par famille, de la plus petite à la plus grande. */
val SHAPE_ORDER = listOf(
    WidgetSize.S,
    WidgetSize.M, WidgetSize.M3, WidgetSize.W,
    WidgetSize.V, WidgetSize.V3, WidgetSize.V4,
    WidgetSize.L, WidgetSize.L3, WidgetSize.L23, WidgetSize.L33,
    WidgetSize.XL, WidgetSize.T, WidgetSize.XL3, WidgetSize.T3,
    WidgetSize.FULL,
)

/**
 * Les formes de `type` pour un ajout à la case (`col`, `row`) de `page`, dans l'ordre de [SHAPE_ORDER]. La case recule
 * près du bord pour laisser tenir la forme ; sans case touchée (`col` < 0), toute forme qui tient quelque part est posable.
 */
fun HomeLayout.shapeChoices(type: WidgetType, page: Int, col: Int, row: Int): List<ShapeChoice> = SHAPE_ORDER.filter { it in type.sizes }.map { size ->
    val c = col.coerceAtMost(COLS - size.w)
    val r = row.coerceAtMost(ROWS - size.h)
    if (col >= 0 && fits(page, c, r, size)) return@map ShapeChoice(size, Fit(size, c, r), atCase = true)
    val free = freeSpot(page, size)?.let { (fc, fr) -> Fit(size, fc, fr) }
    ShapeChoice(size, free, atCase = col < 0 && free != null)
}

/** Rang, dans [shapeChoices], de la forme sur laquelle la fiche s'ouvre : celle d'un ajout (la première de `sizes`). */
fun openingShape(type: WidgetType) = SHAPE_ORDER.filter { it in type.sizes }.indexOf(type.sizes.first())

/** Nom de la famille de formes, sous l'aperçu (« 2 × 1 · Ligne »). */
@get:StringRes
val ShapeFamily.label
    get() = when (this) {
        ShapeFamily.PILL -> R.string.catalogue_family_pill
        ShapeFamily.ROW -> R.string.catalogue_family_row
        ShapeFamily.COLUMN -> R.string.catalogue_family_column
        ShapeFamily.BLOCK -> R.string.catalogue_family_block
        ShapeFamily.LARGE -> R.string.catalogue_family_large
        ShapeFamily.FULL -> R.string.catalogue_family_full
    }

/** En tête du catalogue, retirées de leur rayon. */
val SUGGESTED = listOf(WidgetType.CLOCK, WidgetType.WEATHER, WidgetType.NEXT)

/** Rayons du catalogue, ceux des issues #180 à #186 (et des blocs de [WidgetSamples]). */
enum class CatalogueShelf(@StringRes val title: Int, val types: List<WidgetType>) {
    SUGGESTIONS(R.string.catalogue_shelf_suggestions, SUGGESTED),
    DAY(R.string.catalogue_shelf_day, listOf(WidgetType.CLOCK, WidgetType.HERO, WidgetType.DAY, WidgetType.DAYLINE, WidgetType.WEATHER, WidgetType.NEXT, WidgetType.AGENDA)),
    LISTS(R.string.catalogue_shelf_lists, listOf(WidgetType.TASKS, WidgetType.SHOPPING, WidgetType.CHECKLIST, WidgetType.NOTE, WidgetType.MEALS, WidgetType.NEWS, WidgetType.MAIL)),
    SOUND(R.string.catalogue_shelf_sound, listOf(WidgetType.AUDIO, WidgetType.MEDIA, WidgetType.MIXER, WidgetType.YTMUSIC)),
    COMPUTER(R.string.catalogue_shelf_computer, listOf(WidgetType.KEYS, WidgetType.APPS, WidgetType.OPEN, WidgetType.PAD, WidgetType.SCREEN, WidgetType.PERF, WidgetType.WEB)),
    CALLS(R.string.catalogue_shelf_calls, listOf(WidgetType.DISCORD, WidgetType.MEETING, WidgetType.CALL, WidgetType.PRESENT)),
    DEV(R.string.catalogue_shelf_dev, listOf(WidgetType.AGENTS, WidgetType.JIRA, WidgetType.GITLAB, WidgetType.PIPELINES, WidgetType.GITHUB, WidgetType.ACTIONS, WidgetType.METRICS)),
    HOME(R.string.catalogue_shelf_home, listOf(WidgetType.FOCUS, WidgetType.WATER, WidgetType.AWAY, WidgetType.HA, WidgetType.VALORANT)),
}

/** Les rayons à montrer, avec leurs widgets proposés (`offered`) ; une suggestion n'est pas répétée dans son rayon. */
fun catalogueShelves(offered: (WidgetType) -> Boolean): List<Pair<CatalogueShelf, List<WidgetType>>> {
    val suggested = SUGGESTED.filter(offered).toSet()
    return CatalogueShelf.entries.map { shelf ->
        shelf to shelf.types.filter { offered(it) && (shelf == CatalogueShelf.SUGGESTIONS || it !in suggested) }
    }.filter { it.second.isNotEmpty() }
}
