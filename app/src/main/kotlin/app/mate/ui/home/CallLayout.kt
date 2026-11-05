package app.mate.ui.home

import app.mate.net.DeckTile
import app.mate.net.VoiceMember

/**
 * Ce qu'un widget d'appel montre dans une famille de formes (#184). Télécommande (Réunion, Appel) : les touches d'abord,
 * toutes dans la grille, plus grandes en Grand et Plein écran. Îlot (Discord) : un gros rond pour la touche de l'état
 * ([main]), les autres en petits ronds ([keys]), et qui parle dès la Colonne.
 *
 * [main] : la touche seule de la Pastille, ou le gros rond de l'îlot ; [header] : titre et état ; [members] : les
 * participants (photos ou noms) ; [audio] : le son propre à l'appli ; [bigKeys] : grandes touches dans la grille.
 */
data class CallPlan(
    val main: DeckTile?,
    val keys: List<DeckTile>,
    val header: Boolean,
    val members: Boolean,
    val audio: Boolean,
    val bigKeys: Boolean,
)

/** Petits ronds de l'îlot qui tiennent dans chaque famille, à côté du gros. */
private val ISLAND_KEYS = mapOf(
    ShapeFamily.PILL to 0,
    ShapeFamily.ROW to 3,
    ShapeFamily.COLUMN to 2,
    ShapeFamily.BLOCK to 3,
    ShapeFamily.LARGE to 4,
    ShapeFamily.FULL to 4,
)

/** [tiles] : les touches de la page montrées dans l'état de l'appel (`tileShown`), dans l'ordre de la page. */
fun planCall(family: ShapeFamily, island: Boolean, tiles: List<DeckTile>): CallPlan {
    // La touche propre à l'état de l'appel (micro, rejoindre), sinon la première (ouvrir l'appli).
    val first = tiles.firstOrNull { it.whenState != null } ?: tiles.firstOrNull()
    val roomy = family == ShapeFamily.BLOCK || family == ShapeFamily.LARGE || family == ShapeFamily.FULL
    if (family == ShapeFamily.PILL) {
        return CallPlan(first, emptyList(), header = first == null, members = false, audio = false, bigKeys = true)
    }
    if (!island) {
        return CallPlan(null, tiles, header = true, members = roomy, audio = roomy, bigKeys = family == ShapeFamily.LARGE || family == ShapeFamily.FULL)
    }
    // Les touches de l'appel avant « Ouvrir » : en appel, sourdine et quitter servent plus.
    val rest = (tiles - listOfNotNull(first).toSet()).sortedBy { it.whenState == null }
    return CallPlan(
        main = first,
        keys = rest.take(ISLAND_KEYS.getValue(family)),
        header = true,
        members = roomy || family == ShapeFamily.COLUMN,
        audio = roomy,
        bigKeys = false,
    )
}

/** Participant mis en avant (Plein écran de l'îlot) : qui parle, sinon le dernier à avoir parlé, sinon le premier arrivé. */
fun featuredMember(members: List<VoiceMember>, speaking: Set<String>, lastSpoke: Map<String, Long>): VoiceMember? =
    members.firstOrNull { it.id in speaking }
        ?: members.filter { it.id in lastSpoke }.maxByOrNull { lastSpoke.getValue(it.id) }
        ?: members.firstOrNull()
