package app.mate.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Deux sons courts et discrets pour les agents Claude, fabriqués comme [Crunch] : « attend » (deux notes qui montent, on
 * te veut) et « fini » (une seule note grave, c'est fait). Pas de voix.
 */
object AgentSounds {
    private const val RATE = 22_050

    private val waiting: ShortArray by lazy { build(listOf(Note(660f, 0), Note(880f, 150)), totalMs = 520, decayMs = 110) }
    private val done: ShortArray by lazy { build(listOf(Note(523f, 0)), totalMs = 420, decayMs = 140) }

    fun waiting() = PcmPlayer.play(waiting, RATE)

    fun done() = PcmPlayer.play(done, RATE)

    private class Note(val hz: Float, val startMs: Int)

    /** Cloche douce : un sinus et son harmonique, qui s'éteint en exponentielle (`decayMs` : temps pour perdre 63 %). */
    private fun build(notes: List<Note>, totalMs: Int, decayMs: Int): ShortArray {
        val out = FloatArray(RATE * totalMs / 1000)
        for (note in notes) {
            val start = RATE * note.startMs / 1000
            for (i in 0 until out.size - start) {
                val t = i.toFloat() / RATE
                val envelope = exp(-t * 1000f / decayMs) * (1f - exp(-t * 600f))
                val wave = sin(2 * PI * note.hz * t).toFloat() + 0.3f * sin(2 * PI * note.hz * 2 * t).toFloat()
                out[start + i] += AMPLITUDE * envelope * wave
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Discret : un tiers de la pleine échelle. */
    private const val AMPLITUDE = 0.33f
}
