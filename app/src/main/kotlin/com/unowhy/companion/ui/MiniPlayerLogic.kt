package com.unowhy.companion.ui

import com.unowhy.companion.net.MediaSession

/** Petit lecteur en haut à droite (MiniPlayerView) : quelle lecture, et quand l'afficher. */
object MiniPlayerLogic {
    /** Une pause reste affichée ce temps-là après la dernière lecture vue (relancer sans aller sur Média). */
    const val PAUSE_KEEP_MS = 10 * 60_000L

    /** Ce qui joue avant ce que l'ordinateur met en avant (comme la page Média). */
    fun pick(sessions: List<MediaSession>): MediaSession? =
        sessions.firstOrNull { it.playing } ?: sessions.firstOrNull { it.current } ?: sessions.firstOrNull()

    /** Visible pendant une lecture, puis [PAUSE_KEEP_MS] après la dernière lecture vue ; jamais pour une pause d'avant. */
    fun visible(session: MediaSession?, lastPlayingMs: Long?, nowMs: Long): Boolean =
        session != null && (session.playing || (lastPlayingMs != null && nowMs - lastPlayingMs <= PAUSE_KEEP_MS))
}
