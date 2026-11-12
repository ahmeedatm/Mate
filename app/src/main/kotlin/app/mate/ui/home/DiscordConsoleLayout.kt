package app.mate.ui.home

import androidx.annotation.StringRes
import app.mate.R
import app.mate.net.DeckTile

/**
 * Ce que la console Discord (thèmes Instrument) montre dans une forme : [keys] les touches, celle de l'état de l'appel
 * d'abord ; [lcd] la fenêtre LCD (salon, qui parle, vumètres) ; [labels] les libellés sous les touches ; [vertical] les
 * vumètres en colonne.
 */
data class ConsolePlan(val keys: List<DeckTile>, val lcd: Boolean, val labels: Boolean, val vertical: Boolean)

object ConsoleMetrics {
    /** Place d'une touche ronde du dock (`item_dock_tool`, 68 dp) et de son écart. */
    const val KEY_SLOT_DP = 76

    /** Touche avec son libellé (DEL, capuchon, texte) ; sans libellé, elle tient dans une rangée basse (68 dp utiles). */
    const val KEY_WITH_LABEL_DP = 84

    /** Hauteur minimale de la fenêtre LCD au-dessus des touches. */
    const val LCD_MIN_DP = 64

    /** Pastille : sous cette hauteur utile, la touche seule (pas de LCD au-dessus). */
    const val PILL_LCD_DP = 120

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
    val slots = when (family) {
        ShapeFamily.PILL -> 1
        ShapeFamily.ROW -> (innerW * ROW_KEYS_SHARE / KEY_SLOT_DP).toInt()
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
    val keyRoom = if (family == ShapeFamily.ROW || !lcd) innerH else innerH - LCD_MIN_DP
    ConsolePlan(keys, lcd, labels = keyRoom >= KEY_WITH_LABEL_DP, vertical = family == ShapeFamily.COLUMN)
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
