package app.mate.ui.mixer

import kotlin.math.log10
import kotlin.math.roundToInt

/** Calculs du mélangeur, sans Android (testés dans app/src/test). */
object MixerMath {
    /** Bas de l'échelle des vumètres : en dessous, aucune LED. */
    const val FLOOR_DB = -48.0

    /**
     * LED allumées pour un niveau 0-100 (amplitude, comme le donne Windows) : échelle en décibels, sinon une voix
     * normale n'allumerait que le bas du vumètre.
     */
    fun ledsLit(level: Int, leds: Int): Int {
        if (level <= 0) return 0
        val db = 20 * log10(level.coerceAtMost(100) / 100.0)
        return (((db - FLOOR_DB) / -FLOOR_DB) * leds).roundToInt().coerceIn(0, leds)
    }

    /** Volume après avoir glissé le curseur de `dy` pixels (vers le bas : positif) sur une course de `travel` pixels. */
    fun dragVolume(start: Int, dy: Float, travel: Float): Int =
        if (travel <= 0f) start else (start - dy / travel * 100).roundToInt().coerceIn(0, 100)

    /** Couleur d'une LED selon sa place (0 = en bas) : 3 rouges en haut, 4 jaunes, le reste vert. */
    fun ledZone(index: Int, leds: Int): Int = when {
        index >= leds - 3 -> 2
        index >= leds - 7 -> 1
        else -> 0
    }
}
