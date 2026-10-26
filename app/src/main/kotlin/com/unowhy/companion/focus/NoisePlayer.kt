package com.unowhy.companion.focus

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.random.Random

/**
 * Sons d'ambiance générés en temps réel (aucun fichier audio dans l'APK).
 * 22 kHz mono 16 bits : quelques opérations par échantillon, négligeable même sur un petit CPU.
 */
object NoisePlayer {

    enum class Kind { WHITE, PINK, BROWN }

    private const val SAMPLE_RATE = 22_050

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
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("DEPRECATION") // AudioTrack.Builder impose l'API 23 ; ce constructeur marche partout
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4, AudioTrack.MODE_STREAM,
        )
        val buffer = ShortArray(2_048)
        val random = Random.Default
        // État des filtres : bruit rose (méthode de Paul Kellet) et bruit brun (intégrateur à fuite).
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var b3 = 0f; var b4 = 0f; var b5 = 0f; var b6 = 0f
        var brown = 0f
        var gain = 0f // montée progressive pour éviter un "clic" au démarrage

        try {
            track.play()
            while (!Thread.currentThread().isInterrupted && playingKind == target) {
                for (i in buffer.indices) {
                    val white = random.nextFloat() * 2f - 1f
                    val sample = when (target) {
                        Kind.WHITE -> white * 0.5f
                        Kind.PINK -> {
                            b0 = 0.99886f * b0 + white * 0.0555179f
                            b1 = 0.99332f * b1 + white * 0.0750759f
                            b2 = 0.96900f * b2 + white * 0.1538520f
                            b3 = 0.86650f * b3 + white * 0.3104856f
                            b4 = 0.55000f * b4 + white * 0.5329522f
                            b5 = -0.7616f * b5 - white * 0.0168980f
                            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
                            b6 = white * 0.115926f
                            pink * 0.11f
                        }
                        Kind.BROWN -> {
                            brown = (brown + white * 0.02f) / 1.02f
                            brown * 3.5f
                        }
                    }
                    if (gain < 1f) gain += 1f / SAMPLE_RATE
                    buffer[i] = (sample.coerceIn(-1f, 1f) * gain * volume * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buffer, 0, buffer.size)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
