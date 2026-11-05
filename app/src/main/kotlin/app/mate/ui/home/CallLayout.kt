package app.mate.ui.home

import app.mate.net.DeckTile
import app.mate.net.VoiceMember
import kotlin.math.roundToInt

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

/** Les [count] touches qui tiennent en Ligne : les premières de la page, et toujours la dernière (« Quitter l'appel »). */
fun fitKeys(keys: List<DeckTile>, count: Int): List<DeckTile> = when {
    keys.size <= count -> keys
    count <= 1 -> keys.takeLast(1)
    else -> keys.take(count - 1) + keys.last()
}

/** Cotes des touches une à une (Pastille, Ligne, îlot), en dp : plafonds par famille, plancher de 48 (cible tactile). */
object CallKeyMetrics {
    const val MIN_KEY_DP = 48
    const val PILL_KEY_DP = 80
    const val PILL_ROUND_DP = 96
    const val ROW_KEY_DP = 64
    const val ROW_ROUND_DP = 80
    const val COLUMN_ROUND_DP = 96
    const val BLOCK_ROUND_DP = 88
    const val LARGE_ROUND_DP = 104
    const val FULL_ROUND_DP = 120
    const val SMALL_SHARE = 0.62
    const val KEY_GAP_DP = 12

    /** Ligne de la télécommande : les touches prennent jusqu'à 65 % de la largeur, une touche au moins 84 dp (libellé). */
    const val ROW_KEYS_SHARE = 0.65
    const val ROW_LABEL_WIDTH_DP = 84

    /** Légende sous le gros rond de la Pastille de l'îlot (texte de 14 sp et sa marge). */
    const val CAPTION_DP = 28
    const val HALO_RING_DP = 3
    const val HALO_GAP_DP = 3
}

/**
 * Cotes des touches une à une, en dp. [main] : la touche seule ou le gros rond ; [small] : les autres ; [count] : petites
 * touches qui tiennent ; [width] : largeur d'une touche de Ligne de la télécommande (0 : à sa taille).
 */
data class CallKeySizes(val main: Int, val mainLabel: Boolean, val small: Int, val smallLabel: Boolean, val count: Int, val width: Int)

/** Cotes tirées de la place utile de la carte ([innerW] × [innerH] dp) ; [lineDp] : une ligne de libellé ; [keyCount] : petites touches. */
fun callKeySizes(family: ShapeFamily, island: Boolean, innerW: Int, innerH: Int, lineDp: Int, keyCount: Int): CallKeySizes = with(CallKeyMetrics) {
    val labelRoom = TileMetrics.LABEL_GAP_DP + 2 * lineDp
    val withLabel = innerH >= MIN_KEY_DP + labelRoom
    val plate = { max: Int -> (if (withLabel) innerH - labelRoom else innerH).coerceIn(MIN_KEY_DP, max) }
    if (family == ShapeFamily.PILL) {
        if (island) return CallKeySizes(minOf(innerH - CAPTION_DP, innerW).coerceIn(MIN_KEY_DP, PILL_ROUND_DP), false, 0, false, 0, 0)
        return CallKeySizes(plate(PILL_KEY_DP), withLabel, 0, false, 0, 0)
    }
    if (family == ShapeFamily.ROW && !island) {
        val key = plate(ROW_KEY_DP)
        val width = maxOf(key, if (withLabel) ROW_LABEL_WIDTH_DP else key) + KEY_GAP_DP
        val count = ((innerW * ROW_KEYS_SHARE) / width).toInt().coerceAtLeast(1)
        return CallKeySizes(0, false, key, withLabel, count, width - KEY_GAP_DP)
    }
    val main = when (family) {
        ShapeFamily.ROW -> (innerH - 2 * (HALO_GAP_DP + HALO_RING_DP)).coerceIn(MIN_KEY_DP, ROW_ROUND_DP)
        ShapeFamily.COLUMN -> (innerW / 2).coerceIn(MIN_KEY_DP, COLUMN_ROUND_DP)
        ShapeFamily.LARGE -> LARGE_ROUND_DP
        ShapeFamily.FULL -> FULL_ROUND_DP
        else -> BLOCK_ROUND_DP
    }
    val small = (main * SMALL_SHARE).roundToInt().coerceAtLeast(MIN_KEY_DP)
    CallKeySizes(main, false, small, false, keyCount, 0)
}
