package app.mate.ui

/** Calage sur la minute pleine : ce qui dépend de l'heure avance d'un seul geste, à `hh:mm:00`. */
object MinuteClock {
    /** Marge après la minute : l'attente d'une coroutine ne part jamais en avance, mais l'horloge murale dérive de quelques ms. */
    const val SLACK_MS = 30L

    /** Millisecondes jusqu'à la prochaine minute pleine : de 1 à 60 000, jamais 0 (pile sur la minute, la suivante). */
    fun msToNextMinute(now: Long): Long = 60_000L - Math.floorMod(now, 60_000L)
}
