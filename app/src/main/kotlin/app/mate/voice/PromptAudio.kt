package app.mate.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/** Dictée d'un prompt pour une session Claude : 16 kHz mono 16 bits, ce qu'attend whisper.cpp sur l'ordinateur. */
object PromptAudio {
    const val RATE = 16_000
    /** Une mesure du niveau tous les 100 ms. */
    const val FRAME_MS = 100
    const val FRAME_SAMPLES = RATE * FRAME_MS / 1000
    /** Au-delà, la dictée s'arrête : le compagnon refuse plus long. */
    const val MAX_MS = 60_000

    /** En-tête WAV PCM de 44 octets devant `pcm` (16 bits petit-boutiste, mono). */
    fun wav(pcm: ByteArray, rate: Int = RATE): ByteArray =
        ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(rate)
            putInt(rate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
            put(pcm)
        }.array()

    /** Niveau affiché (halo du micro) entre 0 et 1 : 0 au seuil de la parole, 1 à 30 dB au-dessus. */
    fun level(rms: Double): Float {
        if (rms <= SilenceGate.MIN_SPEECH) return 0f
        return (20 * log10(rms / SilenceGate.MIN_SPEECH) / 30).toFloat().coerceIn(0f, 1f)
    }

    /** Niveau efficace des `n` premiers échantillons. */
    fun rms(samples: ShortArray, n: Int = samples.size): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / n)
    }
}

/**
 * Quand arrêter la dictée : après [QUIET_MS] de silence qui suivent de la parole, après [NOTHING_MS] sans parole, ou à
 * [PromptAudio.MAX_MS]. Parole = niveau au moins [RATIO] fois le plus bas entendu (le bruit de la pièce), et jamais sous
 * [MIN_SPEECH] : le micro de la tablette n'a pas de niveau connu d'avance.
 */
class SilenceGate {
    enum class Verdict { LISTEN, DONE }

    var heard = false
        private set
    private var floor = Double.MAX_VALUE
    private var elapsed = 0
    private var quiet = 0

    fun feed(rms: Double, frameMs: Int = PromptAudio.FRAME_MS): Verdict {
        elapsed += frameMs
        if (rms < floor) floor = rms
        if (rms >= maxOf(MIN_SPEECH, floor * RATIO)) {
            heard = true
            quiet = 0
        } else {
            quiet += frameMs
        }
        return when {
            elapsed >= PromptAudio.MAX_MS -> Verdict.DONE
            heard && quiet >= QUIET_MS -> Verdict.DONE
            !heard && elapsed >= NOTHING_MS -> Verdict.DONE
            else -> Verdict.LISTEN
        }
    }

    companion object {
        const val QUIET_MS = 1_500
        const val NOTHING_MS = 8_000
        const val RATIO = 2.5
        const val MIN_SPEECH = 300.0
    }
}
