package app.mate.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.mate.net.MediaSession
import app.mate.net.YtMusicState
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ce qui joue, lu de l'ordinateur pour les petites formes de Lecture et de YouTube Music. [app] : l'appli à piloter
 * (Lecture) ; [coverKey] : change avec la pochette, null sans pochette.
 */
data class NowTrack(
    val app: String?,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val progress: Float?,
    val coverKey: String?,
) {
    companion object {
        /** Ce qui joue avant ce que Windows met en avant (sa session « en cours » est parfois une appli muette). */
        fun ofMedia(sessions: List<MediaSession>): NowTrack? {
            val s = sessions.firstOrNull { it.playing } ?: sessions.firstOrNull { it.current } ?: sessions.firstOrNull() ?: return null
            return NowTrack(s.app, s.title, s.artist, s.playing, progress(s.positionMs?.toDouble(), s.durationMs?.toDouble()), s.thumb?.let { "${s.app}|$it" })
        }

        fun ofYtMusic(state: YtMusicState): NowTrack? {
            val t = state.track?.takeIf { state.open } ?: return null
            return NowTrack(null, t.title, t.artist, state.playing, progress(t.position, t.duration), t.cover)
        }

        /** Entre 0 et 1 ; null sans durée (direct, ou titre pas encore chargé). */
        private fun progress(position: Double?, duration: Double?): Float? =
            if (position == null || duration == null || duration <= 0) null else (position / duration).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Relit [read] chaque seconde tant que le widget est à l'écran ([shown]) et charge la pochette quand [NowTrack.coverKey]
 * change, réduite à [coverPx] (la tablette a 128 Mo de tas). [send] : une action à la fois, puis relecture ; un double
 * appui n'envoie pas deux « suivant ».
 */
class PlayingLink(
    private val scope: CoroutineScope,
    private val shown: () -> Boolean,
    private val read: suspend () -> NowTrack?,
    private val cover: suspend (NowTrack) -> ByteArray?,
    private val send: suspend (NowTrack, String) -> Unit,
    private val coverPx: Int,
    private val onState: (Playing?) -> Unit,
) {
    private var poll: Job? = null
    private var track: NowTrack? = null
    private var art: Bitmap? = null
    private var artKey: String? = null
    private var sending = false

    fun setActive(active: Boolean) {
        poll?.cancel()
        poll = null
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                if (shown()) refresh()
                delay(POLL_MS)
            }
        }
    }

    fun action(op: String) {
        val now = track ?: return
        if (sending) return
        sending = true
        scope.launch {
            runCatchingCancellable { send(now, op) }
            // Le temps que l'appli change de morceau ou d'état.
            delay(SETTLE_MS)
            sending = false
            refresh()
        }
    }

    private suspend fun refresh() {
        // Ordinateur injoignable un instant : on garde l'affichage (l'Accueil voile le widget s'il ne répond plus).
        val next = runCatchingCancellable { read() }.getOrElse { return }
        track = next
        val key = next?.coverKey
        if (key != artKey) {
            artKey = key
            art = null
            if (next != null && key != null) {
                art = runCatchingCancellable { cover(next) }.getOrNull()?.let { withContext(Dispatchers.Default) { decode(it) } }
                // Échec (ordinateur occupé) : réessayé à la relecture suivante.
                if (art == null) artKey = null
            }
        }
        onState(next?.let { Playing(it.title, it.artist, it.playing, it.progress, art) })
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= coverPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val SETTLE_MS = 400L
    }
}
