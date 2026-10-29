package com.unowhy.companion.focus

/** Durées du Pomodoro : préréglages fixes et durées perso, bornées (logique pure, testée sur la JVM). */
object FocusPresets {

    data class Preset(val focus: Int, val pause: Int)

    /** Pastilles de l'écran Focus, avant « Perso ». */
    val FIXED = listOf(Preset(25, 5), Preset(50, 10), Preset(15, 3))

    /** Durées perso proposées tant qu'aucune n'a été enregistrée. */
    val DEFAULT_CUSTOM = Preset(30, 5)

    val FOCUS_RANGE = 5..120
    val PAUSE_RANGE = 1..30
    const val FOCUS_STEP = 5
    const val PAUSE_STEP = 1

    /** Durées relues (préférences, ordre venu d'ailleurs) ramenées dans les bornes. */
    fun bounded(focus: Int, pause: Int) = Preset(focus.coerceIn(FOCUS_RANGE), pause.coerceIn(PAUSE_RANGE))

    fun isFixed(preset: Preset) = preset in FIXED

    fun stepFocus(value: Int, up: Boolean) = step(value, up, FOCUS_STEP, FOCUS_RANGE)

    fun stepPause(value: Int, up: Boolean) = step(value, up, PAUSE_STEP, PAUSE_RANGE)

    /** − / + d'un pas, calé sur les multiples du pas (23 → 25 ou 20), sans sortir des bornes. */
    internal fun step(value: Int, up: Boolean, step: Int, range: IntRange): Int {
        val next = if (up) (value / step + 1) * step else ((value + step - 1) / step - 1) * step
        return next.coerceIn(range)
    }
}
