package com.unowhy.companion.focus

import com.unowhy.companion.focus.Breathing.Pattern
import com.unowhy.companion.focus.Breathing.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class BreathingTest {

    @Test
    fun `duree d'un cycle`() {
        assertEquals(10_000L, Pattern.COHERENCE.cycleMs)
        assertEquals(16_000L, Pattern.BOX.cycleMs)
        assertEquals(19_000L, Pattern.RELAX.cycleMs)
    }

    @Test
    fun `etapes du 4-7-8`() {
        val start = Breathing.at(Pattern.RELAX, 0)
        assertEquals(Step.INHALE, start.step)
        assertEquals(4, start.secondsLeft)
        assertEquals(0, start.cycle)
        assertEquals(3, Breathing.at(Pattern.RELAX, 1_500).secondsLeft)
        assertEquals(Step.HOLD_IN, Breathing.at(Pattern.RELAX, 4_000).step)
        assertEquals(7, Breathing.at(Pattern.RELAX, 4_000).secondsLeft)
        assertEquals(Step.EXHALE, Breathing.at(Pattern.RELAX, 11_000).step)
        val next = Breathing.at(Pattern.RELAX, 19_000)
        assertEquals(Step.INHALE, next.step)
        assertEquals(1, next.cycle)
    }

    @Test
    fun `l'onde monte, tient puis redescend`() {
        assertEquals(0f, Breathing.at(Pattern.BOX, 0).level, 1e-4f)
        assertEquals(0.5f, Breathing.at(Pattern.BOX, 2_000).level, 1e-4f)
        assertEquals(1f, Breathing.at(Pattern.BOX, 6_000).level, 1e-4f)
        assertEquals(0.5f, Breathing.at(Pattern.BOX, 10_000).level, 1e-4f)
        assertEquals(0f, Breathing.at(Pattern.BOX, 14_000).level, 1e-4f)
    }

    @Test
    fun `l'exercice finit sur un cycle entier`() {
        assertEquals(300_000L, Breathing.endMs(Pattern.COHERENCE, 5))
        // 3 min = 9,47 cycles de 19 s : on va jusqu'au 10e.
        assertEquals(190_000L, Breathing.endMs(Pattern.RELAX, 3))
    }
}
