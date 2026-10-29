package com.unowhy.companion.focus

import com.unowhy.companion.focus.NoisePlayer.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseKindTest {

    @Test
    fun `nom recu de l'ordinateur ou de la voix`() {
        assertEquals(Kind.FOREST, Kind.of("forest"))
        assertEquals(Kind.LOFI, Kind.of("LOFI"))
        assertNull(Kind.of("orage"))
    }

    @Test
    fun `les anciens bruits deviennent la pluie`() {
        // Un compagnon d'avant les ambiances enregistrées envoie encore brown, pink ou white.
        assertEquals(Kind.STORM, Kind.of("brown"))
        assertEquals(Kind.STORM, Kind.of("pink"))
        assertEquals(Kind.STORM, Kind.of("white"))
    }
}
