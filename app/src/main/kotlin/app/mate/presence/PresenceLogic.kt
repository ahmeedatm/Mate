package app.mate.presence

/**
 * Règles de la veille à la présence, sans Android ni horloge : l'appelant donne l'heure (ms). Mouvement ou toucher
 * repoussent la veille ; après [delayMs] sans rien, l'écran s'endort ; il se réveille à un toucher, à deux images en
 * mouvement de suite (une seule peut être un parasite) ou dès que l'écran sert (appel, Focus…). Tant que l'écran sert,
 * le délai ne court pas.
 */
class PresenceLogic(private val delayMs: Long = SLEEP_DELAY_MS) {

    enum class Change { NONE, SLEEP, WAKE }

    var asleep = false
        private set
    private var lastSeen = 0L
    private var streak = 0

    /** Surveillance lancée (ou relancée) : écran éveillé, délai reparti de [now]. */
    fun start(now: Long) {
        asleep = false
        lastSeen = now
        streak = 0
    }

    /** Une image analysée : [moved] = mouvement vu ; [busy] = l'écran sert et ne doit pas dormir. */
    fun onFrame(moved: Boolean, busy: Boolean, now: Long): Change {
        streak = if (moved) streak + 1 else 0
        if (busy) return seen(now)
        if (asleep) return if (streak >= 2) seen(now) else Change.NONE
        if (moved) lastSeen = now
        if (now - lastSeen < delayMs) return Change.NONE
        asleep = true
        return Change.SLEEP
    }

    /** Toucher ou touche : présence certaine. */
    fun onTouch(now: Long): Change = seen(now)

    /** Caméra perdue ou arrêtée : plus rien ne peut réveiller l'écran, il se réveille. */
    fun onLost(now: Long): Change = seen(now)

    private fun seen(now: Long): Change {
        lastSeen = now
        if (!asleep) return Change.NONE
        asleep = false
        streak = 0
        return Change.WAKE
    }

    companion object {
        /** Délai sans présence avant la veille : assez long pour lire sans bouger, assez court pour servir. */
        const val SLEEP_DELAY_MS = 5 * 60_000L
    }
}
