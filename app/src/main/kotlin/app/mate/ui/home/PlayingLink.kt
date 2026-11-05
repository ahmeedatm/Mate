package app.mate.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import app.mate.net.HttpException
import app.mate.net.MediaSession
import app.mate.net.YtMusicState
import app.mate.net.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Quand recharger la pochette : une nouvelle se charge ; une tenue ne se recharge pas ; un chargement interrompu se
 * refait ; après un échec (ordinateur qui n'en a pas), un seul nouvel essai toutes les [RETRY_MS], pas chaque seconde.
 */
class CoverTries {
    private var loadedKey: String? = null
    private var failedKey: String? = null
    private var failedAt = 0L

    fun shouldFetch(key: String?, now: Long): Boolean =
        key != null && key != loadedKey && (key != failedKey || now - failedAt >= RETRY_MS)

    fun loaded(key: String) {
        loadedKey = key
        failedKey = null
    }

    fun failed(key: String, now: Long) {
        failedKey = key
        failedAt = now
    }

    companion object {
        const val RETRY_MS = 30_000L
    }
}

/**
 * Relit [read] chaque seconde tant que le widget est à l'écran ([shown]) et charge la pochette quand [NowTrack.coverKey]
 * change ([CoverTries]), réduite à [coverPx] (la tablette a 128 Mo de tas). [action] : une à la fois, puis relecture ;
 * un double appui n'envoie pas deux « suivant ». Les relectures passent l'une après l'autre ; un 501 (l'ordinateur ne
 * sait pas lire ce qui joue) arrête la boucle.
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
    private var acting: Job? = null
    private val turn = Mutex()
    private var track: NowTrack? = null
    private var art: Bitmap? = null
    private val tries = CoverTries()
    private var sending = false

    fun setActive(active: Boolean) {
        poll?.cancel()
        acting?.cancel()
        poll = null
        sending = false
        if (!active) return
        poll = scope.launch {
            while (isActive) {
                if (shown() && !refresh()) break
                delay(POLL_MS)
            }
        }
    }

    fun action(op: String) {
        val now = track ?: return
        if (sending) return
        sending = true
        acting = scope.launch {
            runCatchingCancellable { send(now, op) }
            // Le temps que l'appli change de morceau ou d'état.
            delay(SETTLE_MS)
            sending = false
            refresh()
        }
    }

    /** Faux : l'ordinateur ne sait pas lire ce qui joue (501), rien à relire. */
    private suspend fun refresh(): Boolean = turn.withLock {
        val next = try {
            read()
        } catch (e: HttpException) {
            if (e.code == UNSUPPORTED) {
                track = null
                onState(null)
                return@withLock false
            }
            return@withLock true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ordinateur injoignable un instant : on garde l'affichage (l'Accueil voile le widget s'il ne répond plus).
            return@withLock true
        }
        if (next?.coverKey != track?.coverKey) art = null
        track = next
        val key = next?.coverKey
        if (next != null && key != null && tries.shouldFetch(key, SystemClock.elapsedRealtime())) {
            val loaded = runCatchingCancellable { cover(next) }.getOrNull()?.let { withContext(Dispatchers.Default) { decode(it) } }
            if (loaded != null) {
                art = loaded
                tries.loaded(key)
            } else {
                tries.failed(key, SystemClock.elapsedRealtime())
            }
        }
        onState(next?.let { Playing(it.title, it.artist, it.playing, it.progress, art) })
        true
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
        const val UNSUPPORTED = 501
    }
}
