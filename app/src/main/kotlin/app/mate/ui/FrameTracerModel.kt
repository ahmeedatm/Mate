package app.mate.ui

/**
 * Entrée de l'Accueil des thèmes Instrument (ui/FrameTracer) : le cadre de chaque module se trace, son contenu apparaît,
 * puis les fenêtres LCD s'allument. Calcul pur, sans Android : délais, plafond, repli en fondu, avancement des tracés.
 * Coupé (Réduire les animations, famille Accueil coupée, échelle d'animation à zéro) : [plan] est vide, tout est visible.
 */
object FrameTracerModel {
    /** Durée du tracé d'un cadre et écart entre deux cadres (ms). */
    const val TRACE_MS = 440L
    const val STAGGER_MS = 60L

    /** Cadres tracés au plus ; les suivants arrivent en fondu (la tablette 4.2 ne tient pas plus de traits à l'écran). */
    const val MAX_TRACED = 8

    /** Depuis le départ du cadre : le contenu (fondu) et la fenêtre LCD (paliers) apparaissent (ms). */
    const val CONTENT_DELAY_MS = 300L
    const val LCD_DELAY_MS = 700L

    /** Fondu du contenu, allumage de la fenêtre LCD en [LCD_STEPS] paliers, effacement du trait (ms). */
    const val FADE_MS = 200L
    const val LCD_MS = 300L
    const val LCD_STEPS = 4
    const val LINE_FADE_MS = 260L

    /** Arrivée par le balayage orange : le tracé démarre 140 ms après le début de la révélation (maquette). */
    const val AFTER_SWEEP_MS = SweepModel.COVER_MS + 140L

    /** Un module : départ (ms depuis l'arrivée de l'écran) et tracé ou simple fondu (au-delà du plafond). */
    data class Frame(val index: Int, val startMs: Long, val traced: Boolean)

    fun enabled(instrument: Boolean, homeOn: Boolean, reduced: Boolean) = instrument && homeOn && !reduced

    fun plan(count: Int, lead: Long, enabled: Boolean): List<Frame> {
        if (!enabled) return emptyList()
        return List(count) { i -> Frame(i, lead + minOf(i, MAX_TRACED - 1) * STAGGER_MS, i < MAX_TRACED) }
    }

    /** Fin de la séquence : le dernier départ plus l'allumage LCD, le plus long des effets. */
    fun totalMs(plan: List<Frame>): Long = plan.maxOfOrNull { it.startMs }?.let { it + LCD_DELAY_MS + LCD_MS } ?: 0L

    /** Avancement linéaire (0 à 1) du tracé d'un cadre parti à [startMs] ; la vue y applique sa courbe. */
    fun progress(elapsed: Long, startMs: Long): Float = ((elapsed - startMs).toFloat() / TRACE_MS).coerceIn(0f, 1f)

    /** Opacité du trait : plein pendant le tracé, effacé en [LINE_FADE_MS] ensuite. */
    fun lineAlpha(elapsed: Long, startMs: Long): Float = 1f - ((elapsed - startMs - TRACE_MS).toFloat() / LINE_FADE_MS).coerceIn(0f, 1f)

    /**
     * Opacité du contenu du module : fondu à +300 ms, ou quatre paliers à +700 ms pour une fenêtre LCD. Un cadre en
     * fondu ([traced] faux) n'attend pas le tracé : son contenu monte dès son départ, sans attente de +300 ms.
     */
    fun contentAlpha(elapsed: Long, startMs: Long, lcd: Boolean, traced: Boolean = true): Float {
        val t = elapsed - startMs
        if (!traced) return (t.toFloat() / FADE_MS).coerceIn(0f, 1f)
        if (lcd) {
            if (t < LCD_DELAY_MS) return 0f
            val done = ((t - LCD_DELAY_MS).toFloat() / LCD_MS).coerceIn(0f, 1f)
            return (done * LCD_STEPS).toInt().plus(1).coerceAtMost(LCD_STEPS).toFloat() / LCD_STEPS
        }
        if (t < CONTENT_DELAY_MS) return 0f
        return ((t - CONTENT_DELAY_MS).toFloat() / FADE_MS).coerceIn(0f, 1f)
    }
}
