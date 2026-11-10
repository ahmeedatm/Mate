package app.mate.ui

import app.mate.net.MediaSession

/**
 * Lecture en cours du header ([MusicChip], règles du petit lecteur de COMP-56) : quelle lecture, et quand l'afficher ;
 * puis l'îlot ouvert d'un toucher ([MusicIsland]) : sa place, son ouverture, sa fermeture.
 */
object MiniPlayerLogic {
    /** Une pause reste affichée ce temps-là après la dernière lecture vue (relancer sans aller sur Média). */
    const val PAUSE_KEEP_MS = 10 * 60_000L

    /** L'îlot ouvert se referme seul après ce temps sans toucher. */
    private const val AUTO_CLOSE_MS = 5_000L

    /** Part de l'ouverture où le contenu de l'îlot commence à apparaître : avant, il n'aurait pas la place. */
    private const val CONTENT_FROM = 0.4f

    /** Ce qui joue avant ce que l'ordinateur met en avant (comme la page Média). */
    fun pick(sessions: List<MediaSession>): MediaSession? =
        sessions.firstOrNull { it.playing } ?: sessions.firstOrNull { it.current } ?: sessions.firstOrNull()

    /** Visible pendant une lecture, puis [PAUSE_KEEP_MS] après la dernière lecture vue ; jamais pour une pause d'avant. */
    fun visible(session: MediaSession?, lastPlayingMs: Long?, nowMs: Long): Boolean =
        session != null && (session.playing || (lastPlayingMs != null && nowMs - lastPlayingMs <= PAUSE_KEEP_MS))

    /** Avance possible : l'appli le permet et le morceau a une durée (pas un direct). */
    fun seekable(session: MediaSession): Boolean = session.canSeek && (session.durationMs ?: 0) > 0

    /** « 1:33 », « 1:02:05 » au-delà d'une heure. */
    fun clock(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Bord gauche de l'îlot : centré sous la pastille, sans passer la marge d'un bord de l'écran. */
    fun islandLeft(chipCenterX: Int, width: Int, parentWidth: Int, margin: Int): Int =
        (chipCenterX - width / 2).coerceAtMost(parentWidth - margin - width).coerceAtLeast(margin)

    /** Délai de fermeture seule de l'îlot ; null sous TalkBack (exploration au toucher) : il se ferme au toucher ailleurs. */
    fun autoCloseMs(touchExploration: Boolean): Long? = if (touchExploration) null else AUTO_CLOSE_MS

    /** Opacité du contenu à l'avancement `t` de l'ouverture (0 : pastille, 1 : îlot ouvert). */
    fun contentAlpha(t: Float): Float = ((t - CONTENT_FROM) / (1f - CONTENT_FROM)).coerceIn(0f, 1f)

    /** Opacité du bandeau (heure, cloche, titre…) à l'avancement `t` : effacé avant que le contenu de l'îlot n'apparaisse. */
    fun barAlpha(t: Float): Float = (1f - t / CONTENT_FROM).coerceIn(0f, 1f)

    /**
     * Îlot à l'encre foncée du thème, comme la Dynamic Island : fond clair (luminance de `c_bg` au-dessus de
     * [LIGHT_BG]), l'îlot prend la couleur du texte et son texte celle du fond ; fond sombre, il garde le fond.
     */
    fun islandInverted(bgLuminance: Double): Boolean = bgLuminance > LIGHT_BG

    private const val LIGHT_BG = 0.5
}
