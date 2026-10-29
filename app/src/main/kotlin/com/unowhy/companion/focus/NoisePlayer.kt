package com.unowhy.companion.focus

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RawRes
import com.unowhy.companion.R

/**
 * Sons d'ambiance : enregistrements libres de l'APK (`res/raw/ambiance_*.webm`, `scripts/ambiances.mjs` : même volume
 * bas pour tous, fin fondue dans le début). Décodé au fil de la lecture ([AmbianceDecoder], une seconde d'avance : le
 * son part tout de suite), gardé en mémoire (mono 24 kHz, ~7 Mo pour 2 min 30) et bouclé sans blanc par un
 * AudioTrack ; le dernier son entier est gardé pour la reprise après une pause.
 */
@SuppressLint("StaticFieldLeak")
object NoisePlayer {

    /** Ordre des pastilles de Focus et de l'Accueil. Les noms partent vers l'ordinateur (`tablette_etat`, MCP). */
    enum class Kind(@RawRes val raw: Int) {
        STORM(R.raw.ambiance_storm),
        FOREST(R.raw.ambiance_forest),
        JAPAN(R.raw.ambiance_japan),
        SEA(R.raw.ambiance_sea),
        LOFI(R.raw.ambiance_lofi),
        ;

        companion object {
            /** Nom reçu de l'ordinateur ou de la voix ; les anciens bruits (brown, pink, white) deviennent la pluie. */
            fun of(name: String): Kind? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: STORM.takeIf { name.lowercase() in setOf("brown", "pink", "white") }
        }
    }

    private const val TAG = "NoisePlayer"

    /** Contexte de l'application ([init]) : il vit autant que le processus, rien à fuir. */
    private lateinit var context: Context

    /** Appelé par MainActivity : les sons se lisent dans les ressources de l'appli. */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /** Dernier son décodé en entier (le chrono en pause coupe le fil, la reprise ne redécode pas). */
    @Volatile private var decoded: Pair<Kind, AmbianceDecoder>? = null

    /** Ambiance choisie (null : aucune). Elle peut être choisie sans jouer : coupée, ou chrono en pause. */
    @Volatile var kind: Kind? = null
        private set
    @Volatile var volume = 0.5f
        private set
    /** Bouton haut-parleur de Focus. */
    @Volatile var muted = false
        private set
    /** Chrono mis en pause après avoir été lancé : l'ambiance attend qu'il reprenne. */
    private var heldByTimer = false
    @Volatile private var playingKind: Kind? = null
    private var thread: Thread? = null
    private val listeners = mutableSetOf<() -> Unit>()

    /** Prévient l'écran Focus quand le son change ailleurs (ex. depuis le Mac). Appels sur le thread principal. */
    fun observe(listener: () -> Unit) {
        listeners += listener
    }

    fun unobserve(listener: () -> Unit) {
        listeners -= listener
    }

    /** Ce qu'on entend vraiment. */
    val playing: Boolean
        get() = kind != null && !muted && !heldByTimer

    /** Choisir une ambiance la fait entendre tout de suite, même chrono arrêté. */
    fun play(newKind: Kind) {
        kind = newKind
        muted = false
        heldByTimer = false
        sync()
    }

    fun stop() {
        kind = null
        sync()
    }

    fun setMuted(value: Boolean) {
        muted = value
        sync()
    }

    /** Appelé par [FocusTimer] quand il démarre ou s'arrête. */
    internal fun followTimer(running: Boolean) {
        heldByTimer = !running
        sync()
    }

    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (v == volume) return
        volume = v
        notifyListeners()
    }

    private fun sync() {
        val target = kind.takeIf { playing }
        if (target != playingKind) {
            stopThread()
            if (target != null) {
                playingKind = target
                thread = Thread({ loop(target) }, "noise").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
            }
        }
        notifyListeners()
    }

    private fun stopThread() {
        playingKind = null
        // Sans join (appelé depuis le fil principal) : l'ancien fil sort seul à la fin de son tampon (isInterrupted).
        thread?.interrupt()
        thread = null
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    private fun loop(target: Kind) {
        val source = decoded?.takeIf { it.first == target }?.second ?: try {
            AmbianceDecoder(context, target.raw)
        } catch (e: Exception) {
            Log.w(TAG, "son illisible : $target", e)
            return
        }
        val start = SystemClock.elapsedRealtime()
        var track: AudioTrack? = null
        try {
            // Une demi-seconde d'avance avant de jouer : le décodeur va bien plus vite que la lecture.
            while (!source.done && !source.failed && source.size < source.rate / 2) {
                if (playingKind != target) return
                Thread.sleep(20)
            }
            if (source.size == 0) return
            val minBuffer = AudioTrack.getMinBufferSize(source.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("DEPRECATION") // AudioTrack.Builder impose l'API 23 ; ce constructeur marche partout
            val output = AudioTrack(
                AudioManager.STREAM_MUSIC, source.rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4, AudioTrack.MODE_STREAM,
            ).also { track = it }
            val buffer = ShortArray(2_048)
            var position = 0
            var gain = 0f // montée progressive (une seconde) pour éviter un "clic" au démarrage
            var cached = false
            output.play()
            while (!Thread.currentThread().isInterrupted && playingKind == target) {
                val done = source.done
                val size = source.size
                val samples = source.samples
                if (done && !cached) {
                    cached = true
                    decoded = target to source
                    Log.i(TAG, "$target décodé en ${SystemClock.elapsedRealtime() - start} ms")
                }
                if (!done && size - position < buffer.size) {
                    // Décodeur en retard (ou en panne) : on attend sans boucler sur un son incomplet.
                    if (source.failed) return
                    Thread.sleep(10)
                    continue
                }
                for (i in buffer.indices) {
                    if (gain < 1f) gain += 1f / source.rate
                    buffer[i] = (samples[position] * gain * volume).toInt().toShort()
                    position = if (position + 1 >= size) 0 else position + 1
                }
                output.write(buffer, 0, buffer.size)
            }
        } catch (e: InterruptedException) {
            // autre ambiance, pause ou arrêt
        } finally {
            if (!source.done) source.release()
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }
}
