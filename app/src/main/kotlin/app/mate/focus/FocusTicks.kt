package app.mate.focus

/** Graduations du cadran Focus : une par minute d'une séance de 25 min, allumées seulement quand le chrono tourne. */
object FocusTicks {
    const val COUNT = 25

    /** Nombre de graduations allumées (les dernières du cadran) : minutes restantes entamées, ramenées à 25. */
    fun lit(remainingMs: Long, durationMs: Long, running: Boolean): Int {
        if (!running || durationMs <= 0 || remainingMs <= 0) return 0
        return ((remainingMs * COUNT + durationMs - 1) / durationMs).toInt().coerceIn(0, COUNT)
    }
}
