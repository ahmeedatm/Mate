package com.unowhy.unomate.audio

import android.content.Context
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Petit « croc » (fenêtre jetée dans la corbeille) fabriqué à la volée : un bruit sourd puis quelques craquements
 * de bruit filtré qui s'éteignent. Pas de fichier son à embarquer ; à peine un tiers de seconde.
 */
object Crunch {
    private const val RATE = 22_050

    private val samples: ShortArray by lazy { build() }

    fun play(context: Context) = PcmPlayer.play(samples, RATE)

    private fun build(): ShortArray {
        val out = FloatArray(RATE * 340 / 1000)
        val random = Random(7)
        // Bouchée : un bruit sourd qui descend.
        for (i in 0 until RATE * 70 / 1000) {
            val t = i.toFloat() / RATE
            out[i] += 0.55f * sin(2 * PI * (140 - 500 * t) * t).toFloat() * exp(-t / 0.025f)
        }
        // Craquements : salves de bruit aigu (bruit dérivé = plus croustillant), de plus en plus faibles.
        val grains = listOf(0 to 0.9f, 55 to 0.65f, 105 to 0.8f, 165 to 0.5f, 230 to 0.3f)
        for ((startMs, amp) in grains) {
            val start = RATE * startMs / 1000
            var previous = 0f
            for (i in 0 until RATE * 45 / 1000) {
                val index = start + i
                if (index >= out.size) break
                val noise = random.nextFloat() * 2 - 1
                val crisp = noise - previous
                previous = noise
                out[index] += amp * 0.5f * crisp * exp(-i.toFloat() / (RATE * 0.009f))
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE * 0.8f).toInt().toShort() }
    }
}
