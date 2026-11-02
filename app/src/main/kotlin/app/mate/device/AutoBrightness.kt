package app.mate.device

import kotlin.math.abs
import kotlin.math.log10

/**
 * Luminosité de l'écran déduite de la lumière de la pièce (capteur de luminosité ambiante, en lux).
 * L'œil juge la lumière à l'échelle logarithmique : la courbe monte régulièrement entre [DARK_LUX] et [BRIGHT_LUX].
 */
object AutoBrightness {

    /** Niveau dans une pièce sombre : assez bas pour le soir, encore lisible. */
    const val DARK_LEVEL = 0.1f
    private const val DARK_LUX = 3f
    private const val BRIGHT_LUX = 1_000f

    /** Écart minimal de niveau qui vaut la peine de changer l'écran (évite le scintillement du capteur). */
    private const val MIN_STEP = 0.05f

    /** Niveau de 0,1 à 1 pour [lux] ; une valeur invalide donne le niveau sombre. */
    fun level(lux: Float): Float {
        if (lux.isNaN() || lux <= DARK_LUX) return DARK_LEVEL
        if (lux >= BRIGHT_LUX) return 1f
        val part = (log10(lux) - log10(DARK_LUX)) / (log10(BRIGHT_LUX) - log10(DARK_LUX))
        return DARK_LEVEL + (1f - DARK_LEVEL) * part
    }

    fun worthChange(current: Float, target: Float): Boolean = abs(target - current) >= MIN_STEP
}
