package app.mate.ui

/**
 * Entrée d'un écran des thèmes Instrument (ui/FrameTracer) : le contenu des modules est visible tout de suite, le cadre de
 * chaque module se trace par-dessus comme une finition, et les fenêtres LCD s'allument par paliers sans attendre.
 * Calcul pur, sans Android : délais, plafond, avancement des tracés. Coupé (Réduire les animations, famille Accueil coupée,
 * échelle d'animation à zéro) : [plan] est vide, tout est visible.
 */
object FrameTracerModel {
    /** Durée du tracé d'un cadre et écart entre deux cadres (ms). */
    const val TRACE_MS = 440L
    const val STAGGER_MS = 60L

    /** Cadres tracés au plus ; les suivants n'ont pas de trait (la tablette 4.2 ne tient pas plus de traits à l'écran). */
    const val MAX_TRACED = 8

    /** Allumage de la fenêtre LCD en [LCD_STEPS] paliers (au plus 300 ms), effacement du trait (ms). */
    const val LCD_MS = 240L
    const val LCD_STEPS = 4
    const val LINE_FADE_MS = 260L

    /** Arrivée par le balayage orange : le tracé démarre 140 ms après le début de la révélation (maquette). */
    private const val AFTER_REVEAL_MS = 140L
    const val AFTER_SWEEP_MS = SweepModel.COVER_MS + AFTER_REVEAL_MS

    /** Un module : départ (ms depuis l'arrivée de l'écran) et tracé ou simple fondu (au-delà du plafond). */
    data class Frame(val index: Int, val startMs: Long, val traced: Boolean, val lcdStartMs: Long = startMs)

    fun enabled(instrument: Boolean, homeOn: Boolean, reduced: Boolean) = instrument && homeOn && !reduced

    fun plan(count: Int, lead: Long, enabled: Boolean): List<Frame> {
        if (!enabled) return emptyList()
        // Les LCD s'allument dès l'arrivée de l'écran (le balayage le découvre à COVER_MS), sans le décalage des cadres.
        val lcd = (lead - AFTER_REVEAL_MS).coerceAtLeast(0L)
        return List(count) { i -> Frame(i, lead + minOf(i, MAX_TRACED - 1) * STAGGER_MS, i < MAX_TRACED, lcd) }
    }

    /** Fin de la séquence : le trait du dernier cadre fini d'effacer, ou le dernier LCD allumé si c'est plus tard. */
    fun totalMs(plan: List<Frame>): Long = maxOf(
        plan.maxOfOrNull { it.startMs + TRACE_MS + LINE_FADE_MS } ?: 0L,
        plan.maxOfOrNull { it.lcdStartMs + LCD_MS } ?: 0L,
    )

    /** Avancement linéaire (0 à 1) du tracé d'un cadre parti à [startMs] ; la vue y applique sa courbe. */
    fun progress(elapsed: Long, startMs: Long): Float = ((elapsed - startMs).toFloat() / TRACE_MS).coerceIn(0f, 1f)

    /** Opacité du trait : plein pendant le tracé, effacé en [LINE_FADE_MS] ensuite. */
    fun lineAlpha(elapsed: Long, startMs: Long): Float = 1f - ((elapsed - startMs - TRACE_MS).toFloat() / LINE_FADE_MS).coerceIn(0f, 1f)

    /**
     * Opacité du contenu du module : toujours pleine, sauf une fenêtre LCD (quatre paliers à partir de [startMs], qui est
     * `lcdStartMs` du cadre).
     */
    fun contentAlpha(elapsed: Long, startMs: Long, lcd: Boolean): Float {
        if (!lcd) return 1f
        if (elapsed < startMs) return 0f
        val done = ((elapsed - startMs).toFloat() / LCD_MS).coerceIn(0f, 1f)
        return ((done * LCD_STEPS).toInt() + 1).coerceAtMost(LCD_STEPS).toFloat() / LCD_STEPS
    }

    /** Le trait d'un cadre est à l'écran : du départ à la fin de son effacement ; avant et après, rien à redessiner. */
    fun lineVisible(elapsed: Long, startMs: Long, traced: Boolean): Boolean =
        traced && elapsed > startMs && elapsed < startMs + TRACE_MS + LINE_FADE_MS
}
