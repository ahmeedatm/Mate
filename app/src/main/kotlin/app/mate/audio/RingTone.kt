package app.mate.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Sonnerie de « Faire sonner la tablette » : trois bips aigus puis un silence, en boucle, à pleine échelle (la tablette
 * peut être cachée sous des papiers). Fabriquée comme [AgentSounds], sans fichier.
 */
object RingTone {
    const val RATE = 22_050
    const val PERIOD_MS = 1_600

    /** Une sonnerie oubliée s'éteint seule : la batterie de la tablette est vieille. */
    const val AUTO_STOP_MS = 120_000L

    private const val BEEP_MS = 140
    private const val GAP_MS = 110
    private const val BEEPS = 3
    private const val HZ = 2_400.0

    fun samples(): ShortArray {
        val out = ShortArray(RATE * PERIOD_MS / 1000)
        val beep = RATE * BEEP_MS / 1000
        val step = RATE * (BEEP_MS + GAP_MS) / 1000
        for (n in 0 until BEEPS) {
            for (i in 0 until beep) {
                // Montée et descente de 5 ms : pas de claquement au début et à la fin d'un bip.
                val edge = minOf(i, beep - 1 - i).toDouble() / (RATE * 5 / 1000)
                val wave = sin(2 * PI * HZ * i / RATE) * minOf(1.0, edge)
                out[n * step + i] = (wave * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
