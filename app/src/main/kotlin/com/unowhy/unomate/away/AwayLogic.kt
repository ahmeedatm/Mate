package com.unowhy.unomate.away

/**
 * Absence en cours (widget « Je m'absente / Reprendre »). `sinceMs` : début, en heure du système ; `micMutedByUs` : c'est
 * le widget qui a coupé le micro de l'ordinateur ; `focusPausedByUs` : c'est lui qui a mis le Focus en pause. Ces deux
 * indicateurs disent ce que la reprise doit rendre, et rien de plus.
 */
data class AwayState(val sinceMs: Long, val micMutedByUs: Boolean, val focusPausedByUs: Boolean)

object AwayLogic {

    /** Début d'absence : le micro n'est coupé, et le Focus mis en pause, que s'ils ne l'étaient pas déjà. */
    fun begin(nowMs: Long, micAlreadyMuted: Boolean, focusRunning: Boolean) =
        AwayState(nowMs, micMutedByUs = !micAlreadyMuted, focusPausedByUs = focusRunning)

    /** Minutes écoulées depuis le début (jamais négatif, même si l'horloge a reculé). */
    fun minutes(state: AwayState, nowMs: Long): Int = ((nowMs - state.sinceMs) / 60_000L).coerceAtLeast(0L).toInt()

    /** Rouvrir le micro seulement si l'absence l'a coupé. */
    fun shouldUnmute(state: AwayState) = state.micMutedByUs

    /**
     * Relancer le Focus seulement si l'absence l'a mis en pause, qu'il est toujours en pause, et qu'il a de quoi reprendre :
     * un chrono entier (appli relancée pendant l'absence) n'est pas un chrono interrompu.
     */
    fun shouldResumeFocus(state: AwayState, running: Boolean, remainingMs: Long, durationMs: Long) =
        state.focusPausedByUs && !running && remainingMs < durationMs
}
