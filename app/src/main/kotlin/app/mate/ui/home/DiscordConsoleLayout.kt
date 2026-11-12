package app.mate.ui.home

import androidx.annotation.StringRes
import app.mate.R
import app.mate.net.DeckTile

/**
 * Ce que la console Discord (thèmes Instrument) montre dans une forme : [keys] les touches, celle de l'état de l'appel
 * d'abord ; [lcd] la fenêtre LCD (salon, qui parle, vumètres) ; [labels] les libellés sous les touches ; [vertical] les
 * vumètres en colonne ; [side] les touches à droite de la fenêtre (Ligne, et carte très large comme le Grand 4 × 2).
 */
data class ConsolePlan(val keys: List<DeckTile>, val lcd: Boolean, val labels: Boolean, val vertical: Boolean, val side: Boolean)

object ConsoleMetrics {
    /** Place d'une touche ronde du dock (`item_dock_tool`, 68 dp) et de son écart. */
    const val KEY_SLOT_DP = 76

    /** Touche avec son libellé (DEL, capuchon, texte) ; sans libellé, elle tient dans une rangée basse (68 dp utiles). */
    const val KEY_WITH_LABEL_DP = 84

    /** Hauteur minimale de la fenêtre LCD au-dessus des touches. */
    const val LCD_MIN_DP = 64

    /** Pastille : sous cette hauteur utile, la touche seule ; au-dessus, une ligne de LCD de [PILL_LINE_DP] la coiffe. */
    const val PILL_LCD_DP = 110
    const val PILL_LINE_DP = 44

    /** Carte au moins 4 fois plus large que haute : touches à droite, la fenêtre LCD garde toute la hauteur. */
    const val SIDE_RATIO = 4

    /** Ligne : les touches prennent au plus la moitié de la largeur, la fenêtre LCD le reste. */
    const val ROW_KEYS_SHARE = 0.5
}

/**
 * [tiles] : touches de la page montrées dans l'état de l'appel (`tileShown`), dans l'ordre de la page ; [innerW] ×
 * [innerH] : place utile de la carte, en dp. Quand la place manque, « Quitter » (la dernière touche de l'appel) reste,
 * « Ouvrir » part la première.
 */
fun planConsole(family: ShapeFamily, tiles: List<DeckTile>, innerW: Int, innerH: Int): ConsolePlan = with(ConsoleMetrics) {
    val main = tiles.firstOrNull { it.whenState != null } ?: tiles.firstOrNull()
    val side = family == ShapeFamily.ROW ||
        (family != ShapeFamily.PILL && family != ShapeFamily.COLUMN && innerH > 0 && innerW >= SIDE_RATIO * innerH)
    val slots = when {
        family == ShapeFamily.PILL -> 1
        side -> (innerW * ROW_KEYS_SHARE / KEY_SLOT_DP).toInt()
        else -> innerW / KEY_SLOT_DP
    }.coerceAtLeast(1)
    val rest = tiles - listOfNotNull(main).toSet()
    val call = rest.filter { it.whenState != null }
    val other = rest.filter { it.whenState == null }
    val keys = if (main == null) {
        emptyList()
    } else {
        val calls = if (slots > 1) fitKeys(call, slots - 1) else emptyList()
        listOf(main) + calls + other.take((slots - 1 - calls.size).coerceAtLeast(0))
    }
    val lcd = family != ShapeFamily.PILL || innerH >= PILL_LCD_DP
    val keyRoom = when {
        side || !lcd -> innerH
        family == ShapeFamily.PILL -> innerH - PILL_LINE_DP
        else -> innerH - LCD_MIN_DP
    }
    ConsolePlan(keys, lcd, labels = keyRoom >= KEY_WITH_LABEL_DP, vertical = family == ShapeFamily.COLUMN, side = side)
}

/**
 * Photos de la fenêtre LCD : [fits] places le long de la rangée (la dernière devient « +N » quand le salon est plein,
 * `planMembers`), [avatarDp] diamètre d'une photo, [slotDp] place de chacun (photo, halo, nom, écart).
 */
data class FacePlan(val fits: Int, val avatarDp: Float, val slotDp: Float)

object FaceMetrics {
    const val MIN_AVATAR_DP = 24f
    const val MAX_AVATAR_DP = 64f

    /** Anneau de qui parle autour de la photo, des deux côtés : 2 dp d'écart et 3 dp de trait. */
    const val HALO_DP = 10f

    /** Écart minimal entre deux places. */
    const val GAP_DP = 8f
}

/**
 * [count] participants sur une rangée de [lengthDp] (largeur ; hauteur pour la Colonne) ; [maxAvatarDp] : la plus
 * grande photo que l'autre axe laisse ; [nameDp] : largeur d'un nom court (0 sans nom), qui tient toujours dans sa
 * place. La place se partage entre ceux qui tiennent : peu de monde, grandes photos espacées ; salon plein, plus petites.
 */
fun planFaces(count: Int, lengthDp: Float, maxAvatarDp: Float, nameDp: Float): FacePlan = with(FaceMetrics) {
    val minSlot = maxOf(MIN_AVATAR_DP + HALO_DP, nameDp) + GAP_DP
    val fits = if (count <= 0) 0 else minOf(count, (lengthDp / minSlot).toInt())
    if (fits == 0) return FacePlan(0, 0f, 0f)
    val slot = lengthDp / fits
    val top = maxAvatarDp.coerceIn(MIN_AVATAR_DP, MAX_AVATAR_DP)
    FacePlan(fits, (slot - GAP_DP - HALO_DP).coerceIn(MIN_AVATAR_DP, top), slot)
}

/** Libellé court sous une touche de la console, tiré de son symbole ; null : le libellé de la touche (touche maison). */
@StringRes
fun consoleLabel(tile: DeckTile): Int? = when {
    tile.symbol == "mic_off" -> R.string.console_key_mic
    tile.symbol == "volume_off" -> R.string.console_key_deafen
    tile.symbol == "call_end" -> R.string.console_key_leave
    tile.symbol == "present" -> R.string.console_key_share
    tile.symbol == null && tile.type == "app" -> R.string.console_key_open
    else -> null
}
